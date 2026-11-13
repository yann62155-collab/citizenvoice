// Citizen Voix — page du chat vocal (téléphone, Xbox, tablette, PC sans mod)
(() => {
  const $ = (id) => document.getElementById(id);
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} },
  };

  let ws = null, ctx = null, capture = null, player = null, stream = null, wakeLock = null;
  let authed = false, muted = false, deaf = false, whisper = false, ptt = false, pttDown = false;
  let whisperDist = 3;
  let vadHold = 0, retry = 0, stopping = false, pending = null;

  const show = (id) => ['login', 'resume', 'app'].forEach((s) => $(s).classList.toggle('hidden', s !== id));
  const setErr = (id, t) => { $(id).textContent = t || ''; };

  // ---------- écran de départ
  const params = new URLSearchParams(location.search);
  const urlCode = params.get('code');
  if (urlCode) { $('code').value = urlCode.replace(/\D/g, '').slice(0, 6); history.replaceState(null, '', '/'); }
  const savedToken = store.get('cv_token');
  if (savedToken && !urlCode) { $('resumeName').textContent = store.get('cv_name') || ''; show('resume'); } else show('login');

  $('code').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6); });
  $('code').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('join').click(); });
  $('join').onclick = () => {
    const code = $('code').value.trim();
    if (code.length !== 6) return setErr('loginErr', 'Le code fait 6 chiffres.');
    start({ t: 'auth', code }, 'loginErr');
  };
  $('resumeBtn').onclick = () => start({ t: 'auth', token: store.get('cv_token') }, 'resumeErr');
  $('forget').onclick = (e) => { e.preventDefault(); store.set('cv_token', null); show('login'); };
  $('leave').onclick = () => { stopping = true; store.set('cv_token', null); teardown(); show('login'); };

  // ---------- audio
  async function startAudio() {
    if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error("Ce navigateur bloque le micro sur cette page. Ouvre l'adresse en https:// (et accepte le certificat).");
    }
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
    });
    ctx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    await ctx.audioWorklet.addModule('/worklet.js');
    const src = ctx.createMediaStreamSource(stream);
    const lp = ctx.createBiquadFilter(); // anti-repliement avant le passage à 16 kHz
    lp.type = 'lowpass'; lp.frequency.value = 7000;
    capture = new AudioWorkletNode(ctx, 'cv-capture', { numberOfInputs: 1, numberOfOutputs: 0 });
    src.connect(lp).connect(capture);
    player = new AudioWorkletNode(ctx, 'cv-player', { numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [2] });
    player.connect(ctx.destination);
    player.port.postMessage({ volume: $('vol').value / 100 });
    capture.port.onmessage = (e) => onMicFrame(e.data.pcm, e.data.rms);
    if (ctx.state === 'suspended') await ctx.resume();
    try { if ('wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen'); } catch {}
  }

  function onMicFrame(pcm, rms) {
    // vu-mètre (échelle log)
    const lvl = Math.max(0, Math.min(1, (20 * Math.log10(rms + 1e-6) + 60) / 60));
    $('meter').style.width = (lvl * 100).toFixed(0) + '%';
    if (!authed || muted || !ws || ws.readyState !== 1) return;
    let active;
    if (ptt) active = pttDown;
    else {
      const threshold = 1 - $('sens').value / 100; // sensibilité haute = seuil bas
      if (lvl > threshold * 0.8) vadHold = 15; // garde le micro ouvert 300 ms après la dernière parole
      active = vadHold-- > 0;
    }
    if (active && ws.bufferedAmount < 64000) ws.send(pcm);
  }

  // ---------- réseau
  async function start(authMsg, errBox) {
    setErr(errBox, '');
    stopping = false;
    try {
      if (!ctx) await startAudio();
    } catch (e) {
      setErr(errBox, e.name === 'NotAllowedError' ? "Autorise l'accès au micro pour parler." : (e.message || String(e)));
      return;
    }
    pending = authMsg;
    connect();
  }

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(proto + location.host + '/ws');
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      retry = 0;
      ws.send(JSON.stringify(pending || { t: 'auth', token: store.get('cv_token') }));
    };
    ws.onmessage = (e) => {
      if (typeof e.data !== 'string') return onAudio(e.data);
      const m = JSON.parse(e.data);
      if (m.t === 'ok') {
        authed = true;
        store.set('cv_token', m.token); store.set('cv_name', m.name);
        pending = { t: 'auth', token: m.token };
        $('dist').textContent = '(' + m.distance + ' blocs)';
        whisperDist = m.whisper || 3;
        $('whisper').textContent = whisper ? '🤫 Chuchotement (' + whisperDist + ' blocs)' : '🗣 Voix normale';
        show('app'); setErr('appErr', '');
        ws.send(JSON.stringify({ t: 'mute', v: muted }));
        ws.send(JSON.stringify({ t: 'deaf', v: deaf }));
        ws.send(JSON.stringify({ t: 'whisper', v: whisper }));
      } else if (m.t === 'state') renderState(m);
      else if (m.t === 'err') {
        if (!authed) {
          const box = $('app').classList.contains('hidden') ? ($('resume').classList.contains('hidden') ? 'loginErr' : 'resumeErr') : 'appErr';
          setErr(box, m.msg);
          if (m.expired) { store.set('cv_token', null); show('login'); setErr('loginErr', m.msg); }
          stopping = true; ws.close();
        } else { setErr('appErr', m.msg); }
        if (m.fatal) { stopping = true; }
      }
    };
    ws.onclose = () => {
      const wasAuthed = authed;
      authed = false;
      if (stopping) return;
      if (wasAuthed || retry > 0) {
        setStatus('warn', 'Connexion perdue, reconnexion…');
        retry++;
        setTimeout(connect, Math.min(10000, 1000 * retry));
      }
    };
  }

  function onAudio(buf) {
    if (!player || deaf) return;
    const dv = new DataView(buf);
    if (dv.getUint8(0) !== 1) return;
    const id = dv.getInt32(4, true), gain = dv.getFloat32(8, true), pan = dv.getFloat32(12, true);
    const pcm = buf.slice(16);
    player.port.postMessage({ id, gain, pan, pcm }, [pcm]);
  }

  function teardown() {
    authed = false;
    try { ws && ws.close(); } catch {}
    try { stream && stream.getTracks().forEach((t) => t.stop()); } catch {}
    try { ctx && ctx.close(); } catch {}
    try { wakeLock && wakeLock.release(); } catch {}
    ws = ctx = capture = player = stream = wakeLock = null;
  }

  // ---------- interface
  function setStatus(kind, text) {
    $('stDot').className = 'dot' + (kind === 'on' ? ' on' : kind === 'warn' ? ' warn' : '');
    $('stText').textContent = text;
  }

  function renderState(m) {
    if (!m.inGame) setStatus('warn', 'Connecté — rejoins le serveur en jeu pour parler');
    else setStatus('on', muted ? 'En jeu — micro coupé' : (m.speaking ? (whisper ? 'En jeu — tu chuchotes…' : 'En jeu — tu parles…') : 'En jeu — on t’entend'));
    const ul = $('near');
    ul.textContent = '';
    for (const p of m.near) {
      const li = document.createElement('li');
      const dot = document.createElement('span'); dot.className = 'speak' + (p.s ? ' on' : '');
      const n = document.createElement('span'); n.className = 'n'; n.textContent = p.n;
      const d = document.createElement('span'); d.className = 'd'; d.textContent = p.d + ' m';
      const tag = document.createElement('span');
      tag.className = 'tag' + (p.v ? '' : ' no');
      tag.textContent = p.w ? 'téléphone/console' : (p.v ? 'PC' : 'sans voix');
      li.append(dot, n, tag, d);
      ul.append(li);
    }
    $('nobody').classList.toggle('hidden', m.near.length > 0);
  }

  $('mute').onclick = () => {
    muted = !muted;
    $('mute').textContent = muted ? '🔇 Micro coupé' : '🎙 Micro actif';
    $('mute').classList.toggle('off', muted);
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'mute', v: muted }));
  };
  $('deaf').onclick = () => {
    deaf = !deaf;
    $('deaf').textContent = deaf ? '🔈 Sourdine' : '🔊 Son actif';
    $('deaf').classList.toggle('off', deaf);
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'deaf', v: deaf }));
  };
  $('whisper').onclick = () => {
    whisper = !whisper;
    $('whisper').textContent = whisper ? '🤫 Chuchotement (' + whisperDist + ' blocs)' : '🗣 Voix normale';
    $('whisper').classList.toggle('off', !whisper);
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'whisper', v: whisper }));
  };
  $('vol').oninput = () => player && player.port.postMessage({ volume: $('vol').value / 100 });

  const setMode = (p) => {
    ptt = p;
    $('modePtt').classList.toggle('off', !p);
    $('modeVoice').classList.toggle('off', p);
    $('ptt').classList.toggle('hidden', !p);
    $('vadBox').classList.toggle('hidden', p);
    store.set('cv_ptt', p ? '1' : '0');
  };
  $('modePtt').onclick = () => setMode(true);
  $('modeVoice').onclick = () => setMode(false);
  setMode(store.get('cv_ptt') === '1');
  const sens = store.get('cv_sens'); if (sens) $('sens').value = sens;
  $('sens').oninput = () => store.set('cv_sens', $('sens').value);

  const down = (e) => { e.preventDefault(); pttDown = true; $('ptt').classList.add('talking'); };
  const up = () => { pttDown = false; $('ptt').classList.remove('talking'); };
  $('ptt').addEventListener('pointerdown', down);
  ['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => $('ptt').addEventListener(t, up));
  // manette Xbox / clavier : maintenir Espace ou Entrée sur le bouton
  $('ptt').addEventListener('keydown', (e) => { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) down(e); });
  $('ptt').addEventListener('keyup', up);

  // ping pour garder la connexion ouverte
  setInterval(() => { if (ws && ws.readyState === 1) ws.send('{"t":"ping"}'); }, 15000);
  document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState === 'visible') {
      if (ctx && ctx.state !== 'running') { try { await ctx.resume(); } catch {} }
      if (wakeLock === null && ctx && 'wakeLock' in navigator) { try { wakeLock = await navigator.wakeLock.request('screen'); } catch {} }
    }
  });
})();
