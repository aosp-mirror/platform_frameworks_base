LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES += \
      $(call all-java-files-under,src)

LOCAL_PACKAGE_NAME := BluetoothMidiService
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
