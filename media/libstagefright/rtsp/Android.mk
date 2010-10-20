LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
        AAMRAssembler.cpp           \
        AAVCAssembler.cpp           \
        AH263Assembler.cpp          \
        AMPEG4AudioAssembler.cpp    \
        AMPEG4ElementaryAssembler.cpp \
        APacketSource.cpp           \
        ARTPAssembler.cpp           \
        ARTPConnection.cpp          \
        ARTPSession.cpp             \
        ARTPSource.cpp              \
        ARTPWriter.cpp              \
        ARTSPConnection.cpp         \
        ARTSPController.cpp         \
        ASessionDescription.cpp     \
        UDPPusher.cpp               \

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	$(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/frameworks/base/media/libstagefright/include \
        $(TOP)/external/openssl/include

LOCAL_MODULE:= libstagefright_rtsp

ifeq ($(TARGET_ARCH),arm)
    LOCAL_CFLAGS += -Wno-psabi
endif

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=         \
        rtp_test.cpp

LOCAL_SHARED_LIBRARIES := \
	libstagefright liblog libutils libbinder libstagefright_foundation

LOCAL_STATIC_LIBRARIES := \
        libstagefright_rtsp

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
	frameworks/base/media/libstagefright \
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE:= rtp_test

include $(BUILD_EXECUTABLE)
