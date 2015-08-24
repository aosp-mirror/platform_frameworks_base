LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4
# The design lib requires that the client package use appcompat themes.
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-appcompat
# Supplies material design components, e.g. Snackbar.
LOCAL_STATIC_JAVA_LIBRARIES += android-support-design
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-recyclerview
LOCAL_STATIC_JAVA_LIBRARIES += guava

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res 
# Not quite sure why it is necessary to explicitly pull in resources from the
# appcompat lib, but the demo code indicates it's necessary (see
# development/samples/Support7Demos/Android.mk)
LOCAL_RESOURCE_DIR += frameworks/support/v7/appcompat/res
LOCAL_RESOURCE_DIR += frameworks/support/design/res

# Again, required to pull in appcompat resources.  See abovementioned demo code.
LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_AAPT_FLAGS += --extra-packages android.support.v7.appcompat
LOCAL_AAPT_FLAGS += --extra-packages android.support.design

LOCAL_PACKAGE_NAME := DocumentsUI
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(LOCAL_PATH)/tests/Android.mk
