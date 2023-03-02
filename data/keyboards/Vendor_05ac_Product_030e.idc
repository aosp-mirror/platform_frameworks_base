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
# Apple Magic Trackpad
#

gestureProp.Touchpad_Stack_Version = 1
# We are using raw touch major value as pressure value, so set the Palm
# pressure threshold high.
gestureProp.Palm_Pressure = 1000
gestureProp.Compute_Surface_Area_from_Pressure = 0
gestureProp.IIR_b0 = 1
gestureProp.IIR_b1 = 0
gestureProp.IIR_b2 = 0
gestureProp.IIR_b3 = 0
gestureProp.IIR_a1 = 0
gestureProp.IIR_a2 = 0
# NOTE: bias on X-axis is uncalibrated
gestureProp.Touchpad_Device_Output_Bias_on_X-Axis = -283.3226025266607
gestureProp.Touchpad_Device_Output_Bias_on_Y-Axis = -283.3226025266607
gestureProp.Max_Allowed_Pressure_Change_Per_Sec = 100000.0
gestureProp.Max_Hysteresis_Pressure_Per_Sec = 100000.0
# Drumroll suppression causes janky movement on this touchpad.
gestureProp.Drumroll_Suppression_Enable = 0
gestureProp.Two_Finger_Vertical_Close_Distance_Thresh = 35.0
gestureProp.Fling_Buffer_Suppress_Zero_Length_Scrolls = 0
