# PocketHuman

Make realistic adult human bodies on your phone and export them as rigged `.glb` files for games.

- **Body:** gender, age (25 to 90), muscle, weight, height, proportions, breast size and firmness
- **Ethnicity mix:** African / Asian / Caucasian base shapes, blended
- **Details:** 231 sliders across 21 areas (face, nose, mouth, eyes, ears, torso, hips, arms, legs, hands, feet…)
- **Looks:** 43 skins, eye colours, 12 eyebrows, 4 eyelash styles, 10 hairstyles
- **Export:** one `.glb` with a Mixamo-named skeleton in an A-pose, skin weights, eyes, brows, lashes, teeth, tongue and hair, textures embedded
- Save and load characters as `.json`

Controls: one finger turns, pinch zooms, two fingers move up and down, double-tap jumps between face and full body.

## Assets and licences

PocketHuman's code is new and is not derived from MakeHuman's code. It uses the data from MakeHuman's Blender plugin, [MPFB2](https://github.com/makehumancommunity/mpfb2), and the MakeHuman community asset packs (`makehuman_system_assets`, `skins01`). All of that data is **CC0**: base mesh, morph targets, rig and weights, eyes, eyebrows, eyelashes, teeth, tongue, hair and skins. Anything you export is yours to use, including commercially, with no credit needed (credit is still nice).

The app leaves out MakeHuman's baby and child targets (it only makes adults), the genital targets and skins, and facial expressions.

## Rebuilding the assets

`app/src/main/assets/ph` is generated:

```
git clone https://github.com/makehumancommunity/mpfb2
# download and unzip makehuman_system_assets_cc0.zip and skins01_cc0.zip from
# https://static.makehumancommunity.org/assets/assetpacks/index.html
python3 tools/build_assets.py mpfb2 makehuman_system_assets_cc0 skins01_cc0 app/src/main/assets/ph
```

`tools/reference_model.py` is a Python version of the app's body maths, used to check the converted data.
