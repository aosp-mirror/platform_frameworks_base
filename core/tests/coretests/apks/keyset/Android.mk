LOCAL_PATH:= $(call my-dir)

#apks signed by keyset_A
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sa_unone
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := uNone/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sa_ua
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := uA/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sa_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := uB/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sa_uab
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := uAB/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sa_ua_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := uAuB/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_permdef_sa_unone
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := permDef/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_permuse_sa_ua_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := permUse/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

#apks signed by keyset_B
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sb_ua
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_B
LOCAL_MANIFEST_FILE := uA/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sb_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_B
LOCAL_MANIFEST_FILE := uB/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_permuse_sb_ua_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_B
LOCAL_MANIFEST_FILE := permUse/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

#apks signed by keyset_A and keyset_B
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sab_ua
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_ADDITIONAL_CERTIFICATES := $(LOCAL_PATH)/../../certs/keyset_B
LOCAL_MANIFEST_FILE := uA/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

#apks signed by keyset_A and unit_test
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_sau_ub
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_ADDITIONAL_CERTIFICATES := $(LOCAL_PATH)/../../certs/keyset_B
LOCAL_MANIFEST_FILE := uB/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

#apks signed by platform only
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_splat_api
LOCAL_CERTIFICATE := platform
LOCAL_MANIFEST_FILE := api_test/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)

#apks signed by platform and keyset_A
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := keyset_splata_api
LOCAL_CERTIFICATE := platform
LOCAL_ADDITIONAL_CERTIFICATES := $(LOCAL_PATH)/../../certs/keyset_A
LOCAL_MANIFEST_FILE := api_test/AndroidManifest.xml
include $(FrameworkCoreTests_BUILD_PACKAGE)