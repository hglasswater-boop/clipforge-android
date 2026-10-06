# App icon

ClipForge uses one canonical launcher icon set. The icon should stay readable at small launcher sizes and avoid decorative detail that disappears under Android masking or monochrome theming.

## Visual contract

- Background: a single dark navy field (`@color/ic_launcher_background`).
- Foreground: two simple white rounded clip halves separated by a clear diagonal cut gap.
- The right clip half contains one play triangle as a transparent cutout, so the same geometry works in normal and monochrome launcher rendering.
- No text, gradients, shadows, scissors, film sprocket holes, outlines, or secondary decorative strokes.
- Android 13+ monochrome uses the exact same foreground vector; no duplicate monochrome artwork is maintained.
- Foreground geometry stays inside the adaptive-icon safe area so circle, squircle, and rounded-square masks remain legible.

## Resource contract

- Manifest references only `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.
- Adaptive icons use the single `@drawable/ic_launcher_foreground` vector for both foreground and monochrome layers.
- Because `minSdk` is 26, only adaptive launcher resources are retained; legacy bitmap fallback artwork is removed.
- Launcher resources do not use version suffixes such as `_v2`; obsolete launcher artwork is removed instead of retained as compatibility assets.

## Verification

`LauncherIconContractTest` locks the canonical manifest/resource references, requires foreground/monochrome to share one vector, verifies the play mark remains a transparent cutout, and rejects legacy launcher artwork. Android CI must pass unit tests and assemble the debug APK.
