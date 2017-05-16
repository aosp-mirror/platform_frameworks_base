LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.usb

LOCAL_SRC_FILES += \
      $(call all-java-files-under,java)

LOCAL_JAVA_LIBRARIES := services.core \
android.hidl.manager-V1.0-java

LOCAL_STATIC_JAVA_LIBRARIES := android.hardware.usb-V1.0-java \
android.hardware.usb-V1.1-java

include $(BUILD_STATIC_JAVA_LIBRARY)
