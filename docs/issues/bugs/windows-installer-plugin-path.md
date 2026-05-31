## Description

The `.exe` installer needs further tweaking to make sure it installs the plugin into the correct OBS plugin folder.

## Expected Behavior

The installer should detect the correct OBS Studio installation path and place the plugin files in the correct folder.

## Tasks

- [ ] Detect default OBS Studio install path
- [ ] Support custom OBS install paths
- [ ] Validate plugin folder after install
- [ ] Add clear error if OBS is not found
- [ ] Add manual folder browse option
- [ ] Test with OBS Studio 32.1.2
- [ ] Document manual install fallback
