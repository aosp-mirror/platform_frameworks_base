#
# Copyright (C) 2015 The Android Open Source Project
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
LOCAL_PATH:= $(call my-dir)

# ==========================================================
# Build the host executable: protoc-gen-javastream
# ==========================================================
include $(CLEAR_VARS)

LOCAL_MODULE := bit

LOCAL_MODULE_HOST_OS := linux darwin

LOCAL_SRC_FILES := \
    aapt.cpp \
    adb.cpp \
    command.cpp \
    main.cpp \
    make.cpp \
    print.cpp \
    util.cpp

LOCAL_STATIC_LIBRARIES := \
    libexpat \
    libinstrumentation \
    libjsoncpp

LOCAL_SHARED_LIBRARIES := \
    libprotobuf-cpp-full

include $(BUILD_HOST_EXECUTABLE)
