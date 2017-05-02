LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

BUGREPORT_FONT_CACHE_USAGE := false

# Enables fine-grained GLES error checking
# If set to true, every GLES call is wrapped & error checked
# Has moderate overhead
HWUI_ENABLE_OPENGL_VALIDATION := false

hwui_src_files := \
    hwui/Bitmap.cpp \
    font/CacheTexture.cpp \
    font/Font.cpp \
    hwui/Canvas.cpp \
    hwui/MinikinSkia.cpp \
    hwui/MinikinUtils.cpp \
    hwui/PaintImpl.cpp \
    hwui/Typeface.cpp \
    pipeline/skia/GLFunctorDrawable.cpp \
    pipeline/skia/LayerDrawable.cpp \
    pipeline/skia/RenderNodeDrawable.cpp \
    pipeline/skia/ReorderBarrierDrawables.cpp \
    pipeline/skia/SkiaDisplayList.cpp \
    pipeline/skia/SkiaOpenGLPipeline.cpp \
    pipeline/skia/SkiaOpenGLReadback.cpp \
    pipeline/skia/SkiaPipeline.cpp \
    pipeline/skia/SkiaProfileRenderer.cpp \
    pipeline/skia/SkiaRecordingCanvas.cpp \
    pipeline/skia/SkiaVulkanPipeline.cpp \
    renderstate/Blend.cpp \
    renderstate/MeshState.cpp \
    renderstate/OffscreenBufferPool.cpp \
    renderstate/PixelBufferState.cpp \
    renderstate/RenderState.cpp \
    renderstate/Scissor.cpp \
    renderstate/Stencil.cpp \
    renderstate/TextureState.cpp \
    renderthread/CanvasContext.cpp \
    renderthread/OpenGLPipeline.cpp \
    renderthread/DrawFrameTask.cpp \
    renderthread/EglManager.cpp \
    renderthread/VulkanManager.cpp \
    renderthread/RenderProxy.cpp \
    renderthread/RenderTask.cpp \
    renderthread/RenderThread.cpp \
    renderthread/TimeLord.cpp \
    renderthread/Frame.cpp \
    service/GraphicsStatsService.cpp \
    thread/TaskManager.cpp \
    utils/Blur.cpp \
    utils/Color.cpp \
    utils/GLUtils.cpp \
    utils/LinearAllocator.cpp \
    utils/StringUtils.cpp \
    utils/TestWindowContext.cpp \
    utils/VectorDrawableUtils.cpp \
    AmbientShadow.cpp \
    AnimationContext.cpp \
    Animator.cpp \
    AnimatorManager.cpp \
    BakedOpDispatcher.cpp \
    BakedOpRenderer.cpp \
    BakedOpState.cpp \
    Caches.cpp \
    CanvasState.cpp \
    ClipArea.cpp \
    DamageAccumulator.cpp \
    DeferredLayerUpdater.cpp \
    DeviceInfo.cpp \
    DisplayList.cpp \
    Extensions.cpp \
    FboCache.cpp \
    FontRenderer.cpp \
    FrameBuilder.cpp \
    FrameInfo.cpp \
    FrameInfoVisualizer.cpp \
    GammaFontRenderer.cpp \
    GlLayer.cpp \
    GlopBuilder.cpp \
    GpuMemoryTracker.cpp \
    GradientCache.cpp \
    Image.cpp \
    Interpolator.cpp \
    JankTracker.cpp \
    Layer.cpp \
    LayerBuilder.cpp \
    LayerUpdateQueue.cpp \
    Matrix.cpp \
    OpDumper.cpp \
    OpenGLReadback.cpp \
    Patch.cpp \
    PatchCache.cpp \
    PathCache.cpp \
    PathParser.cpp \
    PathTessellator.cpp \
    PixelBuffer.cpp \
    ProfileRenderer.cpp \
    Program.cpp \
    ProgramCache.cpp \
    Properties.cpp \
    PropertyValuesAnimatorSet.cpp \
    PropertyValuesHolder.cpp \
    RecordingCanvas.cpp \
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
    VkLayer.cpp \
    protos/hwui.proto

hwui_test_common_src_files := \
    $(call all-cpp-files-under, tests/common/scenes) \
    tests/common/LeakChecker.cpp \
    tests/common/TestListViewSceneBase.cpp \
    tests/common/TestContext.cpp \
    tests/common/TestScene.cpp \
    tests/common/TestUtils.cpp

hwui_debug_common_src_files := \
    debug/wrap_gles.cpp \
    debug/DefaultGlesDriver.cpp \
    debug/GlesErrorCheckWrapper.cpp \
    debug/GlesDriver.cpp \
    debug/FatalBaseDriver.cpp \
    debug/NullGlesDriver.cpp

hwui_cflags := \
    -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES \
    -DATRACE_TAG=ATRACE_TAG_VIEW -DLOG_TAG=\"OpenGLRenderer\" \
    -Wall -Wno-unused-parameter -Wunreachable-code -Werror

ifeq ($(TARGET_USES_HWC2),true)
    hwui_cflags += -DUSE_HWC2
endif

# TODO: Linear blending should be enabled by default, but we are
# TODO: making it an opt-in while it's a work in progress
# TODO: The final test should be:
# TODO: ifneq ($(TARGET_ENABLE_LINEAR_BLENDING),false)
ifeq ($(TARGET_ENABLE_LINEAR_BLENDING),true)
    hwui_cflags += -DANDROID_ENABLE_LINEAR_BLENDING
endif

# GCC false-positives on this warning, and since we -Werror that's
# a problem
hwui_cflags += -Wno-free-nonheap-object

# clang's warning is broken, see: https://llvm.org/bugs/show_bug.cgi?id=21629
hwui_cflags += -Wno-missing-braces

ifeq (true, $(BUGREPORT_FONT_CACHE_USAGE))
    hwui_src_files += \
        font/FontCacheHistoryTracker.cpp
    hwui_cflags += -DBUGREPORT_FONT_CACHE_USAGE
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
    external/skia/src/effects \
    external/skia/src/image \
    external/skia/src/utils \
    external/icu/icu4c/source/common \
    external/harfbuzz_ng/src \
    external/freetype/include

# enable RENDERSCRIPT
hwui_c_includes += \
    $(call intermediates-dir-for,STATIC_LIBRARIES,TARGET,) \
    frameworks/rs/cpp \
    frameworks/rs

# ------------------------
# static library
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := STATIC_LIBRARIES
LOCAL_MODULE := libhwui_static
LOCAL_CFLAGS := $(hwui_cflags)
LOCAL_SRC_FILES := $(hwui_src_files)

ifeq (true, $(HWUI_ENABLE_OPENGL_VALIDATION))
    LOCAL_CFLAGS += -include debug/wrap_gles.h
    LOCAL_CFLAGS += -DDEBUG_OPENGL=3
    LOCAL_SRC_FILES += $(hwui_debug_common_src_files)
endif

LOCAL_C_INCLUDES := $(hwui_c_includes) $(call hwui_proto_include)
LOCAL_EXPORT_C_INCLUDE_DIRS := \
        $(LOCAL_PATH) \
        $(call hwui_proto_include)

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_STATIC_LIBRARY)

# ------------------------
# static library null gpu
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_CLASS := STATIC_LIBRARIES
LOCAL_MODULE := libhwui_static_debug
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -include debug/wrap_gles.h \
        -DHWUI_NULL_GPU
LOCAL_SRC_FILES := \
        $(hwui_src_files) \
        $(hwui_debug_common_src_files) \
        debug/nullegl.cpp
LOCAL_C_INCLUDES := $(hwui_c_includes) $(call hwui_proto_include)
LOCAL_EXPORT_C_INCLUDE_DIRS := \
        $(LOCAL_PATH) \
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
LOCAL_STATIC_LIBRARIES := libgmock libhwui_static_debug
LOCAL_SHARED_LIBRARIES := libmemunreachable
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -include debug/wrap_gles.h \
        -DHWUI_NULL_GPU
LOCAL_C_INCLUDES := $(hwui_c_includes)

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/unit/main.cpp \
    tests/unit/BakedOpDispatcherTests.cpp \
    tests/unit/BakedOpRendererTests.cpp \
    tests/unit/BakedOpStateTests.cpp \
    tests/unit/BitmapTests.cpp \
    tests/unit/CanvasContextTests.cpp \
    tests/unit/CanvasStateTests.cpp \
    tests/unit/ClipAreaTests.cpp \
    tests/unit/DamageAccumulatorTests.cpp \
    tests/unit/DeferredLayerUpdaterTests.cpp \
    tests/unit/DeviceInfoTests.cpp \
    tests/unit/FatVectorTests.cpp \
    tests/unit/FontRendererTests.cpp \
    tests/unit/FrameBuilderTests.cpp \
    tests/unit/GlopBuilderTests.cpp \
    tests/unit/GpuMemoryTrackerTests.cpp \
    tests/unit/GradientCacheTests.cpp \
    tests/unit/GraphicsStatsServiceTests.cpp \
    tests/unit/LayerUpdateQueueTests.cpp \
    tests/unit/LeakCheckTests.cpp \
    tests/unit/LinearAllocatorTests.cpp \
    tests/unit/MatrixTests.cpp \
    tests/unit/MeshStateTests.cpp \
    tests/unit/OffscreenBufferPoolTests.cpp \
    tests/unit/OpDumperTests.cpp \
    tests/unit/PathInterpolatorTests.cpp \
    tests/unit/RenderNodeDrawableTests.cpp \
    tests/unit/RecordingCanvasTests.cpp \
    tests/unit/RenderNodeTests.cpp \
    tests/unit/RenderPropertiesTests.cpp \
    tests/unit/SkiaBehaviorTests.cpp \
    tests/unit/SkiaDisplayListTests.cpp \
    tests/unit/SkiaPipelineTests.cpp \
    tests/unit/SkiaRenderPropertiesTests.cpp \
    tests/unit/SkiaCanvasTests.cpp \
    tests/unit/SnapshotTests.cpp \
    tests/unit/StringUtilsTests.cpp \
    tests/unit/TestUtilsTests.cpp \
    tests/unit/TextDropShadowCacheTests.cpp \
    tests/unit/TextureCacheTests.cpp \
    tests/unit/TypefaceTests.cpp \
    tests/unit/VectorDrawableTests.cpp \

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_NATIVE_TEST)

# ------------------------
# Macro-bench app
# ------------------------

include $(CLEAR_VARS)

LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/local/tmp
LOCAL_MODULE:= hwuimacro
LOCAL_MODULE_TAGS := tests
LOCAL_MULTILIB := both
LOCAL_CFLAGS := $(hwui_cflags)
LOCAL_C_INCLUDES := $(hwui_c_includes)

# set to libhwui_static_debug to skip actual GL commands
LOCAL_WHOLE_STATIC_LIBRARIES := libhwui_static
LOCAL_SHARED_LIBRARIES := libmemunreachable

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/macrobench/TestSceneRunner.cpp \
    tests/macrobench/main.cpp

include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_NATIVE_BENCHMARK)

# ------------------------
# Micro-bench app
# ---------------------
include $(CLEAR_VARS)

LOCAL_MODULE:= hwuimicro
LOCAL_MODULE_TAGS := tests
LOCAL_CFLAGS := \
        $(hwui_cflags) \
        -include debug/wrap_gles.h \
        -DHWUI_NULL_GPU

LOCAL_C_INCLUDES := $(hwui_c_includes)

LOCAL_WHOLE_STATIC_LIBRARIES := libhwui_static_debug
LOCAL_SHARED_LIBRARIES := libmemunreachable

LOCAL_SRC_FILES += \
    $(hwui_test_common_src_files) \
    tests/microbench/main.cpp \
    tests/microbench/DisplayListCanvasBench.cpp \
    tests/microbench/FontBench.cpp \
    tests/microbench/FrameBuilderBench.cpp \
    tests/microbench/LinearAllocatorBench.cpp \
    tests/microbench/PathParserBench.cpp \
    tests/microbench/RenderNodeBench.cpp \
    tests/microbench/ShadowBench.cpp \
    tests/microbench/TaskManagerBench.cpp


include $(LOCAL_PATH)/hwui_static_deps.mk
include $(BUILD_NATIVE_BENCHMARK)
