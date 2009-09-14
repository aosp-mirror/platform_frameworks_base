# Copyright (C) 2009 The Android Open Source Project
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
# define the KEYSTORE_TESTS environment variable to build the test programs
ifdef KEYSTORE_TESTS
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_SRC_FILES:= netkeystore_test.c ../keymgmt.c ../netkeystore.c
LOCAL_SHARED_LIBRARIES := libcutils libssl
LOCAL_MODULE:= netkeystore_test
LOCAL_MODULE_TAGS := optional
LOCAL_C_INCLUDES := external/openssl/include \
								frameworks/base/cmds/keystore
EXTRA_CFLAGS := -g -O0 -DGTEST_OS_LINUX -DGTEST_HAS_STD_STRING
include $(BUILD_EXECUTABLE)

endif  #KEYSTORE_TESTS
