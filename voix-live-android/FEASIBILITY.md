# Voix Live — preuve de faisabilité faible latence

> Mise à jour v1.1 : ce verdict concerne uniquement l’objectif inférieur à
> 150 ms. Le prototype reprend désormais avec un flux continu volontairement
> retardé de 2 à 3 secondes, accepté pour l’essai utilisateur.

Date du test : 15 septembre 2026

## But mesuré

Supprimer une voix préalablement enregistrée dans le son ambiant, tout en
conservant les autres voix, avec une latence suffisamment faible pour une
écoute naturelle au casque.

Critères de passage vers Android :

- latence algorithmique maximale : 150 ms ;
- atténuation de la voix cible : au moins 10 dB ;
- variation de l'autre voix : entre -3 et +3 dB ;
- calcul plus rapide que le temps réel.

## Résultats

Deux modèles publics ont été testés sur le même mélange contrôlé de deux voix,
avec une référence distincte de la voix à supprimer.

### VoiceSplit / VoiceFilter quantifié ONNX

Le modèle atténue presque autant l'autre voix que la voix cible. À 160 ms :

- voix cible : -21,72 dB ;
- autre voix : -21,84 dB ;
- temps de calcul médian : 51,22 ms par fenêtre.

Il ne remplit donc pas le critère de sélectivité.

### OpenSpeakerBeam-SS

| Fenêtre | Calcul médian | Voix cible | Autre voix |
|---:|---:|---:|---:|
| 80 ms | 15,51 ms | -7,92 dB | -7,04 dB |
| 160 ms | 17,90 ms | -6,96 dB | -3,32 dB |
| 320 ms | 23,59 ms | -6,34 dB | -3,49 dB |
| 640 ms | 34,98 ms | -6,07 dB | -2,97 dB |
| 1 280 ms | 60,32 ms | -14,21 dB | -1,46 dB |
| 2 000 ms | 82,30 ms | -22,56 dB | -0,80 dB |

Le processeur du Mac exécute bien le modèle plus vite que le temps réel. Le
problème n'est donc pas la vitesse brute du calcul : la qualité de séparation
n'apparaît qu'avec environ 1,3 à 2 secondes de contexte audio.

## Décision

La preuve faible latence échoue. Porter ce modèle vers Android produirait une
application calculant localement, mais toujours en retard et par blocs. La
version Android n'est donc pas lancée à ce stade.

Pour reprendre le développement, il faut l'un des éléments suivants :

1. un modèle causal de suppression de locuteur, entraîné spécifiquement pour
   des blocs de 20 à 80 ms et publiquement utilisable ;
2. un accès au microphone brut de plusieurs micros du casque et un modèle
   spatial exploitant leur direction ;
3. un budget de recherche permettant d'entraîner ou d'adapter un tel modèle.

L'objectif produit reste pertinent, mais il ne peut pas être atteint avec une
simple optimisation de l'application actuelle ni avec les deux modèles testés.
