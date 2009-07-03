LOCAL_PATH:= $(call my-dir)

# ---------------------------------------
# First project
# 
# Build DRM2 core library
#
# Output: libdrm2.so
# ---------------------------------------
include $(CLEAR_VARS)

common_SRC_FILES := \
    src/util/ustl-1.0/bktrace.cpp \
    src/util/ustl-1.0/memblock.cpp \
    src/util/ustl-1.0/ofstream.cpp \
    src/util/ustl-1.0/ualgobase.cpp \
    src/util/ustl-1.0/unew.cpp \
    src/util/ustl-1.0/cmemlink.cpp \
    src/util/ustl-1.0/memlink.cpp \
    src/util/ustl-1.0/sistream.cpp \
    src/util/ustl-1.0/ubitset.cpp \
    src/util/ustl-1.0/ustdxept.cpp \
    src/util/ustl-1.0/fstream.cpp \
    src/util/ustl-1.0/mistream.cpp \
    src/util/ustl-1.0/sostream.cpp \
    src/util/ustl-1.0/uexception.cpp \
    src/util/ustl-1.0/ustring.cpp \
    src/util/xml/DomExpatAgent.cpp \
    src/util/xml/ExpatWrapper.cpp \
    src/util/xml/XMLDocumentImpl.cpp \
    src/util/xml/XMLElementImpl.cpp \
    src/util/domcore/CharacterDataImpl.cpp \
    src/util/domcore/ElementImpl.cpp \
    src/util/domcore/NodeListImpl.cpp \
    src/util/domcore/DocumentImpl.cpp \
    src/util/domcore/NodeImpl.cpp \
    src/util/domcore/TextImpl.cpp \
    src/util/domcore/DOMException.cpp \
    src/util/domcore/NodeIterator.cpp \
    src/util/crypto/DrmCrypto.cpp \
    src/rights/RoManager.cpp \
    src/rights/Asset.cpp \
    src/rights/Ro.cpp \
    src/rights/OperationPermission.cpp \
    src/rights/Right.cpp \
    src/rights/Constraint.cpp \
    src/drmmanager/DrmManager.cpp \
    src/dcf/DrmDcfCommon.cpp \
    src/dcf/DrmDcfContainer.cpp \
    src/dcf/DrmIStream.cpp \
    src/dcf/DrmRawContent.cpp \
    src/roap/RoapMessageHandler.cpp \
    src/roap/Registration.cpp

ifeq ($(TARGET_ARCH),arm)
	LOCAL_CFLAGS += -fstrict-aliasing -fomit-frame-pointer
endif

common_CFLAGS := -W -g -DPLATFORM_ANDROID

common_C_INCLUDES +=\
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/src/util/ustl-1.0 \
    external/expat/lib \
    external/openssl    \
    external/openssl/include

LOCAL_SRC_FILES := $(common_SRC_FILES)
LOCAL_CFLAGS += $(common_CFLAGS)
LOCAL_C_INCLUDES += $(common_C_INCLUDES)

LOCAL_SHARED_LIBRARIES := libexpat libcrypto
LOCAL_MODULE := libdrm2 

ifeq ($(TARGET_OS)-$(TARGET_ARCH),linux-x86)
LOCAL_CFLAGS += -DUSTL_ANDROID_X86
else
  ifeq ($(TARGET_OS)-$(TARGET_ARCH),linux-sh)
  LOCAL_CFLAGS += -DUSTL_ANDROID_SH
  endif
endif

include $(BUILD_STATIC_LIBRARY)
