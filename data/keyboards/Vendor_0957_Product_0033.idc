# Copyright 2024 The Android Open Source Project
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

# Input Device Configuration file for Google Reference RCU Remote.
# PID 0033 is for new G20 with start button.

# Basic Parameters
keyboard.layout = Vendor_0957_Product_0031
# The reason why we set is follow https://docs.partner.android.com/tv/build/gtv/boot-resume
keyboard.doNotWakeByDefault = 1
audio.mic = 1
