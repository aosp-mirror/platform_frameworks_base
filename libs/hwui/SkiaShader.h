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

#ifndef ANDROID_HWUI_SKIA_SHADER_H
#define ANDROID_HWUI_SKIA_SHADER_H

#include <SkShader.h>
#include <SkXfermode.h>

#include <GLES2/gl2.h>

#include <cutils/compiler.h>

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

    ANDROID_API SkiaShader(Type type, SkShader* key, SkShader::TileMode tileX,
            SkShader::TileMode tileY, SkMatrix* matrix, bool blend);
    virtual ~SkiaShader();

    virtual SkiaShader* copy() = 0;
    void copyFrom(const SkiaShader& shader);

    virtual void describe(ProgramDescription& description, const Extensions& extensions);
    virtual void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

    inline SkShader *getSkShader() {
        return mKey;
    }

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

    virtual void updateTransforms(Program* program, const mat4& modelView,
            const Snapshot& snapshot) {
    }

    uint32_t getGenerationId() {
        return mGenerationId;
    }

    void setMatrix(SkMatrix* matrix) {
        updateLocalMatrix(matrix);
        mGenerationId++;
    }

    void updateLocalMatrix(const SkMatrix* matrix) {
        if (matrix) {
            mat4 localMatrix(*matrix);
            mShaderMatrix.loadInverse(localMatrix);
        } else {
            mShaderMatrix.loadIdentity();
        }
    }

    void computeScreenSpaceMatrix(mat4& screenSpace, const mat4& modelView);

protected:
    SkiaShader() {
    }

    /**
     * The appropriate texture unit must have been activated prior to invoking
     * this method.
     */
    inline void bindTexture(Texture* texture, GLenum wrapS, GLenum wrapT);

    Type mType;
    SkShader* mKey;
    SkShader::TileMode mTileX;
    SkShader::TileMode mTileY;
    bool mBlend;

    TextureCache* mTextureCache;
    GradientCache* mGradientCache;

    mat4 mUnitMatrix;
    mat4 mShaderMatrix;

private:
    uint32_t mGenerationId;
}; // struct SkiaShader


///////////////////////////////////////////////////////////////////////////////
// Implementations
///////////////////////////////////////////////////////////////////////////////

/**
 * A shader that draws a bitmap.
 */
struct SkiaBitmapShader: public SkiaShader {
    ANDROID_API SkiaBitmapShader(SkBitmap* bitmap, SkShader* key, SkShader::TileMode tileX,
            SkShader::TileMode tileY, SkMatrix* matrix, bool blend);
    SkiaShader* copy();

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);
    void updateTransforms(Program* program, const mat4& modelView, const Snapshot& snapshot);

private:
    SkiaBitmapShader() {
    }

    /**
     * This method does not work for n == 0.
     */
    inline bool isPowerOfTwo(unsigned int n) {
        return !(n & (n - 1));
    }

    SkBitmap* mBitmap;
    Texture* mTexture;
    GLenum mWrapS;
    GLenum mWrapT;
}; // struct SkiaBitmapShader

/**
 * A shader that draws a linear gradient.
 */
struct SkiaLinearGradientShader: public SkiaShader {
    ANDROID_API SkiaLinearGradientShader(float* bounds, uint32_t* colors, float* positions,
            int count, SkShader* key, SkShader::TileMode tileMode, SkMatrix* matrix, bool blend);
    ~SkiaLinearGradientShader();
    SkiaShader* copy();

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);
    void updateTransforms(Program* program, const mat4& modelView, const Snapshot& snapshot);

private:
    SkiaLinearGradientShader() {
    }

    float* mBounds;
    uint32_t* mColors;
    float* mPositions;
    int mCount;
}; // struct SkiaLinearGradientShader

/**
 * A shader that draws a sweep gradient.
 */
struct SkiaSweepGradientShader: public SkiaShader {
    ANDROID_API SkiaSweepGradientShader(float x, float y, uint32_t* colors, float* positions,
            int count, SkShader* key, SkMatrix* matrix, bool blend);
    ~SkiaSweepGradientShader();
    SkiaShader* copy();

    virtual void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);
    void updateTransforms(Program* program, const mat4& modelView, const Snapshot& snapshot);

protected:
    SkiaSweepGradientShader(Type type, float x, float y, uint32_t* colors, float* positions,
            int count, SkShader* key, SkShader::TileMode tileMode, SkMatrix* matrix, bool blend);
    SkiaSweepGradientShader() {
    }

    uint32_t* mColors;
    float* mPositions;
    int mCount;
}; // struct SkiaSweepGradientShader

/**
 * A shader that draws a circular gradient.
 */
struct SkiaCircularGradientShader: public SkiaSweepGradientShader {
    ANDROID_API SkiaCircularGradientShader(float x, float y, float radius, uint32_t* colors,
            float* positions, int count, SkShader* key,SkShader::TileMode tileMode,
            SkMatrix* matrix, bool blend);
    SkiaShader* copy();

    void describe(ProgramDescription& description, const Extensions& extensions);

private:
    SkiaCircularGradientShader() {
    }
}; // struct SkiaCircularGradientShader

/**
 * A shader that draws two shaders, composited with an xfermode.
 */
struct SkiaComposeShader: public SkiaShader {
    ANDROID_API SkiaComposeShader(SkiaShader* first, SkiaShader* second, SkXfermode::Mode mode,
            SkShader* key);
    ~SkiaComposeShader();
    SkiaShader* copy();

    void set(TextureCache* textureCache, GradientCache* gradientCache);

    void describe(ProgramDescription& description, const Extensions& extensions);
    void setupProgram(Program* program, const mat4& modelView, const Snapshot& snapshot,
            GLuint* textureUnit);

private:
    SkiaComposeShader(): mCleanup(false) {
    }

    void cleanup() {
        mCleanup = true;
    }

    SkiaShader* mFirst;
    SkiaShader* mSecond;
    SkXfermode::Mode mMode;

    bool mCleanup;
}; // struct SkiaComposeShader

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SKIA_SHADER_H
