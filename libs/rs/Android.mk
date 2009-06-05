# Only build if BUILD_RENDERSCRIPT is defined to true in the environment.
ifeq ($(BUILD_RENDERSCRIPT),true)

TOP_LOCAL_PATH:=$(call my-dir)
include $(CLEAR_VARS)
LOCAL_PATH:= $(TOP_LOCAL_PATH)

# Build rsg-generator

LOCAL_MODULE := rsg-generator

# These symbols are normally defined by BUILD_XXX, but we need to define them
# here so that local-intermediates-dir works.

LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
intermediates:= $(local-intermediates-dir)

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

RSG_GENERATOR:=$(LOCAL_BUILT_MODULE)

# Build render script lib

include $(CLEAR_VARS)
LOCAL_MODULE := libRS

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
intermediates:= $(local-intermediates-dir)

RS_GENERATED_INCLUDE_DIR:=$(intermediates)

# Generate custom headers

GEN := $(addprefix $(intermediates)/, \
            rsgApiStructs.h \
            rsgApiFuncDecl.h \
        )

$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(TOP_LOCAL_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.h : $(LOCAL_PATH)/%.h.rsg
	$(transform-generated-source)

RS_GENERATED_SOURCES += $(GEN)
LOCAL_GENERATED_SOURCES += $(GEN)

# Generate custom source files

GEN := $(addprefix $(intermediates)/, \
            rsgApi.cpp \
            rsgApiReplay.cpp \
        )

$(GEN) : PRIVATE_CUSTOM_TOOL = $(RSG_GENERATOR) $< $@ <$(TOP_LOCAL_PATH)/rs.spec
$(GEN) : $(RSG_GENERATOR) $(LOCAL_PATH)/rs.spec
$(GEN): $(intermediates)/%.cpp : $(LOCAL_PATH)/%.cpp.rsg
	$(transform-generated-source)

RS_GENERATED_SOURCES += $(GEN)
LOCAL_GENERATED_SOURCES += $(GEN)

LOCAL_SRC_FILES:= \
	rsAdapter.cpp \
	rsAllocation.cpp \
	rsComponent.cpp \
	rsContext.cpp \
	rsDevice.cpp \
	rsElement.cpp \
	rsLocklessFifo.cpp \
	rsObjectBase.cpp \
	rsMatrix.cpp \
	rsProgram.cpp \
	rsProgramFragment.cpp \
	rsProgramFragmentStore.cpp \
	rsProgramVertex.cpp \
	rsSampler.cpp \
	rsScript.cpp \
	rsScriptC.cpp \
	rsThreadIO.cpp \
	rsType.cpp \
	rsTriangleMesh.cpp

LOCAL_SHARED_LIBRARIES += libcutils libutils libEGL libGLESv1_CM libui libacc
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libRS
LOCAL_PRELINK_MODULE := false

#LOCAL_MODULE_TAGS := tests

include $(BUILD_SHARED_LIBRARY)

# Build JNI library

LOCAL_PATH:= $(TOP_LOCAL_PATH)/jni
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	RenderScript_jni.cpp

LOCAL_SHARED_LIBRARIES := \
	libandroid_runtime \
	libacc \
	libnativehelper \
	libRS \
	libcutils \
    libsgl \
	libutils \
	libui

LOCAL_STATIC_LIBRARIES :=

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(RS_GENERATED_INCLUDE_DIR) \
	$(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libRS_jni
LOCAL_PRELINK_MODULE := false

LOCAL_ADDITIONAL_DEPENDENCIES += $(RS_GENERATED_SOURCES)

include $(BUILD_SHARED_LIBRARY)

include $(call all-subdir-makefiles)
endif # BUILD_RENDERSCRIPT
