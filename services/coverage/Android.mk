LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.coverage

LOCAL_SRC_FILES += \
      $(call all-java-files-under,java)

LOCAL_JAVA_LIBRARIES := jacocoagent

include $(BUILD_STATIC_JAVA_LIBRARY)
