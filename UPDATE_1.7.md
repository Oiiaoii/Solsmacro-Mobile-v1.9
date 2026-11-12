# Sol Macro 1.7 (UI overhaul on top of 1.6)

## What changed
- Emoji icons are gone. The menu, cards and buttons use real vector icons (res/drawable/ic_*.xml, Material-style paths, tinted in code).
- The Panel check list no longer uses the check / cross / bullet characters, it draws icons too.
- The blue bar on top is gone: new dark theme (res/values/styles.xml, set in AndroidManifest.xml) and the status bar and navigation bar are painted the app's black.
- Cranked text fixed: the app clamps the phone's font size to 100% and all text is sized in dp (app screen, floating panel, position picker prompt), so it no longer grows or gets cut off.
- Buttons rebuilt: 48dp minimum height, icon + text, no clipped labels ("Turn on service" fits).
- Home screen: status pill with dot, 4 stat tiles (Catches, Failed, Sells, Run time), tidy capture/taps/checks/latency/bite/AI aim grid, button positions as a list with a clear "not set yet" warning, numbered How to use steps.
- Switch rows, focus-highlighted input boxes, card icons, boxed log and monitor, keyboard no longer covers fields (adjustResize).
- Screen refresh only rewrites text that changed (less flicker, log text stays selectable).
- Version 1.7 (versionCode 6).

## Files changed
app/build.gradle.kts, AndroidManifest.xml, res/values/styles.xml (new), res/drawable/ic_*.xml (new, 27 icons),
MainActivity.kt (rewritten UI), Panel.kt (dp text, check list icons, version), MacroService.kt (dp text in two overlay prompts).
Everything else is unchanged.

## Honest status
Not compiled or run on a phone here (no Android SDK in this sandbox). The GitHub build is the first compile. If it fails, open the build-reports artifact or send me the error line.

## Added after 1.7 first build
- Home: "Turn off service" button next to "Turn on service" (stops the macro, closes the panel, switches the accessibility service off). "Open Roblox" moved to its own full-width button below.
- Brry panel > Credits: tappable green text "Follow: chromeplated12301 for more update" that opens the Roblox profile link.
