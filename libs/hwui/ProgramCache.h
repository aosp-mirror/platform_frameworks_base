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

#ifndef ANDROID_HWUI_PROGRAM_CACHE_H
#define ANDROID_HWUI_PROGRAM_CACHE_H

#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <GLES2/gl2.h>

#include <SkXfermode.h>

#include "Debug.h"
#include "Program.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_PROGRAMS
    #define PROGRAM_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define PROGRAM_LOGD(...)
#endif

// TODO: This should be set in properties
#define PANEL_BIT_DEPTH 20
#define COLOR_COMPONENT_THRESHOLD (1.0f - (0.5f / PANEL_BIT_DEPTH))
#define COLOR_COMPONENT_INV_THRESHOLD (0.5f / PANEL_BIT_DEPTH)

#define PROGRAM_KEY_TEXTURE 0x1
#define PROGRAM_KEY_A8_TEXTURE 0x2
#define PROGRAM_KEY_BITMAP 0x4
#define PROGRAM_KEY_GRADIENT 0x8
#define PROGRAM_KEY_BITMAP_FIRST 0x10
#define PROGRAM_KEY_COLOR_MATRIX 0x20
#define PROGRAM_KEY_COLOR_LIGHTING 0x40
#define PROGRAM_KEY_COLOR_BLEND 0x80
#define PROGRAM_KEY_BITMAP_NPOT 0x100
#define PROGRAM_KEY_SWAP_SRC_DST 0x2000

#define PROGRAM_KEY_BITMAP_WRAPS_MASK 0x600
#define PROGRAM_KEY_BITMAP_WRAPT_MASK 0x1800

// Encode the xfermodes on 6 bits
#define PROGRAM_MAX_XFERMODE 0x1f
#define PROGRAM_XFERMODE_SHADER_SHIFT 26
#define PROGRAM_XFERMODE_COLOR_OP_SHIFT 20
#define PROGRAM_XFERMODE_FRAMEBUFFER_SHIFT 14

#define PROGRAM_BITMAP_WRAPS_SHIFT 9
#define PROGRAM_BITMAP_WRAPT_SHIFT 11

#define PROGRAM_GRADIENT_TYPE_SHIFT 33
#define PROGRAM_MODULATE_SHIFT 35

#define PROGRAM_IS_POINT_SHIFT 36

#define PROGRAM_HAS_AA_SHIFT 37

#define PROGRAM_HAS_EXTERNAL_TEXTURE_SHIFT 38
#define PROGRAM_HAS_TEXTURE_TRANSFORM_SHIFT 39

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef uint64_t programid;

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

/**
 * Describe the features required for a given program. The features
 * determine the generation of both the vertex and fragment shaders.
 * A ProgramDescription must be used in conjunction with a ProgramCache.
 */
struct ProgramDescription {
    enum ColorModifier {
        kColorNone,
        kColorMatrix,
        kColorLighting,
        kColorBlend
    };

    enum Gradient {
        kGradientLinear,
        kGradientCircular,
        kGradientSweep
    };

    ProgramDescription() {
        reset();
    }

    // Texturing
    bool hasTexture;
    bool hasAlpha8Texture;
    bool hasExternalTexture;
    bool hasTextureTransform;

    // Modulate, this should only be set when setColor() return true
    bool modulate;

    // Shaders
    bool hasBitmap;
    bool isBitmapNpot;

    bool isAA;

    bool hasGradient;
    Gradient gradientType;

    SkXfermode::Mode shadersMode;

    bool isBitmapFirst;
    GLenum bitmapWrapS;
    GLenum bitmapWrapT;

    // Color operations
    ColorModifier colorOp;
    SkXfermode::Mode colorMode;

    // Framebuffer blending (requires Extensions.hasFramebufferFetch())
    // Ignored for all values < SkXfermode::kPlus_Mode
    SkXfermode::Mode framebufferMode;
    bool swapSrcDst;

    bool isPoint;
    float pointSize;

    /**
     * Resets this description. All fields are reset back to the default
     * values they hold after building a new instance.
     */
    void reset() {
        hasTexture = false;
        hasAlpha8Texture = false;
        hasExternalTexture = false;
        hasTextureTransform = false;

        isAA = false;

        modulate = false;

        hasBitmap = false;
        isBitmapNpot = false;

        hasGradient = false;
        gradientType = kGradientLinear;

        shadersMode = SkXfermode::kClear_Mode;

        isBitmapFirst = false;
        bitmapWrapS = GL_CLAMP_TO_EDGE;
        bitmapWrapT = GL_CLAMP_TO_EDGE;

        colorOp = kColorNone;
        colorMode = SkXfermode::kClear_Mode;

        framebufferMode = SkXfermode::kClear_Mode;
        swapSrcDst = false;

        isPoint = false;
        pointSize = 0.0f;
    }

    /**
     * Indicates, for a given color, whether color modulation is required in
     * the fragment shader. When this method returns true, the program should
     * be provided with a modulation color.
     */
    bool setColor(const float r, const float g, const float b, const float a) {
        modulate = a < COLOR_COMPONENT_THRESHOLD || r < COLOR_COMPONENT_THRESHOLD ||
                g < COLOR_COMPONENT_THRESHOLD || b < COLOR_COMPONENT_THRESHOLD;
        return modulate;
    }

    /**
     * Indicates, for a given color, whether color modulation is required in
     * the fragment shader. When this method returns true, the program should
     * be provided with a modulation color.
     */
    bool setAlpha8Color(const float r, const float g, const float b, const float a) {
        modulate = a < COLOR_COMPONENT_THRESHOLD || r > COLOR_COMPONENT_INV_THRESHOLD ||
                g > COLOR_COMPONENT_INV_THRESHOLD || b > COLOR_COMPONENT_INV_THRESHOLD;
        return modulate;
    }

    /**
     * Computes the unique key identifying this program.
     */
    programid key() const {
        programid key = 0;
        if (hasTexture) key |= PROGRAM_KEY_TEXTURE;
        if (hasAlpha8Texture) key |= PROGRAM_KEY_A8_TEXTURE;
        if (hasBitmap) {
            key |= PROGRAM_KEY_BITMAP;
            if (isBitmapNpot) {
                key |= PROGRAM_KEY_BITMAP_NPOT;
                key |= getEnumForWrap(bitmapWrapS) << PROGRAM_BITMAP_WRAPS_SHIFT;
                key |= getEnumForWrap(bitmapWrapT) << PROGRAM_BITMAP_WRAPT_SHIFT;
            }
        }
        if (hasGradient) key |= PROGRAM_KEY_GRADIENT;
        key |= programid(gradientType) << PROGRAM_GRADIENT_TYPE_SHIFT;
        if (isBitmapFirst) key |= PROGRAM_KEY_BITMAP_FIRST;
        if (hasBitmap && hasGradient) {
            key |= (shadersMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_SHADER_SHIFT;
        }
        switch (colorOp) {
            case kColorMatrix:
                key |= PROGRAM_KEY_COLOR_MATRIX;
                break;
            case kColorLighting:
                key |= PROGRAM_KEY_COLOR_LIGHTING;
                break;
            case kColorBlend:
                key |= PROGRAM_KEY_COLOR_BLEND;
                key |= (colorMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_COLOR_OP_SHIFT;
                break;
            case kColorNone:
                break;
        }
        key |= (framebufferMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_FRAMEBUFFER_SHIFT;
        if (swapSrcDst) key |= PROGRAM_KEY_SWAP_SRC_DST;
        if (modulate) key |= programid(0x1) << PROGRAM_MODULATE_SHIFT;
        if (isPoint) key |= programid(0x1) << PROGRAM_IS_POINT_SHIFT;
        if (isAA) key |= programid(0x1) << PROGRAM_HAS_AA_SHIFT;
        if (hasExternalTexture) key |= programid(0x1) << PROGRAM_HAS_EXTERNAL_TEXTURE_SHIFT;
        if (hasTextureTransform) key |= programid(0x1) << PROGRAM_HAS_TEXTURE_TRANSFORM_SHIFT;
        return key;
    }

    /**
     * Logs the specified message followed by the key identifying this program.
     */
    void log(const char* message) const {
#if DEBUG_PROGRAMS
        programid k = key();
        PROGRAM_LOGD("%s (key = 0x%.8x%.8x)", message, uint32_t(k >> 32),
                uint32_t(k & 0xffffffff));
#endif
    }

private:
    inline uint32_t getEnumForWrap(GLenum wrap) const {
        switch (wrap) {
            case GL_CLAMP_TO_EDGE:
                return 0;
            case GL_REPEAT:
                return 1;
            case GL_MIRRORED_REPEAT:
                return 2;
        }
        return 0;
    }

}; // struct ProgramDescription

/**
 * Generates and caches program. Programs are generated based on
 * ProgramDescriptions.
 */
class ProgramCache {
public:
    ProgramCache();
    ~ProgramCache();

    Program* get(const ProgramDescription& description);

    void clear();

private:
    Program* generateProgram(const ProgramDescription& description, programid key);
    String8 generateVertexShader(const ProgramDescription& description);
    String8 generateFragmentShader(const ProgramDescription& description);
    void generateBlend(String8& shader, const char* name, SkXfermode::Mode mode);
    void generateTextureWrap(String8& shader, GLenum wrapS, GLenum wrapT);

    void printLongString(const String8& shader) const;

    KeyedVector<programid, Program*> mCache;
}; // class ProgramCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PROGRAM_CACHE_H
