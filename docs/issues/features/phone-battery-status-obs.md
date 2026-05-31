## Description

Add a phone battery checker/status display inside the OBS plugin so the phone battery level is generally visible while using GHXST Lens.

This is useful because the phone may be running as a camera for long periods, and users need a quick way to know if the device is charging, draining, or close to dying.

## Goal

Show the connected phone battery status somewhere visible in OBS or the GHXST Lens source/settings area.

## Suggested Display Info

- Battery percentage
- Charging status
- Optional low battery warning
- Optional temperature/health info later if available

## Expected Behavior

When the Android phone is connected and streaming, the OBS plugin should receive battery status updates from the phone and display them clearly.

## Possible Locations

- OBS plugin properties panel
- GHXST Lens source status area
- Optional small overlay/status indicator
- Debug/status text area

## Tasks

- [ ] Add battery status reporting in the Android app
- [ ] Send battery percentage to the receiver/plugin
- [ ] Send charging status to the receiver/plugin
- [ ] Display battery status in the OBS plugin
- [ ] Add warning state for low battery
- [ ] Handle missing/unknown battery status gracefully
- [ ] Update README/docs with battery status feature notes

## Labels

`feature-request`, `android`, `obs-plugin`, `ux`, `priority-medium`
