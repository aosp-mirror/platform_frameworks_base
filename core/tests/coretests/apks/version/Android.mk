LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_1
LOCAL_AAPT_FLAGS := --version-code 1 --version-name 1.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_2
LOCAL_AAPT_FLAGS := --version-code 2 --version-name 2.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_3
LOCAL_AAPT_FLAGS := --version-code 3 --version-name 3.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_1_diff
LOCAL_AAPT_FLAGS := --version-code 1 --version-name 1.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test_diff
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_2_diff
LOCAL_AAPT_FLAGS := --version-code 2 --version-name 2.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test_diff
include $(FrameworkCoreTests_BUILD_PACKAGE)
