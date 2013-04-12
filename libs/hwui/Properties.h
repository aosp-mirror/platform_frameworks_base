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
#include <stdlib.h>

/**
 * This file contains the list of system properties used to configure
 * the OpenGLRenderer.
 */

///////////////////////////////////////////////////////////////////////////////
// Compile-time properties
///////////////////////////////////////////////////////////////////////////////

// If turned on, text is interpreted as glyphs instead of UTF-16
#define RENDER_TEXT_AS_GLYPHS 1

// Indicates whether to remove the biggest layers first, or the smaller ones
#define LAYER_REMOVE_BIGGEST_FIRST 0

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
 * Used to enable/disable overdraw debugging. The accepted values are
 * "true" and "false". The default value is "false".
 */
#define PROPERTY_DEBUG_OVERDRAW "debug.hwui.show_overdraw"

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
 * Accepted values are "true" and "false".
 */
#define PROPERTY_ENABLE_GPU_PIXEL_BUFFERS "hwui.use_gpu_pixel_buffers"

// These properties are defined in mega-bytes
#define PROPERTY_TEXTURE_CACHE_SIZE "ro.hwui.texture_cache_size"
#define PROPERTY_LAYER_CACHE_SIZE "ro.hwui.layer_cache_size"
#define PROPERTY_RENDER_BUFFER_CACHE_SIZE "ro.hwui.r_buffer_cache_size"
#define PROPERTY_GRADIENT_CACHE_SIZE "ro.hwui.gradient_cache_size"
#define PROPERTY_PATH_CACHE_SIZE "ro.hwui.path_cache_size"
#define PROPERTY_DROP_SHADOW_CACHE_SIZE "ro.hwui.drop_shadow_cache_size"
#define PROPERTY_FBO_CACHE_SIZE "ro.hwui.fbo_cache_size"

// These properties are defined in percentage (range 0..1)
#define PROPERTY_TEXTURE_CACHE_FLUSH_RATE "ro.hwui.texture_cache_flushrate"

// These properties are defined in pixels
#define PROPERTY_TEXT_SMALL_CACHE_WIDTH "ro.hwui.text_small_cache_width"
#define PROPERTY_TEXT_SMALL_CACHE_HEIGHT "ro.hwui.text_small_cache_height"
#define PROPERTY_TEXT_LARGE_CACHE_WIDTH "ro.hwui.text_large_cache_width"
#define PROPERTY_TEXT_LARGE_CACHE_HEIGHT "ro.hwui.text_large_cache_height"

// Indicates whether gamma correction should be applied in the shaders
// or in lookup tables. Accepted values:
//
//     - "lookup3", correction based on lookup tables. Gamma correction
//        is different for black and white text (see thresholds below)
//
//     - "lookup", correction based on a single lookup table
//
//     - "shader3", correction applied by a GLSL shader. Gamma correction
//        is different for black and white text (see thresholds below)
//
//     - "shader", correction applied by a GLSL shader
//
// See PROPERTY_TEXT_GAMMA, PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD and
// PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD for more control.
#define PROPERTY_TEXT_GAMMA_METHOD "hwui.text_gamma_correction"
#define DEFAULT_TEXT_GAMMA_METHOD "lookup"

// Gamma (>= 1.0, <= 10.0)
#define PROPERTY_TEXT_GAMMA "hwui.text_gamma"
// Luminance threshold below which black gamma correction is applied. Range: [0..255]
#define PROPERTY_TEXT_BLACK_GAMMA_THRESHOLD "hwui.text_gamma.black_threshold"
// Lumincance threshold above which white gamma correction is applied. Range: [0..255]
#define PROPERTY_TEXT_WHITE_GAMMA_THRESHOLD "hwui.text_gamma.white_threshold"

///////////////////////////////////////////////////////////////////////////////
// Default property values
///////////////////////////////////////////////////////////////////////////////

#define DEFAULT_TEXTURE_CACHE_SIZE 24.0f
#define DEFAULT_LAYER_CACHE_SIZE 16.0f
#define DEFAULT_RENDER_BUFFER_CACHE_SIZE 2.0f
#define DEFAULT_PATH_CACHE_SIZE 10.0f
#define DEFAULT_PATCH_CACHE_SIZE 512
#define DEFAULT_GRADIENT_CACHE_SIZE 0.5f
#define DEFAULT_DROP_SHADOW_CACHE_SIZE 2.0f
#define DEFAULT_FBO_CACHE_SIZE 16

#define DEFAULT_TEXTURE_CACHE_FLUSH_RATE 0.6f

#define DEFAULT_TEXT_GAMMA 1.4f
#define DEFAULT_TEXT_BLACK_GAMMA_THRESHOLD 64
#define DEFAULT_TEXT_WHITE_GAMMA_THRESHOLD 192

///////////////////////////////////////////////////////////////////////////////
// Misc
///////////////////////////////////////////////////////////////////////////////

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024

static DebugLevel readDebugLevel() {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_DEBUG, property, NULL) > 0) {
        return (DebugLevel) atoi(property);
    }
    return kDebugDisabled;
}

#endif // ANDROID_HWUI_PROPERTIES_H
