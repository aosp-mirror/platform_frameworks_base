LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
  cdma_sms_jni.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libandroid_runtime \
	libnativehelper

LOCAL_MODULE:= libcdma_sms_jni

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	hardware/ril/include/telephony

LOCAL_C_INCLUDES += hardware/ril/reference-cdma-sms
LOCAL_SHARED_LIBRARIES += libreference-cdma-sms
LOCAL_CFLAGS += -DREFERENCE_CDMA_SMS

LOCAL_PRELINK_MODULE := false
include $(BUILD_SHARED_LIBRARY)
