LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

HWUI_NEW_OPS := true

# Enables fine-grained GLES error checking
# If set to true, every GLES call is wrapped & error checked
# Has moderate overhead
HWUI_ENABLE_OPENGL_VALIDATION := false

hwui_src_files := \
    font/CacheTexture.cpp \
    font/Font.cpp \
    hwui/Canvas.cpp \
    hwui/MinikinSkia.cpp \
    hwui/MinikinUtils.cpp \
    hwui/PaintImpl.cpp \
    hwui/Typeface.cpp \
    renderstate/Blend.cpp \
    renderstate/MeshState.cpp \
    renderstate/OffscreenBufferPool.cpp \
    renderstate/PixelBufferState.cpp \
    renderstate/RenderState.cpp \
    renderstate/Scissor.cpp \
    renderstate/Stencil.cpp \
    renderstate/TextureState.cpp \
    renderthread/CanvasContext.cpp \
    renderthread/DrawFrameTask.cpp \
    renderthread/EglManager.cpp \
    renderthread/RenderProxy.cpp \
    renderthread/RenderTask.cpp \
    renderthread/RenderThread.cpp \
    renderthread/TimeLord.cpp \
    thread/TaskManager.cpp \
    utils/Blur.cpp \
    utils/GLUtils.cpp \
    utils/LinearAllocator.cpp \
    utils/NinePatchImpl.cpp \
    utils/StringUtils.cpp \
    utils/TestWindowContext.cpp \
    utils/VectorDrawableUtils.cpp \
    AmbientShadow.cpp \
    AnimationContext.cpp \
    Animator.cpp \
    AnimatorManager.cpp \
    AssetAtlas.cpp \
    Caches.cpp \
    CanvasState.cpp \
    ClipArea.cpp \
    DamageAccumulator.cpp \
    DeferredDisplayList.cpp \
    DeferredLayerUpdater.cpp \
    DeviceInfo.cpp \
    DisplayList.cpp \
    DisplayListCanvas.cpp \
    Dither.cpp \
    Extensions.cpp \
    FboCache.cpp \
    FontRenderer.cpp \
    FrameInfo.cpp \
    FrameInfoVisualizer.cpp \
    GammaFontRenderer.cpp \
    GlopBuilder.cpp \
    GpuMemoryTracker.cpp \
    GradientCache.cpp \
    Image.cpp \
    Interpolator.cpp \
    JankTracker.cpp \
    Layer.cpp \
    LayerCache.cpp \
    LayerRenderer.cpp \
    LayerUpdateQueue.cpp \
    Matrix.cpp \
    OpenGLRenderer.cpp \
    Patch.cpp \
    PatchCache.cpp \
    PathCache.cpp \
    PathTessellator.cpp \
    PathParser.cpp \
    PixelBuffer.cpp \
    Program.cpp \
    ProgramCache.cpp \
    Properties.cpp \
    PropertyValuesHolder.cpp \
    PropertyValuesAnimatorSet.cpp \
    Readback.cpp \
    RenderBufferCache.cpp \
    RenderNode.cpp \
    RenderProperties.cpp \
    ResourceCache.cpp \
    ShadowTessellator.cpp \
    SkiaCanvas.cpp \
    SkiaCanvasProxy.cpp \
    SkiaShader.cpp \
    Snapshot.cpp \
    SpotShadow.cpp \
    TessellationCache.cpp \
    TextDropShadowCache.cpp \
    Texture.cpp \
    TextureCache.cpp \
    VectorDrawable.cpp \
    protos/hwui.proto

hwui_test_common_src_files := \
    $(call all-cpp-files-under, tests/common/scenes) \
    tests/common/TestContext.cpp \
    tests/common/TestScene.cpp \
    tests/common/TestUtils.cpp

hwui_cflags := \
    -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES \
    -DATRACE_TAG=ATRACE_TAG_VIEW -DLOG_TAG=\"OpenGLRenderer\" \
    -Wall -Wno-unused-parameter -Wunreachable-code -Werror

ifeq ($(TARGET_USES_HWC2),true)
    hwui_cflags += -DUSE_HWC2
endif

# GCC false-positives on this warning, and since we -Werror that's
# a problem
hwui_cflags += -Wno-free-nonheap-object

ifeq (true, $(HWUI_NEW_OPS))
    hwui_src_files += \
        BakedOpDispatcher.cpp \
        BakedOpRenderer.cpp \
        BakedOpState.cpp \
        FrameBuilder.cpp \
        LayerBuilder.cpp \
        OpDumper.cpp \
        RecordingCanvas.cpp

    hwui_cflags += -DHWUI_NEW_OPS

endif

ifndef HWUI_COMPILE_SYMBOLS
    hwui_cflags += -fvisibility=hidden
endif

ifdef HWUI_COMPILE_FOR_PERF
    # TODO: Non-arm?
    hwui_cflags += -fno-omit-frame-pointer -marm -mapcs
endif

# This has to be lazy-resolved because it depends on the LOCAL_MODULE_CLASS
# which varies depending on what is being built
define hwui_proto_include
$(call local-generated-sources-dir)/proto/$(LOCAL_PATH)
endef

hwui_c_includes += \
    external/skia/include/private \
    external/skia/src/core \
    external/harfbuzz_ng/src \
    external/freetype/include

ifneq (false,$(ANDROID_ENABLE_RENDERSCRIPT))
    hwui_cflags += -DANDROID_ENABLE_RENDERSCRIPT
    hwui_c_includes += \
        $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,) \
        frameworks/rs/cpp \
        frameworks/rs
endif

ifeq (true, $(HWUI_ENABLE_OPENGL_VALIDATION))
    hwui_cflags += -include debug/wrap_gles.h
    hwui_src_files += debug/wrap_gles.cpp
    hwui_c_includes += frameworks/native/opengl/libs/GLES2
    hwui_cflags += -DDEBUG_OPENGL=3
endif


# ------------------------
# static library
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := STATIC_LIBRARIES
LOCAL_MODULE := libhwui_static
LOCAL_CFLAGS := $(hwui_cflags)
LOCAL_SRC_FILES := $(hwui_src_files)
LOCAL_C_INCLUDES := $(hwui_c_includes) $(call hwui_proto_include)
LOCAL_EXPORT_C_INCLUDE_DIRS := \
        $(LOCAL_PATH) \
        $(hwui_c_includes) \
        $(call hwui_proto_include)

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_STATIC_LIBRARY)

# ------------------------
# static library null gpu
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := STATIC_LIBRARIES
LOCAL_MODULE := libhwui_static_null_gpu
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -DHWUI_NULL_GPU
LOCAL_SRC_FILES := \
        $(hwui_src_files) \
        debug/nullegl.cpp \
        debug/nullgles.cpp
LOCAL_C_INCLUDES := $(hwui_c_includes) $(call hwui_proto_include)
LOCAL_EXPORT_C_INCLUDE_DIRS := \
        $(LOCAL_PATH) \
        $(hwui_c_includes) \
        $(call hwui_proto_include)

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_STATIC_LIBRARY)

# ------------------------
# shared library
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE := libhwui
LOCAL_WHOLE_STATIC_LIBRARIES := libhwui_static
LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_SHARED_LIBRARY)

# ------------------------
# unit tests
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE := hwui_unit_tests
LOCAL_MODULE_TAGS := tests
LOCAL_STATIC_LIBRARIES := libhwui_static_null_gpu
LOCAL_SHARED_LIBRARIES := libmemunreachable
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -DHWUI_NULL_GPU

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/unit/main.cpp \
    tests/unit/CanvasStateTests.cpp \
    tests/unit/ClipAreaTests.cpp \
    tests/unit/DamageAccumulatorTests.cpp \
    tests/unit/DeviceInfoTests.cpp \
    tests/unit/FatVectorTests.cpp \
    tests/unit/FontRendererTests.cpp \
    tests/unit/GlopBuilderTests.cpp \
    tests/unit/GpuMemoryTrackerTests.cpp \
    tests/unit/GradientCacheTests.cpp \
    tests/unit/LayerUpdateQueueTests.cpp \
    tests/unit/LinearAllocatorTests.cpp \
    tests/unit/MatrixTests.cpp \
    tests/unit/OffscreenBufferPoolTests.cpp \
    tests/unit/RenderNodeTests.cpp \
    tests/unit/RenderPropertiesTests.cpp \
    tests/unit/SkiaBehaviorTests.cpp \
    tests/unit/SnapshotTests.cpp \
    tests/unit/StringUtilsTests.cpp \
    tests/unit/TestUtilsTests.cpp \
    tests/unit/TextDropShadowCacheTests.cpp \
    tests/unit/VectorDrawableTests.cpp

ifeq (true, $(HWUI_NEW_OPS))
    LOCAL_SRC_FILES += \
        tests/unit/BakedOpDispatcherTests.cpp \
        tests/unit/BakedOpRendererTests.cpp \
        tests/unit/BakedOpStateTests.cpp \
        tests/unit/FrameBuilderTests.cpp \
        tests/unit/LeakCheckTests.cpp \
        tests/unit/OpDumperTests.cpp \
        tests/unit/RecordingCanvasTests.cpp \
        tests/unit/SkiaCanvasTests.cpp
endif

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_NATIVE_TEST)

# ------------------------
# Macro-bench app
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/local/tmp
LOCAL_MODULE:= hwuitest
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := hwuitest
LOCAL_MODULE_STEM_64 := hwuitest64
LOCAL_CFLAGS := $(hwui_cflags)

# set to libhwui_static_null_gpu to skip actual GL commands
LOCAL_WHOLE_STATIC_LIBRARIES := libhwui_static

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/macrobench/TestSceneRunner.cpp \
    tests/macrobench/main.cpp

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_EXECUTABLE)

# ------------------------
# Micro-bench app
# ---------------------
include $(CLEAR_VARS)

LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/local/tmp
LOCAL_MODULE:= hwuimicro
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := hwuimicro
LOCAL_MODULE_STEM_64 := hwuimicro64
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -DHWUI_NULL_GPU

LOCAL_WHOLE_STATIC_LIBRARIES := libhwui_static_null_gpu
LOCAL_STATIC_LIBRARIES := libgoogle-benchmark

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/microbench/main.cpp \
    tests/microbench/DisplayListCanvasBench.cpp \
    tests/microbench/FontBench.cpp \
    tests/microbench/LinearAllocatorBench.cpp \
    tests/microbench/PathParserBench.cpp \
    tests/microbench/ShadowBench.cpp \
    tests/microbench/TaskManagerBench.cpp

ifeq (true, $(HWUI_NEW_OPS))
    LOCAL_SRC_FILES += \
        tests/microbench/FrameBuilderBench.cpp
endif

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_EXECUTABLE)
