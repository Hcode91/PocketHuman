# PocketHuman

Make realistic human characters on your phone and export them as rigged `.glb` files for games.

- **Body:** gender, age (1 to 90), muscle, weight, height, proportions, breast size and firmness
- **Ethnicity mix:** African / Asian / Caucasian base shapes, blended
- **Details:** 231 sliders across 21 areas (face, nose, mouth, eyes, ears, torso, hips, arms, legs, hands, feet…)
- **Face:** preview expressions (smile, surprised, angry, sad, blink) or any of the 34 MakeHuman face units and 52 ARKit face units
- **Looks:** 45 skins (auto-matched to ethnicity, gender and age if you like), eye colours, 12 eyebrows, 4 eyelash styles, 10 hairstyles
- **Clothes:** 20 items (casual, elegant, sports and work suits, shoes, hats); the body under them is hidden
- **Adult anatomy:** optional switch for genital shapes and skins (adults only)
- **Export:** one `.glb` with a Mixamo-named skeleton in an A-pose, feet on the ground, skin weights, eyes, brows, lashes, teeth, tongue, hair and clothes, textures and normal maps embedded, and all 86 face shapes as blend shapes (neutral face by default). For adults, the body under the clothes is a separate `body_under_outfit` mesh so outfits can be swapped in a game.
- **Ethnicity presets:** one-tap starting points (East, Southeast and South Asian, Middle Eastern, North, West and East African, Latin American, Indigenous American, Pacific Islander, Northern and Southern European, Mixed) built from the three base shapes plus gentle face adjustments and a matching skin
- **Packs:** browse and download the MakeHuman community asset packs in the app (clothes, hair, beards, glasses, hats, skins, extra shape sliders, lip-sync visemes…), or import a pack `.zip` from your phone. CC-BY assets are credited on the Save tab and inside the exported `.glb`
- **Import outfits** from Meshy or other AI tools (`.glb`, or `.obj` + texture): the app lines the outfit up on the body, strips the model's own skin, keeps it outside the body and hides the body underneath. An editor fixes size, position, orientation, arm and leg angles, looseness and clipping. Saved outfits follow every body slider and can be edited again later
- Save and load characters as `.json`

Controls: one finger turns, pinch zooms, two fingers move up and down, double-tap jumps between face and full body.

## Assets and licences

PocketHuman's code is new and is not derived from MakeHuman's code. It uses the data from MakeHuman's Blender plugin, [MPFB2](https://github.com/makehumancommunity/mpfb2), and the MakeHuman community asset packs (`makehuman_system_assets`, `skins01`, `faceunits01`). All of that data is **CC0**: base mesh, morph targets, rig and weights, face units, eyes, eyebrows, eyelashes, teeth, tongue, hair, clothes and skins. The skins have no normal maps of their own, so the asset builder makes gentle detail normal maps from each skin texture. Anything you export is yours to use, including commercially, with no credit needed (credit is still nice).

The Age slider follows MakeHuman’s 1–90 range, using its baby, child, young-adult, and older-adult body morphs. Characters under 18 wear a fitted base outfit in the preview and GLB export, and use the built-in non-adult skins. Adult anatomy settings become available at age 18. Existing character saves keep their original ages.

## Rebuilding the assets

`app/src/main/assets/ph` is generated:

```
git clone https://github.com/makehumancommunity/mpfb2
# download and unzip makehuman_system_assets_cc0.zip, skins01_cc0.zip and faceunits01.zip from
# https://static.makehumancommunity.org/assets/assetpacks.html
python3 tools/build_assets.py mpfb2 makehuman_system_assets_cc0 skins01_cc0 app/src/main/assets/ph faceunits01
```

To rebuild only the baby and child morphs from MPFB2, run `python3 tools/build_age_targets.py mpfb2 app/src/main/assets/ph`.

`tools/reference_model.py` is a Python version of the app's body maths, used to check the converted data.
