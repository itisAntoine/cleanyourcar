# XYAuto YT3518C (`mt3561s` / `evb3561sv_w_65_m0`)

- **Chipset / SoC:** MediaTek / AutoChips `mt3561s` (`arm64-v8a`)
- **Firmware:** `YT3518C_00008_V008`
- **OS:** Android 6.0 (API 23, displayed as `"8.0"` in `ro.build.version.release`)
- **Display:** `1024×600` @ `160 dpi` (`mdpi`)
- **RAM:** 2 GB

## Quick Start Over Wi-Fi ADB

```bash
# 1. Disable bloatware (frees ~550 MB RAM while keeping Reverse Camera, Bluetooth, USB Music, Dashcam, Equalizer, and Google Maps)
CAR_HOST=192.168.100.234 ./tools/disable.sh examples/yt3518c-mt3561s/debloat.txt

# 2. Build and install CleanYourCar (:app) + Navigation Bridge (:navbridge)
CAR_HOST=192.168.100.234 ./tools/build-and-install.sh

# 3. Restore everything if ever needed
CAR_HOST=192.168.100.234 ./tools/restore.sh examples/yt3518c-mt3561s/debloat.txt
```
