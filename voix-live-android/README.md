# Voix Live Android

Application Android locale de suppression de voix cible.

- Enregistrement d’un profil vocal de 10 secondes.
- Sélection automatique des 4 secondes les plus propres.
- Inférence locale ONNX Runtime, sans envoi audio sur Internet.
- Traitement continu par blocs de 2 secondes dans un service micro au premier plan.
- Sortie réservée aux écouteurs pour éviter le larsen.
- Atténuation réglable de 50 à 120 %.

Le modèle est exporté pendant le workflow GitHub Actions depuis le Space
`swc2/Target-speaker-extraction` (`iter_model`, checkpoint
`3_loss_post.pt.tar`) puis intégré à l’APK.

Limite physique : le flux électronique est filtré avec environ 3 secondes de
délai. L’application ne peut pas supprimer la conduction osseuse naturelle de
la propre voix de l’utilisateur.
