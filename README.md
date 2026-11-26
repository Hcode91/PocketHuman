# PocketHuman

Make realistic adult human bodies on your phone and export them as rigged `.glb` files for games.

- **Body:** gender, age (25 to 90), muscle, weight, height, proportions, breast size and firmness
- **Ethnicity mix:** African / Asian / Caucasian base shapes, blended
- **Details:** 231 sliders across 21 areas (face, nose, mouth, eyes, ears, torso, hips, arms, legs, hands, feet…)
- **Face:** preview expressions (smile, surprised, angry, sad, blink) or any of the 34 MakeHuman face units and 52 ARKit face units
- **Looks:** 45 skins (auto-matched to ethnicity, gender and age if you like), eye colours, 12 eyebrows, 4 eyelash styles, 10 hairstyles
- **Clothes:** 20 items (casual, elegant, sports and work suits, shoes, hats); the body under them is hidden
- **Adult anatomy:** optional switch for genital shapes and skins (adults only)
- **Export:** one `.glb` with a Mixamo-named skeleton in an A-pose, feet on the ground, skin weights, eyes, brows, lashes, teeth, tongue, hair and clothes, textures and normal maps embedded, and all 86 face shapes as blend shapes (neutral face by default). The body under the clothes is a separate `body_under_outfit` mesh so outfits can be swapped in a game.
- Save and load characters as `.json`

Controls: one finger turns, pinch zooms, two fingers move up and down, double-tap jumps between face and full body.

## Assets and licences

PocketHuman's code is new and is not derived from MakeHuman's code. It uses the data from MakeHuman's Blender plugin, [MPFB2](https://github.com/makehumancommunity/mpfb2), and the MakeHuman community asset packs (`makehuman_system_assets`, `skins01`, `faceunits01`). All of that data is **CC0**: base mesh, morph targets, rig and weights, face units, eyes, eyebrows, eyelashes, teeth, tongue, hair, clothes and skins. The skins have no normal maps of their own, so the asset builder makes gentle detail normal maps from each skin texture. Anything you export is yours to use, including commercially, with no credit needed (credit is still nice).

The app only makes adults: MakeHuman's baby and child targets are not included, so ages run from 25 to 90.

## Rebuilding the assets

`app/src/main/assets/ph` is generated:

```
git clone https://github.com/makehumancommunity/mpfb2
# download and unzip makehuman_system_assets_cc0.zip, skins01_cc0.zip and faceunits01.zip from
# https://static.makehumancommunity.org/assets/assetpacks.html
python3 tools/build_assets.py mpfb2 makehuman_system_assets_cc0 skins01_cc0 app/src/main/assets/ph faceunits01
```

`tools/reference_model.py` is a Python version of the app's body maths, used to check the converted data.
