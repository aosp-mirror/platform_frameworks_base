LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE = SettingsLib-search

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/main/res

include $(BUILD_STATIC_JAVA_LIBRARY)


include $(CLEAR_VARS)

LOCAL_MODULE = SettingsLib-search-host

LOCAL_SRC_FILES := $(call all-java-files-under, src)

include $(BUILD_HOST_JAVA_LIBRARY)


include $(CLEAR_VARS)

LOCAL_MODULE = SettingsLib-annotation-processor

LOCAL_STATIC_JAVA_LIBRARIES := \
    javapoet-prebuilt-jar \
    SettingsLib-search-host

LOCAL_SRC_FILES := $(call all-java-files-under, processor-src)

LOCAL_JAVA_RESOURCE_DIRS := \
    resources

include $(BUILD_HOST_JAVA_LIBRARY)
