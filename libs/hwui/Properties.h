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

#include <cutils/properties.h>

/**
 * This file contains the list of system properties used to configure
 * the OpenGLRenderer.
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
 * Used to enable/disable non-rectangular clipping debugging.
 *
 * The accepted values are:
 * "highlight", drawing commands clipped by the stencil will
 *              be colored differently
 * "region", renders the clipping region on screen whenever
 *           the stencil is set
 * "hide", don't show the clip
 *
 * The default value is "hide".
 */
#define PROPERTY_DEBUG_STENCIL_CLIP "debug.hwui.show_non_rect_clip"

/**
 * Turn on to draw dirty regions every other frame.
 *
 * Possible values:
 * "true", to enable dirty regions debugging
 * "false", to disable dirty regions debugging
 */
#define PROPERTY_DEBUG_SHOW_DIRTY_REGIONS "debug.hwui.show_dirty_regions"

/**
 * Disables draw operation deferral if set to "true", forcing draw
 * commands to be issued to OpenGL in order, and processed in sequence
 * with state-manipulation canvas commands.
 */
#define PROPERTY_DISABLE_DRAW_DEFER "debug.hwui.disable_draw_defer"

/**
 * Used to disable draw operation reordering when deferring draw operations
 * Has no effect if PROPERTY_DISABLE_DRAW_DEFER is set to "true"
 */
#define PROPERTY_DISABLE_DRAW_REORDER "debug.hwui.disable_draw_reorder"

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
#define PROPERTY_ENABLE_PARTIAL_UPDATES "debug.hwui.enable_partial_updates"

#define PROPERTY_FILTER_TEST_OVERHEAD "debug.hwui.filter_test_overhead"

///////////////////////////////////////////////////////////////////////////////
// Runtime configuration properties
///////////////////////////////////////////////////////////////////////////////

/**
 * Used to enable/disable scissor optimization. The accepted values are
 * "true" and "false". The default value is "false".
 *
 * When scissor optimization is enabled, OpenGLRenderer will attempt to
 * minimize the use of scissor by selectively enabling and disabling the
 * GL scissor test.
 * When the optimization is disabled, OpenGLRenderer will keep the GL
 * scissor test enabled and change the scissor rect as needed.
 * Some GPUs (for instance the SGX 540) perform better when changing
 * the scissor rect often than when enabling/disabling the scissor test
 * often.
 */
#define PROPERTY_DISABLE_SCISSOR_OPTIMIZATION "ro.hwui.disable_scissor_opt"

/**
 * Indicates whether PBOs can be used to back pixel buffers.
 * Accepted values are "true" and "false". Default is true.
 */
#define PROPERTY_ENABLE_GPU_PIXEL_BUFFERS "ro.hwui.use_gpu_pixel_buffers"

// These properties are defined in mega-bytes
#define PROPERTY_TEXTURE_CACHE_SIZE "ro.hwui.texture_cache_size"
#define PROPERTY_LAYER_CACHE_SIZE "ro.hwui.layer_cache_size"
#define PROPERTY_RENDER_BUFFER_CACHE_SIZE "ro.hwui.r_buffer_cache_size"
#define PROPERTY_GRADIENT_CACHE_SIZE "ro.hwui.gradient_cache_size"
#define PROPERTY_PATH_CACHE_SIZE "ro.hwui.path_cache_size"
#define PROPERTY_VERTEX_CACHE_SIZE "ro.hwui.vertex_cache_size"
#define PROPERTY_PATCH_CACHE_SIZE "ro.hwui.patch_cache_size"
#define PROPERTY_DROP_SHADOW_CACHE_SIZE "ro.hwui.drop_shadow_cache_size"
#define PROPERTY_FBO_CACHE_SIZE "ro.hwui.fbo_cache_size"

// These properties are defined in percentage (range 0..1)
#define PROPERTY_TEXTURE_CACHE_FLUSH_RATE "ro.hwui.texture_cache_flushrate"

// These properties are defined in pixels
#define PROPERTY_TEXT_SMALL_CACHE_WIDTH "ro.hwui.text_small_cache_width"
#define PROPERTY_TEXT_SMALL_CACHE_HEIGHT "ro.hwui.text_small_cache_height"
#define PROPERTY_TEXT_LARGE_CACHE_WIDTH "ro.hwui.text_large_cache_width"
#define PROPERTY_TEXT_LARGE_CACHE_HEIGHT "ro.hwui.text_large_cache_height"

// Gamma (>= 1.0, <= 10.0)
#define PROPERTY_TEXT_GAMMA "hwui.text_gamma"

///////////////////////////////////////////////////////////////////////////////
// Default property values
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXTURE_CACHE_SIZE 24.0f
#define DEFAULT_LAYER_CACHE_SIZE 16.0f
#define DEFAULT_RENDER_BUFFER_CACHE_SIZE 2.0f
#define DEFAULT_PATH_CACHE_SIZE 4.0f
#define DEFAULT_VERTEX_CACHE_SIZE 1.0f
#define DEFAULT_PATCH_CACHE_SIZE 128.0f // in kB
#define DEFAULT_GRADIENT_CACHE_SIZE 0.5f
#define DEFAULT_DROP_SHADOW_CACHE_SIZE 2.0f
#define DEFAULT_FBO_CACHE_SIZE 0

#define DEFAULT_TEXTURE_CACHE_FLUSH_RATE 0.6f

#define DEFAULT_TEXT_GAMMA 1.4f

///////////////////////////////////////////////////////////////////////////////
// Misc
///////////////////////////////////////////////////////////////////////////////

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024
// Converts a number of kilo-bytes into bytes
#define KB(s) s * 1024

enum class ProfileType {
    None,
    Console,
    Bars
};

enum class OverdrawColorSet {
    Default = 0,
    Deuteranomaly
};

enum class StencilClipDebug {
    Hide,
    ShowHighlight,
    ShowRegion
};

/**
 * Renderthread-only singleton which manages several static rendering properties. Most of these
 * are driven by system properties which are queried once at initialization, and again if init()
 * is called.
 */
class Properties {
public:
    static bool load();

    static bool drawDeferDisabled;
    static bool drawReorderDisabled;
    static bool debugLayersUpdates;
    static bool debugOverdraw;
    static bool showDirtyRegions;
    // TODO: Remove after stabilization period
    static bool skipEmptyFrames;
    static bool useBufferAge;
    static bool enablePartialUpdates;

    static float textGamma;

    static int fboCacheSize;
    static int gradientCacheSize;
    static int layerPoolSize;
    static int patchCacheSize;
    static int pathCacheSize;
    static int renderBufferCacheSize;
    static int tessellationCacheSize;
    static int textDropShadowCacheSize;
    static int textureCacheSize;
    static float textureCacheFlushRate;

    static DebugLevel debugLevel;
    static OverdrawColorSet overdrawColorSet;
    static StencilClipDebug debugStencilClip;

    // Override the value for a subset of properties in this class
    static void overrideProperty(const char* name, const char* value);

    static float overrideLightRadius;
    static float overrideLightPosY;
    static float overrideLightPosZ;
    static float overrideAmbientRatio;
    static int overrideAmbientShadowStrength;
    static int overrideSpotShadowStrength;

    static ProfileType getProfileType();

    // Should be used only by test apps
    static bool waitForGpuCompletion;

    // Should only be set by automated tests to try and filter out
    // any overhead they add
    static bool filterOutTestOverhead;

private:
    static ProfileType sProfileType;
    static bool sDisableProfileBars;

}; // class Caches

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PROPERTIES_H
