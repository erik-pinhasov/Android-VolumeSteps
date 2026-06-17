#!/usr/bin/env bash
#
# Installs the minimal Android build toolchain on a Debian/Ubuntu runner.
#
set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y \
  android-sdk-platform-23 \
  dalvik-exchange \
  aapt \
  zipalign \
  apksigner \
  default-jdk
