LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        ABitReader.cpp            \
        AnotherPacketSource.cpp   \
        ATSParser.cpp             \
        MPEG2TSExtractor.cpp      \

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
        $(TOP)/frameworks/base/media/libstagefright

LOCAL_MODULE:= libstagefright_mpeg2ts

ifeq ($(TARGET_ARCH),arm)
    LOCAL_CFLAGS += -Wno-psabi
endif

include $(BUILD_STATIC_LIBRARY)
