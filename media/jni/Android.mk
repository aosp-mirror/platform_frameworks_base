LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_CFLAGS := -Wall -Werror
LOCAL_SRC_FILES := \
    Media2Jni.cpp \

# TODO: Move libmedia2_jni from system to media apex. Currently, libraries defined in
#       Android.mk is not visible in apex build.
LOCAL_MODULE:= libmedia2_jni
LOCAL_SHARED_LIBRARIES := libdl liblog

sanitizer_runtime_libraries := $(call normalize-path-list,$(addsuffix .so,\
  $(ADDRESS_SANITIZER_RUNTIME_LIBRARY) \
  $(UBSAN_RUNTIME_LIBRARY) \
  $(TSAN_RUNTIME_LIBRARY)))

# $(info Sanitizer:  $(sanitizer_runtime_libraries))

ndk_libraries := $(call normalize-path-list,$(addprefix lib,$(addsuffix .so,\
  $(NDK_PREBUILT_SHARED_LIBRARIES))))

# $(info NDK:  $(ndk_libraries))

LOCAL_CFLAGS += -DLINKED_LIBRARIES='"$(sanitizer_runtime_libraries):$(ndk_libraries)"'

sanitizer_runtime_libraries :=
ndk_libraries :=

include $(BUILD_SHARED_LIBRARY)
