LOCAL_PATH:= $(call my-dir)

# Build the IntentFilterVerifier.
include $(CLEAR_VARS)

LOCAL_STATIC_JAVA_LIBRARIES := \
        volley \

LOCAL_JAVA_LIBRARIES += org.apache.http.legacy

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := IntentFilterVerifier

LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAGS := $(proguard.flags)

include $(BUILD_PACKAGE)

# Build the test package.
include $(call all-makefiles-under,$(LOCAL_PATH))
