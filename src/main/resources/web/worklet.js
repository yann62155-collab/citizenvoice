// Citizen Voix — traitement audio en temps réel (AudioWorklet)
const NET_RATE = 16000;
const FRAME = 320; // 20 ms à 16 kHz

// Micro -> trames PCM16 16 kHz
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.step = sampleRate / NET_RATE; // échantillons d'entrée par échantillon de sortie
    this.pos = 1;
    this.prev = 0;
    this.buf = new Int16Array(FRAME);
    this.n = 0;
    this.acc = 0; this.accN = 0;
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    for (let i = 0; i < ch.length; i++) {
      const s = ch[i];
      this.acc += s * s; this.accN++;
      // pos = position (en échantillons d'entrée) du prochain échantillon de sortie, relative à prev
      while (this.pos <= 1) {
        const v = Math.max(-1, Math.min(1, this.prev + (s - this.prev) * this.pos));
        this.buf[this.n++] = v < 0 ? v * 0x8000 : v * 0x7fff;
        if (this.n === FRAME) {
          const rms = Math.sqrt(this.acc / Math.max(1, this.accN));
          this.port.postMessage({ pcm: this.buf.buffer, rms }, [this.buf.buffer]);
          this.buf = new Int16Array(FRAME);
          this.n = 0; this.acc = 0; this.accN = 0;
        }
        this.pos += this.step;
      }
      this.pos -= 1;
      this.prev = s;
    }
    return true;
  }
}

// Voix reçues -> haut-parleurs (une file par joueur, mixage stéréo avec panoramique)
class PlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.speakers = new Map();
    this.step = NET_RATE / sampleRate;
    this.volume = 1;
    this.port.onmessage = (e) => {
      const m = e.data;
      if (m.volume !== undefined) { this.volume = m.volume; return; }
      let sp = this.speakers.get(m.id);
      if (!sp) {
        sp = { q: [], cur: null, idx: 0, frac: 0, started: false, gain: m.gain, pan: m.pan, last: 0, g: m.gain, p: m.pan };
        this.speakers.set(m.id, sp);
      }
      sp.gain = m.gain; sp.pan = m.pan;
      sp.q.push(new Int16Array(m.pcm));
      sp.last = currentTime;
      // retard maximum ~300 ms : on jette le plus ancien
      while (sp.q.length > 15) sp.q.shift();
    };
  }
  process(_inputs, outputs) {
    const out = outputs[0];
    const L = out[0], R = out[1] || out[0];
    L.fill(0); if (R !== L) R.fill(0);
    for (const [id, sp] of this.speakers) {
      if (!sp.started) {
        if (sp.q.length >= 3) sp.started = true; // tampon anti-saccades de 60 ms
        else { if (currentTime - sp.last > 5) this.speakers.delete(id); continue; }
      }
      for (let i = 0; i < L.length; i++) {
        if (!sp.cur || sp.idx >= FRAME) {
          if (sp.cur) sp.idx -= FRAME;
          sp.cur = sp.q.shift() || null;
          if (!sp.cur) { sp.started = false; sp.idx = 0; break; }
        }
        // lissage du gain et du panoramique pour éviter les clics
        sp.g += (sp.gain - sp.g) * 0.002;
        sp.p += (sp.pan - sp.p) * 0.002;
        const i0 = Math.floor(sp.idx), f = sp.idx - i0;
        const s0 = sp.cur[i0];
        const s1 = i0 + 1 < FRAME ? sp.cur[i0 + 1] : (sp.q.length ? sp.q[0][0] : s0);
        const v = (s0 + (s1 - s0) * f) / 32768 * sp.g * this.volume;
        const a = (sp.p + 1) * Math.PI / 4;
        L[i] += v * Math.cos(a) * 1.414;
        if (R !== L) R[i] += v * Math.sin(a) * 1.414;
        sp.idx += this.step;
      }
    }
    for (let i = 0; i < L.length; i++) {
      L[i] = Math.max(-1, Math.min(1, L[i]));
      if (R !== L) R[i] = Math.max(-1, Math.min(1, R[i]));
    }
    return true;
  }
}

registerProcessor('cv-capture', CaptureProcessor);
registerProcessor('cv-player', PlayerProcessor);
