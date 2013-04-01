# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Warning: this is actually a product definition, to be inherited from

PRODUCT_COPY_FILES := \
    frameworks/base/data/fonts/system_fonts.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/system_fonts.xml \
    frameworks/base/data/fonts/fallback_fonts.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/fallback_fonts.xml

PRODUCT_PACKAGES := \
    DroidSansFallback.ttf \
    Roboto-Regular.ttf \
    Roboto-Bold.ttf \
    Roboto-Italic.ttf \
    Roboto-BoldItalic.ttf \
    Roboto-Light.ttf \
    Roboto-LightItalic.ttf \
    Roboto-Thin.ttf \
    Roboto-ThinItalic.ttf \
    RobotoCondensed-Regular.ttf \
    RobotoCondensed-Bold.ttf \
    RobotoCondensed-Italic.ttf \
    RobotoCondensed-BoldItalic.ttf \
    DroidNaskh-Regular.ttf \
    DroidNaskhUI-Regular.ttf \
    DroidSansDevanagari-Regular.ttf \
    DroidSansHebrew-Regular.ttf \
    DroidSansHebrew-Bold.ttf \
    DroidSansThai.ttf \
    DroidSerif-Regular.ttf \
    DroidSerif-Bold.ttf \
    DroidSerif-Italic.ttf \
    DroidSerif-BoldItalic.ttf \
    DroidSansMono.ttf \
    DroidSansArmenian.ttf \
    DroidSansGeorgian.ttf \
    AndroidEmoji.ttf \
    Clockopia.ttf \
    AndroidClock.ttf \
    AndroidClock_Highlight.ttf \
    AndroidClock_Solid.ttf \
