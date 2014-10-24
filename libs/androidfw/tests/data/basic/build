#!/bin/bash
#
# Copyright (C) 2014 The Android Open Source Project
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
#

PATH_TO_FRAMEWORK_RES=$(gettop)/prebuilts/sdk/current/android.jar

aapt package -M AndroidManifest.xml -S res -I $PATH_TO_FRAMEWORK_RES --split hdpi --split xhdpi --split xxhdpi --split fr,de -F bundle.apk -f && \
unzip bundle.apk resources.arsc && \
mv resources.arsc basic.arsc && \
xxd -i basic.arsc > basic_arsc.h && \
\
unzip bundle_de_fr.apk resources.arsc && \
mv resources.arsc split_de_fr.arsc && \
xxd -i split_de_fr.arsc > split_de_fr_arsc.h && \
\
unzip bundle_hdpi-v4.apk resources.arsc && \
mv resources.arsc split_hdpi_v4.arsc && \
xxd -i split_hdpi_v4.arsc > split_hdpi_v4_arsc.h && \
\
unzip bundle_xhdpi-v4.apk resources.arsc && \
mv resources.arsc split_xhdpi_v4.arsc && \
xxd -i split_xhdpi_v4.arsc > split_xhdpi_v4_arsc.h && \
\
unzip bundle_xxhdpi-v4.apk resources.arsc && \
mv resources.arsc split_xxhdpi_v4.arsc && \
xxd -i split_xxhdpi_v4.arsc > split_xxhdpi_v4_arsc.h \
