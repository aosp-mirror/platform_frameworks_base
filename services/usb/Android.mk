LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.usb

LOCAL_SRC_FILES += \
      $(call all-java-files-under,java)

LOCAL_JAVA_LIBRARIES := services.core
LOCAL_STATIC_JAVA_LIBRARIES := android.hardware.usb@1.0-java-static \
android.hidl.manager@1.0-java-static

include $(BUILD_STATIC_JAVA_LIBRARY)
