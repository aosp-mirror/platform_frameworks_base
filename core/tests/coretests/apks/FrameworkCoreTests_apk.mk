
LOCAL_MODULE_TAGS := tests

# Disable dexpreopt.
LOCAL_DEX_PREOPT := false

# Make sure every package name gets the FrameworkCoreTests_ prefix.
LOCAL_PACKAGE_NAME := FrameworkCoreTests_$(LOCAL_PACKAGE_NAME)
LOCAL_SDK_VERSION := current

# Every package should have a native library
LOCAL_JNI_SHARED_LIBRARIES := libframeworks_coretests_jni

FrameworkCoreTests_all_apks += $(LOCAL_PACKAGE_NAME)

include $(BUILD_PACKAGE)
