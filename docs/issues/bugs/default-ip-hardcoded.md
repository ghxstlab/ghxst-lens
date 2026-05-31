## Description

The receiver/plugin currently defaults to `192.168.99.101`.

This should be changed so first-time setup starts with a blank IP field or a guided prompt.

## Expected Behavior

The user should be prompted to enter the IP address shown on the Android phone app.

## Tasks

- [ ] Remove hardcoded default IP
- [ ] Start with blank IP on first launch
- [ ] Add helper text explaining where to find the phone IP
- [ ] Validate IP address format
- [ ] Save last-used successful IP
- [ ] Show clear error if no IP is entered
