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

#ifndef ANDROID_UI_PROGRAM_CACHE_H
#define ANDROID_UI_PROGRAM_CACHE_H

#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <GLES2/gl2.h>

#include <SkXfermode.h>

#include "Program.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#define DEBUG_PROGRAM_CACHE 1

// Debug
#if DEBUG_PROGRAM_CACHE
    #define PROGRAM_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define PROGRAM_LOGD(...)
#endif

#define PROGRAM_KEY_TEXTURE 0x1
#define PROGRAM_KEY_A8_TEXTURE 0x2
#define PROGRAM_KEY_BITMAP 0x4
#define PROGRAM_KEY_GRADIENT 0x8
#define PROGRAM_KEY_BITMAP_FIRST 0x10
#define PROGRAM_KEY_COLOR_MATRIX 0x20
#define PROGRAM_KEY_COLOR_LIGHTING 0x40
#define PROGRAM_KEY_COLOR_BLEND 0x80
#define PROGRAM_KEY_BITMAP_NPOT 0x100

#define PROGRAM_KEY_BITMAP_WRAPS_MASK 0x600
#define PROGRAM_KEY_BITMAP_WRAPT_MASK 0x1800

// Encode the xfermodes on 6 bits
#define PROGRAM_MAX_XFERMODE 0x1f
#define PROGRAM_XFERMODE_SHADER_SHIFT 26
#define PROGRAM_XFERMODE_COLOR_OP_SHIFT 20

#define PROGRAM_BITMAP_WRAPS_SHIFT 9
#define PROGRAM_BITMAP_WRAPT_SHIFT 11

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef uint32_t programid;

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

    ProgramDescription():
        hasTexture(false), hasAlpha8Texture(false),
        hasBitmap(false), isBitmapNpot(false), hasGradient(false),
        shadersMode(SkXfermode::kClear_Mode), isBitmapFirst(false),
        bitmapWrapS(GL_CLAMP_TO_EDGE), bitmapWrapT(GL_CLAMP_TO_EDGE),
        colorOp(kColorNone), colorMode(SkXfermode::kClear_Mode) {
    }

    // Texturing
    bool hasTexture;
    bool hasAlpha8Texture;

    // Shaders
    bool hasBitmap;
    bool isBitmapNpot;
    bool hasGradient;
    SkXfermode::Mode shadersMode;
    bool isBitmapFirst;
    GLenum bitmapWrapS;
    GLenum bitmapWrapT;

    // Color operations
    int colorOp;
    SkXfermode::Mode colorMode;

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
        if (isBitmapFirst) key  |= PROGRAM_KEY_BITMAP_FIRST;
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
        return key;
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

#endif // ANDROID_UI_PROGRAM_CACHE_H
