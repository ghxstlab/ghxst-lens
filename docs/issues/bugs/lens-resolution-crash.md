## Description

Certain crashes are observed when selecting an incompatible camera lens and resolution combination.

## Expected Behavior

Unsupported lens/resolution/FPS combinations should be hidden, disabled, or show a clear warning.

## Tasks

- [ ] Detect supported resolutions per camera/lens
- [ ] Detect supported FPS options per camera/lens
- [ ] Prevent unsupported combinations from being selected
- [ ] Add safe fallback mode
- [ ] Add crash handling around camera session startup
- [ ] Test main, wide, ultra-wide, and telephoto lenses
