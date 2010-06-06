#
# Copyright (C) 2010 The Android Open Source Project
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

ifneq ($(TARGET_SIMULATOR),true)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                                       \
                  mtptest.cpp                           \
                  MtpDatabase.cpp                       \
                  MtpDataPacket.cpp                     \
                  MtpDebug.cpp                          \
                  MtpMediaScanner.cpp                   \
                  MtpPacket.cpp                         \
                  MtpRequestPacket.cpp                  \
                  MtpResponsePacket.cpp                 \
                  MtpServer.cpp                         \
                  MtpStringBuffer.cpp                   \
                  MtpStorage.cpp                        \
                  MtpUtils.cpp                          \
                  SqliteDatabase.cpp                    \
                  SqliteStatement.cpp                   \

LOCAL_MODULE:= mtptest

LOCAL_C_INCLUDES := external/sqlite/dist

LOCAL_CFLAGS := -DMTP_DEVICE

LOCAL_SHARED_LIBRARIES := libutils libsqlite libstagefright libcutils \
	libmedia

include $(BUILD_EXECUTABLE)

endif

include $(CLEAR_VARS)

LOCAL_MODULE := libmtphost

LOCAL_SRC_FILES:=                                       \
                  MtpClient.cpp                         \
                  MtpCursor.cpp                         \
                  MtpDataPacket.cpp                     \
                  MtpDebug.cpp                          \
                  MtpDevice.cpp                         \
                  MtpDeviceInfo.cpp                     \
                  MtpObjectInfo.cpp                     \
                  MtpPacket.cpp                         \
                  MtpProperty.cpp                       \
                  MtpRequestPacket.cpp                  \
                  MtpResponsePacket.cpp                 \
                  MtpStorageInfo.cpp                    \
                  MtpStringBuffer.cpp                   \
                  MtpUtils.cpp                          \


LOCAL_CFLAGS := -g -DMTP_HOST
LOCAL_LDFLAGS := -g

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

LOCAL_MODULE := scantest
LOCAL_SRC_FILES:=                                       \
                  scantest.cpp                          \
                  MtpMediaScanner.cpp                   \
                  MtpDatabase.cpp                       \
                  MtpDataPacket.cpp                     \
                  MtpPacket.cpp                         \
                  MtpStringBuffer.cpp                   \
                  MtpUtils.cpp                          \
                  SqliteDatabase.cpp                    \
                  SqliteStatement.cpp                   \


#LOCAL_STATIC_LIBRARIES := libusbhost
#LOCAL_LDLIBS := -lpthread

LOCAL_C_INCLUDES := external/sqlite/dist
LOCAL_SHARED_LIBRARIES := libutils libsqlite libstagefright libmedia


LOCAL_CFLAGS := -g
LOCAL_LDFLAGS := -g

include $(BUILD_EXECUTABLE)
