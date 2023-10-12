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
# During the conversion to Soong bluprint files, the equivalent
# functionality is provided by adding
#   defaults: ["SettingsLibDefaults"],
# to the corresponding module.
# NOTE: keep this file and ./Android.bp in sync.

LOCAL_STATIC_ANDROID_LIBRARIES += \
    SettingsLib
