#!/bin/bash

# Copyright (C) 2015 The Android Open Source Project
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
adb shell stop perfd
adb shell stop thermal-engine

# cpu possible frequencies
# 204000 229500 255000 280500 306000 331500 357000 382500 408000 433500 459000
# 484500 510000 535500 561000 586500 612000 637500 663000 688500 714000 739500
# 765000 790500 816000 841500 867000 892500 918000 943500 969000 994500 1020000
# 1122000 1224000 1326000 1428000 1530000 1632000 1734000 1836000 1938000
# 2014500 2091000 2193000 2295000 2397000 2499000

S=1326000
echo "set cpu $cpu to $S hz";
adb shell "echo userspace > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
adb shell "echo $S > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
adb shell "echo $S > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"
adb shell "echo $S > /sys/devices/system/cpu/cpu0/cpufreq/scaling_setspeed"

#disable hotplug
adb shell "echo 0 > /sys/devices/system/cpu/cpuquiet/tegra_cpuquiet/enable"

# gbus possible rates
# 72000 108000 180000 252000 324000 396000 468000 540000 612000 648000
# 684000 708000 756000 804000 852000 (kHz)

S=324000000
echo "set gpu to $S hz"
adb shell "echo 1 > /d/clock/override.gbus/state"
adb shell "echo $S > /d/clock/override.gbus/rate"
