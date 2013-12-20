LOCAL_PATH:= $(call my-dir)

# the java library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES :=

# TODO: Move this to the product makefiles
REQUIRED_SERVICES := core accessibility appwidget backup devicepolicy print

include $(patsubst %,$(LOCAL_PATH)/%/java/service.mk,$(REQUIRED_SERVICES))

LOCAL_MODULE:= services

LOCAL_JAVA_LIBRARIES := android.policy conscrypt telephony-common

#LOCAL_PROGUARD_ENABLED := full
#LOCAL_PROGUARD_FLAG_FILES := proguard.flags

include $(BUILD_JAVA_LIBRARY)

include $(BUILD_DROIDDOC)

# native library
# =============================================================

include $(CLEAR_VARS)

LOCAL_SRC_FILES :=
LOCAL_SHARED_LIBRARIES :=

# include all the jni subdirs to collect their sources
include $(wildcard $(LOCAL_PATH)/*/jni/Android.mk)

LOCAL_CFLAGS += -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
    LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_MODULE:= libandroid_servers

include $(BUILD_SHARED_LIBRARY)

