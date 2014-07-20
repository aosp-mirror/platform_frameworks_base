LOCAL_PATH:= $(call my-dir)

# merge all required services into one jar
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE := services

LOCAL_SRC_FILES := $(call all-java-files-under,java)

# EventLogTags files.
LOCAL_SRC_FILES += \
        core/java/com/android/server/EventLogTags.logtags

# Uncomment to enable output of certain warnings (deprecated, unchecked)
# LOCAL_JAVACFLAGS := -Xlint

# Services that will be built as part of services.jar
# These should map to directory names relative to this
# Android.mk.
services := \
    core \
    accessibility \
    appwidget \
    backup \
    devicepolicy \
    print \
    restrictions \
    usage \
    usb \
    voiceinteraction

# The convention is to name each service module 'services.$(module_name)'
LOCAL_STATIC_JAVA_LIBRARIES := $(addprefix services.,$(services))

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

# =============================================================

ifeq (,$(ONE_SHOT_MAKEFILE))
# A full make is happening, so make everything.
include $(call all-makefiles-under,$(LOCAL_PATH))
else
# If we ran an mm[m] command, we still want to build the individual
# services that we depend on. This differs from the above condition
# by only including service makefiles and not any tests or other
# modules.
include $(patsubst %,$(LOCAL_PATH)/%/Android.mk,$(services))
endif

