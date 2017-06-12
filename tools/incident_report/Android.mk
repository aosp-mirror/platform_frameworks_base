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

LOCAL_MODULE := incident_report

LOCAL_C_INCLUDES := \
    external/protobuf/src

LOCAL_SRC_FILES := \
    generic_message.cpp \
    main.cpp \
    printer.cpp

LOCAL_SHARED_LIBRARIES := \
    libplatformprotos \
    libprotobuf-cpp-full

LOCAL_C_FLAGS := \
    -Wno-unused-parameter
include $(BUILD_HOST_EXECUTABLE)


