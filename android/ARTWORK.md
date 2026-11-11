# Launcher artwork

Version 0.1.3 uses the selected signal-bar train design: white background,
red-and-white cab, charcoal-gray lower strips on the three bars only, and no
track. The source is `../design/caltrain-signal-icon.svg`; the selected revision
is also saved as `../design/revisions/v14-charcoal-strip-no-track.svg`.
The launcher foreground is a native Android vector in
`app/src/main/res/drawable/ic_launcher_foreground.xml`, with a white adaptive
background. Its geometry maps the review circle to the launcher mask.

## Earlier artwork

Version 0.1.2 added a playful commuter train with a Wi-Fi antenna, generated with
the built-in image-generation tool. The original transparent PNG is retained in
`app/src/main/res/drawable-nodpi/train_wifi_art.png`. Android renders it over a
warm cream adaptive-icon background in that version. It is no longer the active
launcher foreground.

## Generation prompt

Use case: logo-brand. Create a polished, fun Android launcher app icon for a personal Caltrain Wi-Fi auto-connect helper. A charming chunky red-and-cream commuter train seen straight on, slightly rounded friendly toy-like silhouette, dark charcoal windshield with two friendly bright reflections, two small headlights, and a bold three-arc Wi-Fi symbol floating just above the train like an antenna. Premium playful flat illustration with very subtle dimensional shading, thick simple shapes, crisp edges, excellent legibility at 48 pixels. Caltrain-inspired red, warm ivory, charcoal. No text, letters, numbers, official logo, extra scenery, frame, border, or rounded-square container. Isolated foreground artwork on actual transparent background. Square canvas. Entire train and Wi-Fi symbol centered together and contained within the central 60% of the canvas width and height, generous even transparent margins for Android adaptive icon masking; the symbol must not approach the canvas edge. One single icon, no mockups or contact sheet.
