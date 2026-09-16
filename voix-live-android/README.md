# Voix Live Android v1.2

Application Android locale de suppression de voix cible.

- Enregistrement et conservation des 10 secondes complètes du profil vocal.
- Inférence locale ONNX Runtime, sans envoi audio sur Internet.
- Fenêtres locales `iter_model` de 4 secondes, sans chevauchement afin que
  chaque échantillon ne soit calculé qu’une seule fois.
- Alignement temporel et gain de soustraction identiques au prototype enregistré validé.
- Lecture dans un thread séparé avec deux segments préchargés : le flux reste
  continu avec un retard volontaire d’environ 8 à 12 secondes.
- Sortie réservée aux écouteurs pour éviter le larsen.
- Atténuation réglable de 50 à 120 %.

Le modèle est exporté pendant le workflow GitHub Actions depuis le Space
`swc2/Target-speaker-extraction` (`iter_model`, checkpoint
`3_loss_post.pt.tar`) puis intégré à l’APK.

Pour tester, il faut désactiver le mode Transparence/son ambiant des écouteurs.
Sinon les écouteurs rejouent eux-mêmes le son brut en parallèle et contournent
le filtre. Le microphone utilisé est celui du téléphone.

Limite : le flux électronique est filtré avec environ 8 à 12 secondes de délai.
La qualité dépend toujours de l’estimation produite par `iter_model`.
