#!/bin/bash

set -e

aapt package -F package.apk -M AndroidManifest.xml -S res
unzip -j package.apk resources.arsc res/layout/layout.xml
rm package.apk
