LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := PrivAppPermissionTest
LOCAL_SDK_VERSION := current
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MANIFEST_FILE := system/AndroidManifest.xml
LOCAL_REQUIRED_MODULES := privapp-permissions-test.xml
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE := privapp-permissions-test.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
LOCAL_SRC_FILES:= system/privapp-permissions-test.xml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := VendorPrivAppPermissionTest
LOCAL_SDK_VERSION := current
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MANIFEST_FILE := vendor/AndroidManifest.xml
LOCAL_VENDOR_MODULE := true
LOCAL_REQUIRED_MODULES := vendorprivapp-permissions-test.xml
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE := vendorprivapp-permissions-test.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_VENDOR_ETC)/permissions
LOCAL_SRC_FILES:= vendor/privapp-permissions-test.xml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := ProductPrivAppPermissionTest
LOCAL_SDK_VERSION := current
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MANIFEST_FILE := product/AndroidManifest.xml
LOCAL_PRODUCT_MODULE := true
LOCAL_REQUIRED_MODULES := productprivapp-permissions-test.xml
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE := productprivapp-permissions-test.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT_ETC)/permissions
LOCAL_SRC_FILES:= product/privapp-permissions-test.xml
include $(BUILD_PREBUILT)
