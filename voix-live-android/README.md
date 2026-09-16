# Voix Live Android v1.1

Application Android locale de suppression de voix cible.

- Enregistrement d’un profil vocal de 10 secondes.
- Sélection automatique des 4 secondes les plus propres.
- Inférence locale ONNX Runtime, sans envoi audio sur Internet.
- Fenêtres locales `iter_model` de 2 secondes, décalées d’une seconde.
- Alignement temporel et gain de soustraction identiques au prototype enregistré validé.
- Lecture dans un thread séparé avec deux segments préchargés : le flux reste continu
  avec un retard volontaire d’environ 2 à 3 secondes.
- Sortie réservée aux écouteurs pour éviter le larsen.
- Atténuation réglable de 50 à 120 %.

Le modèle est exporté pendant le workflow GitHub Actions depuis le Space
`swc2/Target-speaker-extraction` (`iter_model`, checkpoint
`3_loss_post.pt.tar`) puis intégré à l’APK.

Pour tester, il faut désactiver le mode Transparence/son ambiant des écouteurs.
Sinon les écouteurs rejouent eux-mêmes le son brut en parallèle et contournent
le filtre. Le microphone utilisé est celui du téléphone.

Limite : le flux électronique est filtré avec environ 2 à 3 secondes de délai.
La qualité dépend toujours de l’estimation produite par `iter_model`.
