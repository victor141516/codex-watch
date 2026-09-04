# ESP32 firmware

ESP-IDF firmware for the Waveshare ESP32-S3-Touch-AMOLED-1.8. It provides the watch UI, BLE document protocol, continuous 16 kHz mono audio streaming, battery indicator, display sleep, persisted UI state, and PMU shutdown.

The local `components` directory contains the Waveshare board support package and Espressif CO5300 display driver with the QSPI/LVGL buffer fixes needed by this application. Upstream license files are included.

Build from the repository root with `./scripts/build-firmware.ps1`.
