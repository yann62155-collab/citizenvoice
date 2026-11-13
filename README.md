# Citizen Voix

Chat vocal de proximité **dans le navigateur** pour les joueurs qui n'ont pas Simple Voice Chat
(Xbox, PlayStation, Switch, téléphone, tablette), relié à **Simple Voice Chat** pour les joueurs PC.

NeoForge 21.1.215 · Minecraft 1.21.1 · Java 21 · **mod uniquement serveur** (ne pas l'envoyer aux joueurs).

## Qui entend qui

| Celui qui parle ↓ / celui qui écoute → | Joueur PC (Simple Voice Chat) | Joueur navigateur |
|---|---|---|
| Joueur PC (Simple Voice Chat) | comme avant (SVC) | ✅ voix décodée, atténuée par la distance, gauche/droite |
| Joueur navigateur | ✅ voix jouée en 3D sur son personnage | ✅ |

La voix d'un joueur navigateur sort **de son personnage en jeu** : les joueurs PC l'entendent en 3D,
avec l'icône de parole, exactement comme un autre joueur PC. Les groupes privés de Simple Voice Chat restent privés.

## Installation (serveur)
1. Mettre `citizenvoice.jar` dans `mods/` du serveur (à côté de `voicechat`).
2. Ouvrir/réserver un port chez BoxToPlay (par défaut **30053**) et le mettre dans
   `config/citizenvoice/citizenvoice.properties` si différent.
3. Redémarrer. La console affiche `Chat vocal web en ligne : https://mc312.boxtoplay.com:30053`.

## Côté joueur
- **Joueurs Java PC : aucune page web.** Ils utilisent Simple Voice Chat comme d'habitude ; `/voix` leur rappelle juste ça.
- **Joueurs console / téléphone** (détectés par Citizen Bedrock) : 10 s après la connexion, ils reçoivent dans le chat
  l'adresse + un code à 6 chiffres. `/voix` leur donne un nouveau code à tout moment.
- Sur la page : entrer le code → autoriser le micro. Le navigateur s'en souvient 24 h (bouton « Reprendre »).
- Modes « Voix auto » (avec réglage de sensibilité) ou « Appuyer pour parler », micro coupé, sourdine, volume,
  liste des joueurs à portée avec indicateur de parole.

## Distances
Voix normale : **20 blocs** · chuchotement : **3 blocs** (`distance` et `distance_chuchotement` dans la config).
Entre deux joueurs PC, c'est la config de Simple Voice Chat qui compte : dans `config/voicechat/voicechat-server.properties`,
mettre `max_voice_distance=20` et `whisper_distance=3`.

## Commandes admin (niveau 2)
`/voix statut` · `/voix code <joueurs>` · `/voix deconnecter <joueur>` · `/voix recharger`

## HTTPS et certificat
Les navigateurs n'autorisent le micro qu'en **https**. Par défaut le mod génère un certificat auto-signé :
à la première visite, le navigateur affiche un avertissement → « Paramètres avancés » → « Continuer ».
Pour supprimer l'avertissement : mettre un vrai certificat `.p12` dans `config/citizenvoice/` et renseigner
`certificat=` et `certificat_mot_de_passe=`. Une adresse tapée en `http://` est redirigée automatiquement vers `https://`.

## Limites connues
- Téléphone qui sert aussi à jouer : l'iPhone coupe le micro de la page quand on passe dans Minecraft.
  Utiliser un **deuxième appareil** (ou l'écran partagé sur Android).
- Navigateur Edge de la Xbox : l'accès au micro n'y est pas garanti → utiliser un téléphone à côté de la console.
- Sans casque, l'écho est possible.
