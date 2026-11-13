# Citizen Voix

Chat vocal de proximité du serveur Citizen, **sans Simple Voice Chat** et sans aucune dépendance.

NeoForge 21.1.215 · Minecraft 1.21.1 · Java 21

## Qui utilise quoi
| Plateforme | Comment |
|---|---|
| **PC Java** | le mod `citizenvoice.jar` dans le dossier `mods` du joueur — aucune page web |
| **Xbox, PlayStation, Switch, téléphone, tablette** (via Citizen Bedrock) | page web envoyée automatiquement dans le chat |
| **Serveur** | le même `citizenvoice.jar` dans `mods/` : il relie tout le monde |

Tout le monde s'entend : PC ↔ PC, PC ↔ console, console ↔ console.
Distances : **20 blocs** en voix normale, **3 blocs** en chuchotant (`distance` et `distance_chuchotement`
dans `config/citizenvoice/citizenvoice.properties` côté serveur).

## Joueur PC
| Touche (modifiable dans Options → Contrôles) | Action |
|---|---|
| **V** | menu des réglages : micro, sortie son, mode, sensibilité, amplification, volume, test du micro |
| **Verr. Maj** | appuyer pour parler (si ce mode est choisi) |
| **B** (maintenir) | chuchoter (3 blocs) |
| **M** | couper / réactiver le micro |

En haut à gauche de l'écran : « Tu parles », « Micro coupé » et les noms des joueurs que tu entends.
Réglages enregistrés dans `config/citizenvoice-client.properties`.

## Joueur console / téléphone
- 10 s après la connexion : adresse + code à 6 chiffres dans le chat (`/voix` pour un nouveau code).
- Sur la page : micro auto ou appuyer pour parler, chuchoter, couper le micro, sourdine, volume,
  liste des joueurs à portée avec qui parle.

## Commandes admin (niveau 2)
`/voix statut` · `/voix code <joueurs>` · `/voix deconnecter <joueur>` · `/voix recharger`

## Serveur
- Port du site : **30053** (`port=` dans la config). HTTPS obligatoire pour le micro des navigateurs :
  certificat auto-signé généré automatiquement (avertissement à la 1re visite → « Paramètres avancés » → « Continuer »),
  ou vrai certificat `.p12` via `certificat=` / `certificat_mot_de_passe=`.
- Détection des joueurs console : nécessite Citizen Bedrock sur le serveur.
- Bande passante : ~16 Ko/s par joueur qui parle (µ-law 16 kHz) vers chaque joueur PC à portée.

## Limites connues
- iPhone : la page perd le micro quand on passe dans Minecraft sur le même téléphone → 2e appareil conseillé.
- Edge sur Xbox : micro pas garanti → utiliser un téléphone à côté de la console.
- Pas d'annulation d'écho : casque conseillé.
