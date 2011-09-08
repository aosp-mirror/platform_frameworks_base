#
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

LOCAL_PATH  := frameworks/base/data/videos
TARGET_PATH := system/media/video

PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/AndroidInSpace.240p.mp4:$(TARGET_PATH)/AndroidInSpace.240p.mp4 \
	$(LOCAL_PATH)/Sunset.240p.mp4:$(TARGET_PATH)/Sunset.240p.mp4

