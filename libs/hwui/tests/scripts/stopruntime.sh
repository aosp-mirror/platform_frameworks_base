#!/bin/bash

# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

adb root
adb wait-for-device
adb shell stop

for pid in $( adb shell ps | awk '{ if ( $9 == "surfaceflinger" ) { print $2 } }' ); do
    adb shell kill $pid
done
adb shell setprop debug.egl.traceGpuCompletion 1
adb shell setprop debug.sf.nobootanimation 1
# Daemonize command is available only in eng builds.
adb shell daemonize surfaceflinger
