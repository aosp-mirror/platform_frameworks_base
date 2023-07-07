# Copyright 2023 The Android Open Source Project
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
# Brydge Touchpad
#

# Reports from this touchpad sometimes get bunched together due to Bluetooth
# batching, leading to bad timestamps that mess up finger velocity calculations.
# To fix this, set a fake delta using the touchpad's known report rate.
gestureProp.Fake_Timestamp_Delta = 0.010
