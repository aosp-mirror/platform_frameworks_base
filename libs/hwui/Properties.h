/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_HWUI_PROPERTIES_H
#define ANDROID_HWUI_PROPERTIES_H

#include <cutils/compiler.h>

/**
 * This file contains the list of system properties used to configure libhwui.
 */

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Compile-time properties
///////////////////////////////////////////////////////////////////////////////

// Textures used by layers must have dimensions multiples of this number
#define LAYER_SIZE 64

// Defines the size in bits of the stencil buffer for the framebuffer
// Note: Only 1 bit is required for clipping but more bits are required
// to properly implement overdraw debugging
#define STENCIL_BUFFER_SIZE 8

///////////////////////////////////////////////////////////////////////////////
// Debug properties
///////////////////////////////////////////////////////////////////////////////

/**
 * Debug level for app developers. The value is a numeric value defined
 * by the DebugLevel enum below.
 */
#define PROPERTY_DEBUG "debug.hwui.level"

/**
 * Debug levels. Debug levels are used as flags.
 */
enum DebugLevel {
    kDebugDisabled = 0,
    kDebugMemory = 1,
    kDebugCaches = 2,
    kDebugMoreCaches = kDebugMemory | kDebugCaches
};

/**
 * Used to enable/disable layers update debugging. The accepted values are
 * "true" and "false". The default value is "false".
 */
#define PROPERTY_DEBUG_LAYERS_UPDATES "debug.hwui.show_layers_updates"

/**
 * Used to enable/disable overdraw debugging.
 *
 * The accepted values are
 * "show", to show overdraw
 * "show_deuteranomaly", to show overdraw if you suffer from Deuteranomaly
 * "count", to show an overdraw counter
 * "false", to disable overdraw debugging
 *
 * The default value is "false".
 */
#define PROPERTY_DEBUG_OVERDRAW "debug.hwui.overdraw"

/**
 *  System property used to enable or disable hardware rendering profiling.
 * The default value of this property is assumed to be false.
 *
 * When profiling is enabled, the adb shell dumpsys gfxinfo command will
 * output extra information about the time taken to execute by the last
 * frames.
 *
 * Possible values:
 * "true", to enable profiling
 * "visual_bars", to enable profiling and visualize the results on screen
 * "false", to disable profiling
 */
#define PROPERTY_PROFILE "debug.hwui.profile"
#define PROPERTY_PROFILE_VISUALIZE_BARS "visual_bars"

/**
 * Turn on to draw dirty regions every other frame.
 *
 * Possible values:
 * "true", to enable dirty regions debugging
 * "false", to disable dirty regions debugging
 */
#define PROPERTY_DEBUG_SHOW_DIRTY_REGIONS "debug.hwui.show_dirty_regions"

/**
 * Setting this property will enable or disable the dropping of frames with
 * empty damage. Default is "true".
 */
#define PROPERTY_SKIP_EMPTY_DAMAGE "debug.hwui.skip_empty_damage"

/**
 * Controls whether or not HWUI will use the EGL_EXT_buffer_age extension
 * to do partial invalidates. Setting this to "false" will fall back to
 * using BUFFER_PRESERVED instead
 * Default is "true"
 */
#define PROPERTY_USE_BUFFER_AGE "debug.hwui.use_buffer_age"

/**
 * Setting this to "false" will force HWUI to always do full-redraws of the surface.
 * This will disable the use of EGL_EXT_buffer_age and BUFFER_PRESERVED.
 * Default is "true"
 */
#define PROPERTY_ENABLE_PARTIAL_UPDATES "debug.hwui.use_partial_updates"

#define PROPERTY_FILTER_TEST_OVERHEAD "debug.hwui.filter_test_overhead"

/**
 * Indicates whether PBOs can be used to back pixel buffers.
 * Accepted values are "true" and "false". Default is true.
 */
#define PROPERTY_ENABLE_GPU_PIXEL_BUFFERS "debug.hwui.use_gpu_pixel_buffers"

/**
 * Allows to set rendering pipeline mode to OpenGL (default), Skia OpenGL
 * or Vulkan.
 */
#define PROPERTY_RENDERER "debug.hwui.renderer"

/**
 * Allows to collect a recording of Skia drawing commands.
 */
#define PROPERTY_CAPTURE_SKP_ENABLED "debug.hwui.capture_skp_enabled"

/**
 * Allows to record Skia drawing commands with systrace.
 */
#define PROPERTY_SKIA_ATRACE_ENABLED "debug.hwui.skia_atrace_enabled"

/**
 * Defines how many frames in a sequence to capture.
 */
#define PROPERTY_CAPTURE_SKP_FRAMES "debug.hwui.capture_skp_frames"

/**
 * File name and location, where a SKP recording will be saved.
 */
#define PROPERTY_CAPTURE_SKP_FILENAME "debug.hwui.skp_filename"

/**
 * Controls whether HWUI will send timing hints to HintManager for
 * better CPU scheduling. Accepted values are "true" and "false".
 */
#define PROPERTY_USE_HINT_MANAGER "debug.hwui.use_hint_manager"

/**
 * Percentage of frame time that's used for CPU work. The rest is
 * reserved for GPU work. This is used with use_hint_manager to
 * provide timing hints to HintManager. Accepted values are
 * integer from 1-100.
 */
#define PROPERTY_TARGET_CPU_TIME_PERCENTAGE "debug.hwui.target_cpu_time_percent"

/**
 * Property for whether this is running in the emulator.
 */
#define PROPERTY_IS_EMULATOR "ro.boot.qemu"

/**
 * Turns on the Skia GPU option "reduceOpsTaskSplitting" which improves GPU
 * efficiency but may increase VRAM consumption. Default is "true".
 */
#define PROPERTY_REDUCE_OPS_TASK_SPLITTING "renderthread.skia.reduceopstasksplitting"

/**
 * Enable WebView Overlays feature.
 */
#define PROPERTY_WEBVIEW_OVERLAYS_ENABLED "debug.hwui.webview_overlays_enabled"

///////////////////////////////////////////////////////////////////////////////
// Misc
///////////////////////////////////////////////////////////////////////////////

// Converts a number of mega-bytes into bytes
#define MB(s) ((s)*1024 * 1024)
// Converts a number of kilo-bytes into bytes
#define KB(s) ((s)*1024)

enum class ProfileType { None, Console, Bars };

enum class OverdrawColorSet { Default = 0, Deuteranomaly };

enum class RenderPipelineType { SkiaGL, SkiaVulkan, NotInitialized = 128 };

enum class StretchEffectBehavior {
    ShaderHWUI,   // Stretch shader in HWUI only, matrix scale in SF
    Shader,       // Stretch shader in both HWUI and SF
    UniformScale  // Uniform scale stretch everywhere
};

/**
 * Renderthread-only singleton which manages several static rendering properties. Most of these
 * are driven by system properties which are queried once at initialization, and again if init()
 * is called.
 */
class Properties {
public:
    static bool load();

    static bool debugLayersUpdates;
    static bool debugOverdraw;
    static bool showDirtyRegions;
    // TODO: Remove after stabilization period
    static bool skipEmptyFrames;
    static bool useBufferAge;
    static bool enablePartialUpdates;
    static bool enableRenderEffectCache;

    // TODO: Move somewhere else?
    static constexpr float textGamma = 1.45f;

    static DebugLevel debugLevel;
    static OverdrawColorSet overdrawColorSet;

    // Override the value for a subset of properties in this class
    static void overrideProperty(const char* name, const char* value);

    static float overrideLightRadius;
    static float overrideLightPosY;
    static float overrideLightPosZ;
    static float overrideAmbientRatio;
    static int overrideAmbientShadowStrength;
    static int overrideSpotShadowStrength;

    static ProfileType getProfileType();
    static RenderPipelineType peekRenderPipelineType();
    static RenderPipelineType getRenderPipelineType();

    static bool enableHighContrastText;

    // Should be used only by test apps
    static bool waitForGpuCompletion;
    static bool forceDrawFrame;

    // Should only be set by automated tests to try and filter out
    // any overhead they add
    static bool filterOutTestOverhead;

    // Workaround a device lockup in edge cases by switching to async mode
    // instead of the default vsync (b/38372997). Only system_server should hit this.
    // Any existing RenderProxy & Surface combination will be unaffected, only things
    // created after changing this.
    static bool disableVsync;

    static bool skpCaptureEnabled;

    // For experimentation b/68769804
    static bool enableRTAnimations;

    // Used for testing only to change the render pipeline.
    static void overrideRenderPipelineType(RenderPipelineType, bool inUnitTest = false);

    static bool runningInEmulator;

    static bool debuggingEnabled;
    static bool isolatedProcess;

    static int contextPriority;

    static float defaultSdrWhitePoint;

    static bool useHintManager;
    static int targetCpuTimePercentage;

    static bool enableWebViewOverlays;

    static StretchEffectBehavior getStretchEffectBehavior() {
        return stretchEffectBehavior;
    }

    static void setIsHighEndGfx(bool isHighEndGfx) {
        stretchEffectBehavior = isHighEndGfx ?
            StretchEffectBehavior::ShaderHWUI :
            StretchEffectBehavior::UniformScale;
    }

    /**
     * Used for testing. Typical configuration of stretch behavior is done
     * through setIsHighEndGfx
     */
    static void setStretchEffectBehavior(StretchEffectBehavior behavior) {
        stretchEffectBehavior = behavior;
    }

private:
    static StretchEffectBehavior stretchEffectBehavior;
    static ProfileType sProfileType;
    static bool sDisableProfileBars;
    static RenderPipelineType sRenderPipelineType;
};  // class Caches

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_PROPERTIES_H
