# Copyright (C) 2011 The Android Open Source Project
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

# Add include paths for native code.
FFW_PATH := $(call my-dir)

# Uncomment the requirements below, once we need them:

# STLport
include external/stlport/libstlport.mk

# Neven FaceDetect SDK
#LOCAL_C_INCLUDES += external/neven/FaceRecEm/common/src/b_FDSDK \
#	external/neven/FaceRecEm/common/src \
#	external/neven/Embedded/common/conf \
#	external/neven/Embedded/common/src \
#	external/neven/unix/src

# Finally, add this directory
LOCAL_C_INCLUDES += $(FFW_PATH)

