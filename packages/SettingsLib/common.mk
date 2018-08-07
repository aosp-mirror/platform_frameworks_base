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

LOCAL_STATIC_JAVA_LIBRARIES += \
    android-support-annotations \
    android-arch-lifecycle-common

LOCAL_STATIC_ANDROID_LIBRARIES += \
    android-support-v4 \
    android-arch-lifecycle-runtime \
    android-support-v7-recyclerview \
    android-support-v7-preference \
    android-support-v7-appcompat \
    android-support-v14-preference \
    SettingsLib

