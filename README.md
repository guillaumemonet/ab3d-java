# Alien Breed 3D — portage Java

Portage en Java du moteur d'**Alien Breed 3D** (Team17, 1995), transcrit
ligne à ligne depuis l'assembleur 68000 d'origine.

![Le niveau 4](docs/images/level_d.png)

Le rendu est un rasteriseur logiciel qui reproduit l'arithmétique du 68000 :
mêmes divisions entières, mêmes débordements, même ordre de dessin.
jMonkeyEngine n'ouvre que la fenêtre, lit le clavier et affiche une texture ;
il ne dessine rien lui-même. Les données du jeu d'origine sont lues telles
quelles à l'exécution — aucune conversion hors ligne.

| | |
|---|---|
| ![Le menu](docs/images/menu.png) | ![Le niveau 1](docs/images/level_a.png) |

## Ce qui tourne

Écran-titre en fondu, menu avec mot de passe et reconfiguration des touches,
rendu logiciel complet (murs, sols, plafonds, sprites, arme en main, fond de
ciel, eau), bandeau et jauges, portes, ascenseurs, interrupteurs, clés et
conditions, ramassages, cinq types d'ennemis qui rôdent, repèrent, poursuivent,
mordent et tirent, six armes dont trois à projectiles, explosions avec souffle
et éclats, barils, et le son.

## Faire tourner

Il faut le jeu d'origine : les sources assembleur et le répertoire `includes/`
d'un côté, les disquettes extraites de l'autre. **Rien de tout cela n'est dans
ce dépôt** — il ne contient que du code Java.

```
gradle run -Dab3d.root=../ab3d-rtg -Dab3d.disk=../adf-extract
```

`ab3d.root` désigne l'arborescence des sources (le portage y lit `source/jg.s`,
`source/anims`, `includes/…`), `ab3d.disk` les fichiers extraits des disquettes
(niveaux, sons).

### Commandes

Ce sont celles de `CONTROLBUFFER`, pas un schéma moderne, et elles sont
reconfigurables depuis **CONTROL OPTIONS**.

| Touche | Action |
|---|---|
| Flèches ← → | tourner |
| Flèches ↑ ↓ | avancer, reculer |
| `.` `/` | pas de côté |
| Maj droite | courir |
| Alt droit | tirer |
| Espace | portes, ascenseurs, interrupteurs |
| `D` | s'accroupir |
| `L` | regarder derrière |
| `1`–`5` | fusil à impulsion, à pompe, plasma, grenades, roquettes |

Pour changer de niveau, **PASSWORD** dans le menu :

| Niveau | Mot de passe |
|---|---|
| 1 — The Gate | `KLLKFFFFFFFFFFFF` |
| 2 — Storage Bay | `KOLKFNFFFFFFFFFF` |
| 3 — Sewer Network | `OKLKFHFFFFFFFFFF` |
| 4 — The Courtyard | `KPLKFPFFFFFFFFFF` |

![Le niveau 3](docs/images/level_c.png)

## Le build visé

Le main est **`source/jg.s`**, pas `master.s`. La distinction a compté : sur
les 778 routines que les deux fichiers partagent, 183 diffèrent, et une
quarantaine touchaient au portage — décalages de tuiles, tests de bits contre
comparaisons, sol de Gouraud présent dans l'un et absent de l'autre. Tout ce qui
est transcrit vient de l'arbre d'inclusion de `jg.s`.

## Comment le code est écrit

Chaque routine porte le nom de son original et cite les instructions qui
décident quelque chose. `M68k` fournit les primitives — `divs` qui laisse sa
destination inchangée en cas de débordement, `muls`, `swap`, l'extension de
signe — parce que reproduire ces cas-là est souvent la différence entre une
image juste et une image plausible.

Les tables ne sont pas recopiées : elles sont **lues dans l'assembleur à
l'exécution**. Les animations d'armes, la table des collisions, les
enregistrements d'armes, les écrans de menu, les noms des échantillons, les
touches par défaut — tout est parsé depuis `source/`. Deux entrées de la table
des sons sont commentées ; une recopie à la main aurait décalé d'un cran tout
ce qui suit la dixième.

## Les contrôles de conformité

`src/main/java/ab3d/tools/` contient une cinquantaine de programmes qui vérifient
le portage contre la source, sans ouvrir de fenêtre :

```
gradle tool -Ptool=EnemyCheck
gradle tool -Ptool=PasswordCheck
```

Ils cherchent des accords entre deux endroits écrits séparément. Les douze
touches par défaut existent deux fois dans l'original — comme octets dans
`CONTROLBUFFER` et comme libellés imprimés sur l'écran des contrôles — et les
douze concordent. Les huit listes d'animation d'armes font chacune exactement un
de plus que le compte annoncé ailleurs dans la table. Vingt-sept des vingt-huit
échantillons ont la longueur que la table leur donne.

Le même travail a servi à savoir quoi **ne pas** porter : sur les douze types
d'ennemis, six ne sont posés dans aucun niveau ; il n'y a aucun mur transparent
dans les quatre niveaux ; le visage animé du bandeau a ses données commentées et
sa routine jamais appelée.

## Les bizarreries de l'original, gardées et signalées

Le code note ce qu'il trouve plutôt que de le lisser.

- `CALCPASSWORD` teste quatre armes avec quatre `sne d0` de suite, et `sne`
  écrit l'octet entier : chaque test efface le précédent. Un mot de passe donné
  par le jeu ne peut jamais restituer plus que la dernière arme, alors que
  `GETSTATS` en relit quatre.
- `ComputeBlast` écrit `add.w 32,d3` sans `#` : lu à la lettre, c'est le mot à
  l'adresse absolue 32, et l'atténuation du souffle disparaîtrait.
- Le bloc qui calcule la hauteur au franchissement d'une ligne soustrait `a4`,
  qui est le pointeur de la zone de destination depuis le haut de la boucle.

## Ce qui manque

Le mode deux joueurs, la musique, la sortie de niveau (les statistiques et le
mot de passe de fin sont transcrits mais rien ne les déclenche), et les six
types d'ennemis que les niveaux ne posent jamais.

Deux écarts assumés subsistent dans le moteur, tous deux commentés à l'endroit
où ils sont : un refus de déplacement qui n'existe pas dans l'original, parce que
le glissement transcrit laisse un résidu que le sien n'a pas ; et la teinte de
l'eau, dont la table n'a jamais été convertie pour cette version du moteur.

## Licence

Le code de ce dépôt est publié tel quel. **Les données du jeu ne s'y trouvent
pas** et restent la propriété de leurs ayants droit — il faut posséder une copie
d'Alien Breed 3D pour faire tourner ce portage.
