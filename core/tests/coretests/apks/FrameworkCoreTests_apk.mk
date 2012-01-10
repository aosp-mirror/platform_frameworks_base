
LOCAL_MODULE_TAGS := tests

# Disable dexpreopt.
LOCAL_DEX_PREOPT := false

# Make sure every package name gets the FrameworkCoreTests_ prefix.
LOCAL_PACKAGE_NAME := FrameworkCoreTests_$(LOCAL_PACKAGE_NAME)

FrameworkCoreTests_all_apks += $(LOCAL_PACKAGE_NAME)

include $(BUILD_PACKAGE)
