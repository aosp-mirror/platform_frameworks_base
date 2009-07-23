LOCAL_PATH:=$(call my-dir)


# Build rsg-generator ====================
include $(CLEAR_VARS)

LOCAL_MODULE := rsg-generator

# These symbols are normally defined by BUILD_XXX, but we need to define them
# here so that local-intermediates-dir works.

LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
intermediates := $(local-intermediates-dir)

GEN := $(addprefix $(intermediates)/, \
            lex.yy.c \
        )
$(GEN):	PRIVATE_CUSTOM_TOOL = flex -o $@ $<

$(intermediates)/lex.yy.c : $(LOCAL_PATH)/spec.lex
	$(transform-generated-source)

$(LOCAL_PATH)/rsg_generator.c : $(intermediates)/lex.yy.c

LOCAL_SRC_FILES:= \
    rsg_generator.c

include $(BUILD_HOST_EXECUTABLE)

# TODO: This should go into build/core/config.mk
RSG_GENERATOR:=$(LOCAL_BUILT_MODULE)



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
	rsComponent.cpp \
	rsContext.cpp \
	rsDevice.cpp \
	rsElement.cpp \
        rsFileA3D.cpp \
	rsLight.cpp \
	rsLocklessFifo.cpp \
	rsObjectBase.cpp \
	rsMatrix.cpp \
        rsMesh.cpp \
	rsProgram.cpp \
	rsProgramFragment.cpp \
	rsProgramFragmentStore.cpp \
	rsProgramVertex.cpp \
	rsSampler.cpp \
	rsScript.cpp \
	rsScriptC.cpp \
	rsScriptC_Lib.cpp \
	rsThreadIO.cpp \
	rsType.cpp \
	rsTriangleMesh.cpp

LOCAL_SHARED_LIBRARIES += libcutils libutils libEGL libGLESv1_CM libui libacc
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libRS

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

ifeq ($(BUILD_RENDERSCRIPT),true)

# Include the subdirectories ====================
include $(addprefix $(LOCAL_PATH)/,$(addsuffix /Android.mk,\
            java \
    	))

endif