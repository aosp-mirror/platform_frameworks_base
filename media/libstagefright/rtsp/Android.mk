LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
        ARTSPController.cpp         \
        AAVCAssembler.cpp           \
        AMPEG4AudioAssembler.cpp    \
        APacketSource.cpp           \
        ARTPAssembler.cpp           \
        ARTPConnection.cpp          \
        ARTPSource.cpp              \
        ARTSPConnection.cpp         \
        ASessionDescription.cpp     \

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
        $(TOP)/frameworks/base/media/libstagefright/include \

LOCAL_MODULE:= libstagefright_rtsp

ifeq ($(TARGET_ARCH),arm)
    LOCAL_CFLAGS += -Wno-psabi
endif

include $(BUILD_STATIC_LIBRARY)

