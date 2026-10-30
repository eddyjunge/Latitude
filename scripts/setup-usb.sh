#!/usr/bin/env bash
# One-time setup: lets adb talk to a Xiaomi phone over USB without root. Needs sudo once.
# Undo with: sudo rm /etc/udev/rules.d/51-android-latitude.rules
set -euo pipefail

RULE=/etc/udev/rules.d/51-android-latitude.rules

# 2717 = Xiaomi, 18d1 = Google (used by some phones in adb/fastboot mode)
sudo tee "$RULE" >/dev/null <<'EOF'
SUBSYSTEM=="usb", ATTR{idVendor}=="2717", MODE="0660", TAG+="uaccess"
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0660", TAG+="uaccess"
EOF
sudo udevadm control --reload-rules
sudo udevadm trigger

echo "Done. Unplug the phone and plug it back in."
