LOCAL_PATH:= $(call my-dir)

# merge all required services into one jar
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE := services

LOCAL_SRC_FILES := $(call all-java-files-under,java)

LOCAL_STATIC_JAVA_LIBRARIES := \
    services.core \
    services.accessibility \
    services.appwidget \
    services.backup \
    services.devicepolicy \
    services.print

include $(BUILD_JAVA_LIBRARY)

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

ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call all-makefiles-under, $(LOCAL_PATH))
endif

