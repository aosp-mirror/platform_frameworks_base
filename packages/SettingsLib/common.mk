#
# Include this make file to build your application against this module.
#
# Make sure to include it after you've set all your desired LOCAL variables.
# Note that you must explicitly set your LOCAL_RESOURCE_DIR before including
# this file.
#
# For example:
#
#   LOCAL_RESOURCE_DIR := \
#        $(LOCAL_PATH)/res
#
#   include frameworks/base/packages/SettingsLib/common.mk
#

ifeq ($(LOCAL_USE_AAPT2),true)
LOCAL_STATIC_ANDROID_LIBRARIES += \
    android-support-annotations \
    android-support-v4 \
    SettingsLib
else
LOCAL_RESOURCE_DIR += $(call my-dir)/res
LOCAL_AAPT_FLAGS += --auto-add-overlay --extra-packages com.android.settingslib
LOCAL_STATIC_JAVA_LIBRARIES += \
    android-support-annotations \
    android-support-v4 \
    SettingsLib
endif
