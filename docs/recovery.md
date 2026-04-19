# Barnabee remote install + break-glass recovery

This is the emergency recovery path when Barnabee has bricked the voice pipeline
on a ThinkSmart View and we can't physically touch the device.

## Primary: ADB-over-network

ThinkSmart Views in this household have ADB-over-network enabled. Known working
as of this session.

```bash
# From the WSL host (Man-Of-War):
adb connect <device-ip>:5555

# Verify:
adb devices
# Should show: <device-ip>:5555    device

# Install / update:
adb -s <device-ip>:5555 install -r -d /path/to/barnabee-release.apk

# Force-stop:
adb -s <device-ip>:5555 shell am force-stop com.msp1974.vacompanion.barnabee

# Relaunch:
adb -s <device-ip>:5555 shell monkey -p com.msp1974.vacompanion.barnabee \
    -c android.intent.category.LAUNCHER 1

# Uninstall (nuclear option, preserves stock VACA since different applicationId):
adb -s <device-ip>:5555 uninstall com.msp1974.vacompanion.barnabee

# Pull crash logs from the device's app-private storage:
adb -s <device-ip>:5555 shell run-as com.msp1974.vacompanion.barnabee \
    tar cf - files/crashes | tar xf - -C ./crash-pulls/
```

## Device inventory

Update this table as devices are added:

| Device name        | LAN IP            | MAC | Notes                |
|--------------------|-------------------|-----|----------------------|
| TODO: fill in      | TODO              | TODO| Canary device first  |

## Secondary: HAGHCPHelper-initiated install

HAGHCPHelper addon has `shell_command` access to the HA host. Once ADB is
installed in the addon container (P0-c-2 follow-up), HA can trigger install
from a push button.

```yaml
# configuration.yaml (to be added in vaca-barnabee-integration)
shell_command:
  barnabee_install_apk: >
    adb connect {{ device_ip }}:5555 &&
    adb -s {{ device_ip }}:5555 install -r -d /share/barnabee/latest.apk
  barnabee_force_restart: >
    adb -s {{ device_ip }}:5555 shell am force-stop com.msp1974.vacompanion.barnabee &&
    sleep 2 &&
    adb -s {{ device_ip }}:5555 shell monkey -p com.msp1974.vacompanion.barnabee
        -c android.intent.category.LAUNCHER 1
```

## Tertiary: Physical fallback

If ADB-over-network is unreachable (network partition, device won't boot to
Android):

1. Connect USB-C keyboard + mouse via OTG
2. Triple-tap Settings gear in status bar (or whatever the ThinkSmart gesture is)
3. Enable developer options → re-enable ADB debugging
4. Factory-reset as absolute last resort (loses all pairings)

## Rollback path (when a Barnabee build breaks audio)

Because we use a distinct `applicationId` (`com.msp1974.vacompanion.barnabee`),
**stock VACA stays installed**. Rollback is literally:

1. `adb ... uninstall com.msp1974.vacompanion.barnabee`
2. Launch stock VACA from the launcher
3. HA integration auto-reconnects because it talks over Wyoming, not Barnabee-specific

Rollback-to-previous-Barnabee is also possible once we retain last-10 APKs per
`P4.6-o rollback-spec`:

```bash
adb -s <ip>:5555 install -r -d ~/.barnabee/apk-retention/barnabee-<prev-version>-release.apk
```

## Known risks

- ADB-over-network resets across device reboot on some vendor skins. If that's
  true for ThinkSmart View, add an HA automation: on `device_tracker` going
  `home`, reassert `adb connect`.
- If Barnabee foreground service grabs the mic before dying, a forced restart
  of the Android audio system may be required. Command:
  `adb shell killall -9 audioserver` (requires root, which ThinkSmart View
  likely doesn't allow → we'd need to reboot the whole device).
- `adb tcpip 5555` requires USB once to enable. Assume already done; document
  "how to re-enable" in parent device setup docs.
