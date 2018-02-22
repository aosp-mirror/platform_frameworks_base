LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.nfc_extras
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under,java)
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.nfc_extras-stubs-gen
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_SRC_FILES := $(call all-java-files-under,java)
# This is to reference SdkConstant annotation; not part of this lib.
LOCAL_DROIDDOC_SOURCE_PATH := frameworks/base/core/java/android/annotation
LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/com.android.nfc_extras.stubs_intermediates/src
LOCAL_DROIDDOC_OPTIONS:= \
    -hide 111 -hide 113 -hide 125 -hide 126 -hide 127 -hide 128 \
    -stubpackages com.android.nfc_extras \
    -nodocs
LOCAL_UNINSTALLABLE_MODULE := true
include $(BUILD_DROIDDOC)
com_android_nfc_extras_gen_stamp := $(full_target)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.nfc_extras.stubs
# This is to reference SdkConstant annotation; not part of this lib.
LOCAL_SRC_FILES := ../core/java/android/annotation/SdkConstant.java
LOCAL_SDK_VERSION := current
LOCAL_ADDITIONAL_DEPENDENCIES := $(com_android_nfc_extras_gen_stamp)
com_android_nfc_extras_gen_stamp :=
include $(BUILD_STATIC_JAVA_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
