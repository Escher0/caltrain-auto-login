# Desktop tray icon

Derived from the selected revision 14 source in `../caltrain-signal-icon.svg`.
The train geometry, red/white livery and three charcoal lower strips are preserved.
The white circle is removed, and a tighter square viewBox makes the train nearly
fill the width without changing its proportions. The cab still has no bottom strip.

- `caltrain-tray.svg`: transparent vector source.
- `caltrain-tray.png`: 128px raster for the tray.
- `comparison.svg` / `comparison.png`: current and proposed designs, including
  native 16, 20, 24 and 32px samples on light and dark backgrounds.

Render these with librsvg. CairoSVG dropped the clipped charcoal strips on the
first two bars in the initial preview. The corrected PNGs use librsvg and retain
all three strips. `../../desktop-tauri/icons/tray.png` is the runtime copy. The
launcher and Android icons remain separate assets.

A vertically stretched variant was previewed and rejected by the user. The tray
uses the proportional version; its wide silhouette naturally leaves space above
and below within the square system tray slot.
