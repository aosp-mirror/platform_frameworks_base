
LOCAL_PATH:=$(call my-dir)


# Build rsg-generator ====================
include $(CLEAR_VARS)

LOCAL_MODULE := rsg-generator

# These symbols are normally defined by BUILD_XXX, but we need to define them
# here so that local-intermediates-dir works.

LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
intermediates := $(local-intermediates-dir)

LOCAL_SRC_FILES:= \
    spec.l \
    rsg_generator.c

include $(BUILD_HOST_EXECUTABLE)

# TODO: This should go into build/core/config.mk
RSG_GENERATOR:=$(LOCAL_BUILT_MODULE)

# include $(CLEAR_VARS)
# input_data_file := $(LOCAL_PATH)/rslib.bc
# slangdata_output_var_name := rs_runtime_lib_bc
# LOCAL_MODULE := librslib_rt

# LOCAL_MODULE_CLASS := STATIC_LIBRARIES

# LOCAL_MODULE_TAGS := optional
# include frameworks/compile/slang/SlangData.mk
# include $(BUILD_STATIC_LIBRARY)

# Build render script lib ====================

include $(CLEAR_VARS)
LOCAL_MODULE := libRS

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
intermediates:= $(local-intermediates-dir)

# Generate custom headers

GEN := $(addprefix $(intermediates)/, \
            rsgApiStructs.h \
            rsgApiFuncDecl.h \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(PRIVATE_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.h : $(LOCAL_PATH)/%.h.rsg
	$(transform-generated-source)

# used in jni/Android.mk
rs_generated_source += $(GEN)
LOCAL_GENERATED_SOURCES += $(GEN)

# Generate custom source files

GEN := $(addprefix $(intermediates)/, \
            rsgApi.cpp \
            rsgApiReplay.cpp \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(PRIVATE_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.cpp : $(LOCAL_PATH)/%.cpp.rsg
	$(transform-generated-source)

# used in jni/Android.mk
rs_generated_source += $(GEN)

LOCAL_GENERATED_SOURCES += $(GEN)

LOCAL_SRC_FILES:= \
	rsAdapter.cpp \
	rsAllocation.cpp \
	rsAnimation.cpp \
	rsComponent.cpp \
	rsContext.cpp \
	rsDevice.cpp \
	rsElement.cpp \
	rsFBOCache.cpp \
	rsFifoSocket.cpp \
	rsFileA3D.cpp \
	rsFont.cpp \
	rsLocklessFifo.cpp \
	rsObjectBase.cpp \
	rsMatrix2x2.cpp \
	rsMatrix3x3.cpp \
	rsMatrix4x4.cpp \
	rsMesh.cpp \
	rsMutex.cpp \
	rsProgram.cpp \
	rsProgramFragment.cpp \
	rsProgramStore.cpp \
	rsProgramRaster.cpp \
	rsProgramVertex.cpp \
	rsSampler.cpp \
	rsScript.cpp \
	rsScriptC.cpp \
	rsScriptC_Lib.cpp \
	rsScriptC_LibGL.cpp \
	rsSignal.cpp \
	rsStream.cpp \
	rsThreadIO.cpp \
	rsType.cpp \
	driver/rsdAllocation.cpp \
	driver/rsdBcc.cpp \
	driver/rsdCore.cpp \
	driver/rsdFrameBuffer.cpp \
	driver/rsdFrameBufferObj.cpp \
	driver/rsdGL.cpp \
	driver/rsdMesh.cpp \
	driver/rsdMeshObj.cpp \
	driver/rsdProgram.cpp \
	driver/rsdProgramRaster.cpp \
	driver/rsdProgramStore.cpp \
	driver/rsdRuntimeMath.cpp \
	driver/rsdRuntimeStubs.cpp \
	driver/rsdSampler.cpp \
	driver/rsdShader.cpp \
	driver/rsdShaderCache.cpp \
	driver/rsdVertexArray.cpp

LOCAL_SHARED_LIBRARIES += libz libcutils libutils libEGL libGLESv1_CM libGLESv2 libui libbcc libbcinfo

LOCAL_STATIC_LIBRARIES := libdex libft2

LOCAL_C_INCLUDES += external/freetype/include external/zlib dalvik
LOCAL_C_INCLUDES += frameworks/compile/libbcc/include

LOCAL_CFLAGS += -Werror -Wall -Wno-unused-parameter -Wno-unused-variable

LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libRS
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

# Now build a host version for serialization
include $(CLEAR_VARS)
LOCAL_MODULE:= libRS
LOCAL_MODULE_TAGS := optional

intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,HOST,)

# Generate custom headers

GEN := $(addprefix $(intermediates)/, \
            rsgApiStructs.h \
            rsgApiFuncDecl.h \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(PRIVATE_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.h : $(LOCAL_PATH)/%.h.rsg
	$(transform-generated-source)

LOCAL_GENERATED_SOURCES += $(GEN)

# Generate custom source files

GEN := $(addprefix $(intermediates)/, \
            rsgApi.cpp \
            rsgApiReplay.cpp \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(PRIVATE_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.cpp : $(LOCAL_PATH)/%.cpp.rsg
	$(transform-generated-source)

LOCAL_GENERATED_SOURCES += $(GEN)

LOCAL_CFLAGS += -Werror -Wall -Wno-unused-parameter -Wno-unused-variable
LOCAL_CFLAGS += -DANDROID_RS_SERIALIZE
LOCAL_CFLAGS += -fPIC

LOCAL_SRC_FILES:= \
	rsAdapter.cpp \
	rsAllocation.cpp \
	rsAnimation.cpp \
	rsComponent.cpp \
	rsContext.cpp \
	rsDevice.cpp \
	rsElement.cpp \
	rsFBOCache.cpp \
	rsFifoSocket.cpp \
	rsFileA3D.cpp \
	rsFont.cpp \
	rsLocklessFifo.cpp \
	rsObjectBase.cpp \
	rsMatrix2x2.cpp \
	rsMatrix3x3.cpp \
	rsMatrix4x4.cpp \
	rsMesh.cpp \
	rsMutex.cpp \
	rsProgram.cpp \
	rsProgramFragment.cpp \
	rsProgramStore.cpp \
	rsProgramRaster.cpp \
	rsProgramVertex.cpp \
	rsSampler.cpp \
	rsScript.cpp \
	rsScriptC.cpp \
	rsScriptC_Lib.cpp \
	rsScriptC_LibGL.cpp \
	rsSignal.cpp \
	rsStream.cpp \
	rsThreadIO.cpp \
	rsType.cpp

LOCAL_STATIC_LIBRARIES := libcutils libutils

LOCAL_LDLIBS := -lpthread

include $(BUILD_HOST_STATIC_LIBRARY)
