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

#ifndef ANDROID_UI_SKIA_SHADER_H
#define ANDROID_UI_SKIA_SHADER_H

#include <SkShader.h>
#include <SkXfermode.h>

#include <GLES2/gl2.h>

#include "Extensions.h"
#include "ProgramCache.h"
#include "TextureCache.h"
#include "GradientCache.h"
#include "Snapshot.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Base shader
///////////////////////////////////////////////////////////////////////////////

/**
 * Represents a Skia shader. A shader will modify the GL context and active
 * program to recreate the original effect.
 */
struct SkiaShader {
    /**
     * Type of Skia shader in use.
     */
    enum Type {
        kNone,
        kBitmap,
        kLinearGradient,
        kCircularGradient,
        kSweepGradient,
        kCompose
    };

    SkiaShader(Type type, SkShader* key, SkShader::TileMode tileX, SkShader::TileMode tileY,
            SkMatrix* matrix, bool blend);
    virtual ~SkiaShader();

    virtual void describe(ProgramDescription& description, const Extensions& extensions);
    virtual void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

    inline bool blend() const {
        return mBlend;
    }

    Type type() const {
        return mType;
    }

    virtual void set(TextureCache* textureCache, GradientCache* gradientCache) {
        mTextureCache = textureCache;
        mGradientCache = gradientCache;
    }

    void setMatrix(SkMatrix* matrix) {
        mMatrix = matrix;
    }

protected:
    inline void bindTexture(GLuint texture, GLenum wrapS, GLenum wrapT, GLuint textureUnit);

    Type mType;
    SkShader* mKey;
    SkShader::TileMode mTileX;
    SkShader::TileMode mTileY;
    SkMatrix* mMatrix;
    bool mBlend;

    TextureCache* mTextureCache;
    GradientCache* mGradientCache;
}; // struct SkiaShader


///////////////////////////////////////////////////////////////////////////////
// Implementations
///////////////////////////////////////////////////////////////////////////////

/**
 * A shader that draws a bitmap.
 */
struct SkiaBitmapShader: public SkiaShader {
    SkiaBitmapShader(SkBitmap* bitmap, SkShader* key, SkShader::TileMode tileX,
            SkShader::TileMode tileY, SkMatrix* matrix, bool blend);

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

private:
    /**
     * This method does not work for n == 0.
     */
    inline bool isPowerOfTwo(unsigned int n) {
        return !(n & (n - 1));
    }

    SkBitmap* mBitmap;
}; // struct SkiaBitmapShader

/**
 * A shader that draws a linear gradient.
 */
struct SkiaLinearGradientShader: public SkiaShader {
    SkiaLinearGradientShader(float* bounds, uint32_t* colors, float* positions, int count,
            SkShader* key, SkShader::TileMode tileMode, SkMatrix* matrix, bool blend);
    ~SkiaLinearGradientShader();

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

private:
    float* mBounds;
    uint32_t* mColors;
    float* mPositions;
    int mCount;
}; // struct SkiaLinearGradientShader

/**
 * A shader that draws two shaders, composited with an xfermode.
 */
struct SkiaComposeShader: public SkiaShader {
    SkiaComposeShader(SkiaShader* first, SkiaShader* second, SkXfermode::Mode mode, SkShader* key);

    void set(TextureCache* textureCache, GradientCache* gradientCache);

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

private:
    SkiaShader* mFirst;
    SkiaShader* mSecond;
    SkXfermode::Mode mMode;
}; // struct SkiaComposeShader

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_SKIA_SHADER_H
