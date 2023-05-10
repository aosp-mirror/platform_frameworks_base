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
# Apple Magic Trackpad 2 (USB) configuration file
#
# WHEN MODIFYING, also change the Bluetooth file (Vendor_004c_Product_0265.idc)
#

gestureProp.Pressure_Calibration_Offset = 30
gestureProp.Palm_Pressure = 250.0
gestureProp.Palm_Width = 20.0
gestureProp.Multiple_Palm_Width = 20.0

# Enable Stationary Wiggle Filter
gestureProp.Stationary_Wiggle_Filter_Enabled = 1
gestureProp.Finger_Moving_Energy = 0.0008
gestureProp.Finger_Moving_Hysteresis = 0.0004

# Avoid accidental scroll/move on finger lift
gestureProp.Max_Stationary_Move_Speed = 47
gestureProp.Max_Stationary_Move_Speed_Hysteresis = 1
gestureProp.Max_Stationary_Move_Suppress_Distance = 0.2
