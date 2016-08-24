LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_PRIVILEGED_MODULE := true

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4
# The design lib requires that the client package use appcompat themes.
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-appcompat
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v13
# Supplies material design components, e.g. Snackbar.
LOCAL_STATIC_JAVA_LIBRARIES += android-support-design
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-recyclerview
LOCAL_STATIC_JAVA_LIBRARIES += guava

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
# Not quite sure why it is necessary to explicitly pull in resources from the
# appcompat lib, but the demo code indicates it's necessary (see
# development/samples/Support7Demos/Android.mk)
LOCAL_RESOURCE_DIR += \
  frameworks/support/v7/appcompat/res \
  frameworks/support/design/res \
  frameworks/support/v7/recyclerview/res

# Again, required to pull in appcompat resources.  See abovementioned demo code.
LOCAL_AAPT_FLAGS := \
  --auto-add-overlay \
  --extra-packages android.support.v7.appcompat \
  --extra-packages android.support.design \
  --extra-packages android.support.v7.recyclerview

LOCAL_JACK_FLAGS := \
  -D jack.optimization.inner-class.accessors=true

# Only enable asserts on userdebug/eng builds
ifneq (,$(filter userdebug eng, $(TARGET_BUILD_VARIANT)))
LOCAL_JACK_FLAGS += -D jack.assert.policy=always
endif

LOCAL_PACKAGE_NAME := DocumentsUI
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
include $(call all-makefiles-under, $(LOCAL_PATH))
