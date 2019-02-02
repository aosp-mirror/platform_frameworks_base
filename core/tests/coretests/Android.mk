ACTUAL_LOCAL_PATH := $(call my-dir)

# this var will hold all the test apk module names later.
FrameworkCoreTests_all_apks :=

# We have to include the subdir makefiles first
# so that FrameworkCoreTests_all_apks will be populated correctly.
include $(call all-makefiles-under,$(ACTUAL_LOCAL_PATH))

LOCAL_PATH := $(ACTUAL_LOCAL_PATH)

include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
	$(call all-java-files-under, src) \
	$(call all-Iaidl-files-under, src) \
	$(call all-java-files-under, DisabledTestApp/src) \
	$(call all-java-files-under, EnabledTestApp/src) \
	$(call all-java-files-under, BinderProxyCountingTestApp/src) \
	$(call all-java-files-under, BinderProxyCountingTestService/src) \
	$(call all-Iaidl-files-under, aidl)

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/aidl

LOCAL_DX_FLAGS := --core-library
LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_AAPT_FLAGS = -0 dat -0 gld -c fa
LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    core-tests-support \
    android-common \
    frameworks-core-util-lib \
    mockwebserver \
    guava \
    androidx.test.espresso.core \
    androidx.test.ext.junit \
    androidx.test.runner \
    androidx.test.rules \
    mockito-target-minus-junit4 \
    ub-uiautomator \
    platform-test-annotations \
    truth-prebuilt \
    print-test-util-lib \
    testng # TODO: remove once Android migrates to JUnit 4.12, which provide assertThrows

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    telephony-common \
    testables \
    org.apache.http.legacy \
    android.test.base \
    android.test.mock \
    framework-atb-backward-compatibility \

LOCAL_PACKAGE_NAME := FrameworksCoreTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

# intermediate dir to include all the test apks as raw resource
FrameworkCoreTests_intermediates := $(call intermediates-dir-for,APPS,$(LOCAL_PACKAGE_NAME))/test_apks/res
LOCAL_RESOURCE_DIR := $(FrameworkCoreTests_intermediates) $(LOCAL_PATH)/res

# Disable AAPT2 because the hacks below depend on the AAPT rules implementation
LOCAL_USE_AAPT2 := false

include $(BUILD_PACKAGE)
# Rules to copy all the test apks to the intermediate raw resource directory
FrameworkCoreTests_all_apks_res := $(addprefix $(FrameworkCoreTests_intermediates)/raw/, \
    $(foreach a, $(FrameworkCoreTests_all_apks), $(patsubst FrameworkCoreTests_%,%,$(a))))

$(FrameworkCoreTests_all_apks_res): $(FrameworkCoreTests_intermediates)/raw/%: $(call intermediates-dir-for,APPS,FrameworkCoreTests_%)/package.apk
	$(call copy-file-to-new-target)

# Use R_file_stamp as dependency because we want the test apks in place before the R.java is generated.
$(R_file_stamp) : $(FrameworkCoreTests_all_apks_res)

FrameworkCoreTests_all_apks :=
FrameworkCoreTests_intermediates :=
FrameworkCoreTests_all_apks_res :=
