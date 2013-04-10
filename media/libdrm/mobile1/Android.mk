LOCAL_PATH := $(call my-dir)

# ---------------------------------------
# First project
# 
# Build DRM1 core library
#
# Output: libdrm1.so
# ---------------------------------------
include $(CLEAR_VARS)

ifeq ($(TARGET_ARCH), arm)
LOCAL_DRM_CFLAG = -DDRM_DEVICE_ARCH_ARM
endif

ifeq ($(TARGET_ARCH), x86)
LOCAL_DRM_CFLAG = -DDRM_DEVICE_ARCH_X86
endif

# DRM 1.0 core source files
LOCAL_SRC_FILES :=                  \
    src/objmng/drm_decoder.c        \
    src/objmng/drm_file.c           \
    src/objmng/drm_i18n.c           \
    src/objmng/drm_time.c           \
    src/objmng/drm_api.c            \
    src/objmng/drm_rights_manager.c \
    src/parser/parser_dcf.c         \
    src/parser/parser_dm.c          \
    src/parser/parser_rel.c         \
    src/xml/xml_tinyparser.c

# Header files path
LOCAL_C_INCLUDES :=                 \
    $(LOCAL_PATH)/include           \
    $(LOCAL_PATH)/include/objmng    \
    $(LOCAL_PATH)/include/parser    \
    $(LOCAL_PATH)/include/xml       \
    external/openssl/include        \
    $(call include-path-for, system-core)/cutils

LOCAL_CFLAGS := $(LOCAL_DRM_CFLAG)

LOCAL_SHARED_LIBRARIES :=   \
    libutils                \
    libcutils               \
    liblog                  \
    libcrypto

LOCAL_MODULE := libdrm1

include $(BUILD_SHARED_LIBRARY)

# ---------------------------------------
# Second project
# 
# Build DRM1 Java Native Interface(JNI) library
#
# Output: libdrm1_jni.so
# ------------------------------------------------
include $(CLEAR_VARS)

# Source files of DRM1 Java Native Interfaces
LOCAL_SRC_FILES :=      \
    src/jni/drm1_jni.c

# Header files path
LOCAL_C_INCLUDES :=         \
    $(LOCAL_PATH)/include   \
    $(LOCAL_PATH)/include/parser \
    $(JNI_H_INCLUDE)    \
    $(call include-path-for, system-core)/cutils


LOCAL_SHARED_LIBRARIES := libdrm1 \
    libnativehelper               \
    libutils                      \
    libcutils                     \
    liblog

LOCAL_MODULE := libdrm1_jni

include $(BUILD_SHARED_LIBRARY)
