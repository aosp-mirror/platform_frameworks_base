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
# Sony Playstation(R) DualSense 5 Controller
#

## Touchpad ##

# Because of the way this touchpad is positioned, touches around the edges are
# no more likely to be palms than ones in the middle, so remove the edge zones
# from the palm classifier to increase the usable area of the pad.
gestureProp.Palm_Edge_Zone_Width = 0
gestureProp.Tap_Exclusion_Border_Width = 0

gestureProp.Point_X_Out_Scale = 2.0
gestureProp.Point_Y_Out_Scale = 2.0

# TODO(b/351326684): Ideally "Scroll X Out Scale" and "Scroll Y Out Scale"
# should be adjusted as well. Currently not supported in IDC files.
