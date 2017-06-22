LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_JAR_MANIFEST := manifest.txt
LOCAL_MODULE := lockedregioncodeinjection
LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_STATIC_JAVA_LIBRARIES := \
    asm-5.2 \
    asm-commons-5.2 \
    asm-tree-5.2 \
    asm-analysis-5.2


include $(BUILD_HOST_JAVA_LIBRARY)
