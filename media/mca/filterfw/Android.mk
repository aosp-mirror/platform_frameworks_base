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

#####################
# Build native sublibraries

include $(all-subdir-makefiles)

#####################
# Build main libfilterfw

include $(CLEAR_VARS)

LOCAL_MODULE := libfilterfw

LOCAL_MODULE_TAGS := optional

LOCAL_WHOLE_STATIC_LIBRARIES := libfilterfw_jni \
                                libfilterfw_native

LOCAL_SHARED_LIBRARIES := libstlport \
                          libGLESv2 \
                          libEGL \
                          libgui \
                          libdl \
                          libcutils \
                          libutils \
                          liblog \
                          libandroid \
                          libjnigraphics \
                          libmedia

# Don't prelink this library.  For more efficient code, you may want
# to add this library to the prelink map and set this to true. However,
# it's difficult to do this for applications that are not supplied as
# part of a system image.
LOCAL_PRELINK_MODULE := false

include $(BUILD_SHARED_LIBRARY)
