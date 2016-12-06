LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml

# omit gradle 'build' dir
LOCAL_SRC_FILES := $(call all-java-files-under,app/src/main/java)

# use appcompat/support lib from the tree, so improvements/
# regressions are reflected in test data
LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/app/src/main/res \
    frameworks/support/v7/appcompat/res

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --extra-packages android.support.v7.appcompat

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v4 \
    android-support-v7-appcompat

LOCAL_PACKAGE_NAME := TouchLatency

include $(BUILD_PACKAGE)
