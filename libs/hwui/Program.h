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

#ifndef ANDROID_HWUI_PROGRAM_H
#define ANDROID_HWUI_PROGRAM_H

#include <utils/KeyedVector.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <SkBlendMode.h>

#include "Debug.h"
#include "FloatColor.h"
#include "Matrix.h"
#include "Properties.h"
#include "utils/Color.h"

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

#define COLOR_COMPONENT_THRESHOLD 1.0f
#define COLOR_COMPONENT_INV_THRESHOLD 0.0f

#define PROGRAM_KEY_TEXTURE 0x01
#define PROGRAM_KEY_A8_TEXTURE 0x02
#define PROGRAM_KEY_BITMAP 0x04
#define PROGRAM_KEY_GRADIENT 0x08
#define PROGRAM_KEY_BITMAP_FIRST 0x10
#define PROGRAM_KEY_COLOR_MATRIX 0x20
#define PROGRAM_KEY_COLOR_BLEND 0x40
#define PROGRAM_KEY_BITMAP_NPOT 0x80
#define PROGRAM_KEY_BITMAP_EXTERNAL 0x100

#define PROGRAM_KEY_BITMAP_WRAPS_MASK 0x600
#define PROGRAM_KEY_BITMAP_WRAPT_MASK 0x1800

#define PROGRAM_KEY_SWAP_SRC_DST_SHIFT 13

// Encode the xfermodes on 6 bits
#define PROGRAM_MAX_XFERMODE 0x1f
#define PROGRAM_XFERMODE_SHADER_SHIFT 26
#define PROGRAM_XFERMODE_COLOR_OP_SHIFT 20
#define PROGRAM_XFERMODE_FRAMEBUFFER_SHIFT 14

#define PROGRAM_BITMAP_WRAPS_SHIFT 9
#define PROGRAM_BITMAP_WRAPT_SHIFT 11

#define PROGRAM_GRADIENT_TYPE_SHIFT 33  // 2 bits for gradient type
#define PROGRAM_MODULATE_SHIFT 35

#define PROGRAM_HAS_VERTEX_ALPHA_SHIFT 36
#define PROGRAM_USE_SHADOW_ALPHA_INTERP_SHIFT 37

#define PROGRAM_HAS_EXTERNAL_TEXTURE_SHIFT 38
#define PROGRAM_HAS_TEXTURE_TRANSFORM_SHIFT 39

#define PROGRAM_IS_SIMPLE_GRADIENT 40

#define PROGRAM_HAS_COLORS 41

#define PROGRAM_HAS_DEBUG_HIGHLIGHT 42
#define PROGRAM_HAS_ROUND_RECT_CLIP 43

#define PROGRAM_HAS_GAMMA_CORRECTION 44
#define PROGRAM_HAS_LINEAR_TEXTURE 45

#define PROGRAM_HAS_COLOR_SPACE_CONVERSION 46
#define PROGRAM_TRANSFER_FUNCTION 47  // 2 bits for transfer function
#define PROGRAM_HAS_TRANSLUCENT_CONVERSION 49

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef uint64_t programid;

///////////////////////////////////////////////////////////////////////////////
// Program description
///////////////////////////////////////////////////////////////////////////////

/**
 * Describe the features required for a given program. The features
 * determine the generation of both the vertex and fragment shaders.
 * A ProgramDescription must be used in conjunction with a ProgramCache.
 */
struct ProgramDescription {
    enum class ColorFilterMode : int8_t { None = 0, Matrix, Blend };

    enum Gradient : int8_t { kGradientLinear = 0, kGradientCircular, kGradientSweep };

    ProgramDescription() { reset(); }

    // Texturing
    bool hasTexture;
    bool hasAlpha8Texture;
    bool hasExternalTexture;
    bool hasTextureTransform;

    // Color attribute
    bool hasColors;

    // Modulate, this should only be set when setColor() return true
    bool modulate;

    // Shaders
    bool hasBitmap;
    bool isShaderBitmapExternal;
    bool useShaderBasedWrap;

    bool hasVertexAlpha;
    bool useShadowAlphaInterp;

    bool hasGradient;
    Gradient gradientType;
    bool isSimpleGradient;

    SkBlendMode shadersMode;

    bool isBitmapFirst;
    GLenum bitmapWrapS;
    GLenum bitmapWrapT;

    // Color operations
    ColorFilterMode colorOp;
    SkBlendMode colorMode;

    // Framebuffer blending (requires Extensions.hasFramebufferFetch())
    // Ignored for all values < SkBlendMode::kPlus
    SkBlendMode framebufferMode;
    bool swapSrcDst;

    bool hasDebugHighlight;
    bool hasRoundRectClip;

    // Extra gamma correction used for text
    bool hasGammaCorrection;
    // Set when sampling an image in linear space
    bool hasLinearTexture;

    bool hasColorSpaceConversion;
    TransferFunctionType transferFunction;
    // Indicates whether the bitmap to convert between color spaces is translucent
    bool hasTranslucentConversion;

    /**
     * Resets this description. All fields are reset back to the default
     * values they hold after building a new instance.
     */
    void reset() {
        hasTexture = false;
        hasAlpha8Texture = false;
        hasExternalTexture = false;
        hasTextureTransform = false;

        hasColors = false;

        hasVertexAlpha = false;
        useShadowAlphaInterp = false;

        modulate = false;

        hasBitmap = false;
        isShaderBitmapExternal = false;
        useShaderBasedWrap = false;

        hasGradient = false;
        gradientType = kGradientLinear;
        isSimpleGradient = false;

        shadersMode = SkBlendMode::kClear;

        isBitmapFirst = false;
        bitmapWrapS = GL_CLAMP_TO_EDGE;
        bitmapWrapT = GL_CLAMP_TO_EDGE;

        colorOp = ColorFilterMode::None;
        colorMode = SkBlendMode::kClear;

        framebufferMode = SkBlendMode::kClear;
        swapSrcDst = false;

        hasDebugHighlight = false;
        hasRoundRectClip = false;

        hasGammaCorrection = false;
        hasLinearTexture = false;

        hasColorSpaceConversion = false;
        transferFunction = TransferFunctionType::None;
        hasTranslucentConversion = false;
    }

    /**
     * Indicates, for a given color, whether color modulation is required in
     * the fragment shader. When this method returns true, the program should
     * be provided with a modulation color.
     */
    bool setColorModulate(const float a) {
        modulate = a < COLOR_COMPONENT_THRESHOLD;
        return modulate;
    }

    /**
     * Indicates, for a given color, whether color modulation is required in
     * the fragment shader. When this method returns true, the program should
     * be provided with a modulation color.
     */
    bool setAlpha8ColorModulate(const float r, const float g, const float b, const float a) {
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
            if (useShaderBasedWrap) {
                key |= PROGRAM_KEY_BITMAP_NPOT;
                key |= getEnumForWrap(bitmapWrapS) << PROGRAM_BITMAP_WRAPS_SHIFT;
                key |= getEnumForWrap(bitmapWrapT) << PROGRAM_BITMAP_WRAPT_SHIFT;
            }
            if (isShaderBitmapExternal) {
                key |= PROGRAM_KEY_BITMAP_EXTERNAL;
            }
        }
        if (hasGradient) key |= PROGRAM_KEY_GRADIENT;
        key |= programid(gradientType) << PROGRAM_GRADIENT_TYPE_SHIFT;
        if (isBitmapFirst) key |= PROGRAM_KEY_BITMAP_FIRST;
        if (hasBitmap && hasGradient) {
            key |= ((int)shadersMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_SHADER_SHIFT;
        }
        switch (colorOp) {
            case ColorFilterMode::Matrix:
                key |= PROGRAM_KEY_COLOR_MATRIX;
                break;
            case ColorFilterMode::Blend:
                key |= PROGRAM_KEY_COLOR_BLEND;
                key |= ((int)colorMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_COLOR_OP_SHIFT;
                break;
            case ColorFilterMode::None:
                break;
        }
        key |= ((int)framebufferMode & PROGRAM_MAX_XFERMODE) << PROGRAM_XFERMODE_FRAMEBUFFER_SHIFT;
        key |= programid(swapSrcDst) << PROGRAM_KEY_SWAP_SRC_DST_SHIFT;
        key |= programid(modulate) << PROGRAM_MODULATE_SHIFT;
        key |= programid(hasVertexAlpha) << PROGRAM_HAS_VERTEX_ALPHA_SHIFT;
        key |= programid(useShadowAlphaInterp) << PROGRAM_USE_SHADOW_ALPHA_INTERP_SHIFT;
        key |= programid(hasExternalTexture) << PROGRAM_HAS_EXTERNAL_TEXTURE_SHIFT;
        key |= programid(hasTextureTransform) << PROGRAM_HAS_TEXTURE_TRANSFORM_SHIFT;
        key |= programid(isSimpleGradient) << PROGRAM_IS_SIMPLE_GRADIENT;
        key |= programid(hasColors) << PROGRAM_HAS_COLORS;
        key |= programid(hasDebugHighlight) << PROGRAM_HAS_DEBUG_HIGHLIGHT;
        key |= programid(hasRoundRectClip) << PROGRAM_HAS_ROUND_RECT_CLIP;
        key |= programid(hasGammaCorrection) << PROGRAM_HAS_GAMMA_CORRECTION;
        key |= programid(hasLinearTexture) << PROGRAM_HAS_LINEAR_TEXTURE;
        key |= programid(hasColorSpaceConversion) << PROGRAM_HAS_COLOR_SPACE_CONVERSION;
        key |= programid(transferFunction) << PROGRAM_TRANSFER_FUNCTION;
        key |= programid(hasTranslucentConversion) << PROGRAM_HAS_TRANSLUCENT_CONVERSION;
        return key;
    }

    /**
     * Logs the specified message followed by the key identifying this program.
     */
    void log(const char* message) const {
#if DEBUG_PROGRAMS
        programid k = key();
        PROGRAM_LOGD("%s (key = 0x%.8x%.8x)", message, uint32_t(k >> 32), uint32_t(k & 0xffffffff));
#endif
    }

private:
    static inline uint32_t getEnumForWrap(GLenum wrap) {
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

};  // struct ProgramDescription

/**
 * A program holds a vertex and a fragment shader. It offers several utility
 * methods to query attributes and uniforms.
 */
class Program {
public:
    enum ShaderBindings { kBindingPosition, kBindingTexCoords };

    /**
     * Creates a new program with the specified vertex and fragment
     * shaders sources.
     */
    Program(const ProgramDescription& description, const char* vertex, const char* fragment);
    virtual ~Program();

    /**
     * Binds this program to the GL context.
     */
    virtual void use();

    /**
     * Marks this program as unused. This will not unbind
     * the program from the GL context.
     */
    virtual void remove();

    /**
     * Returns the OpenGL name of the specified attribute.
     */
    int getAttrib(const char* name);

    /**
     * Returns the OpenGL name of the specified uniform.
     */
    int getUniform(const char* name);

    /**
     * Indicates whether this program is currently in use with
     * the GL context.
     */
    inline bool isInUse() const { return mUse; }

    /**
     * Indicates whether this program was correctly compiled and linked.
     */
    inline bool isInitialized() const { return mInitialized; }

    /**
     * Binds the program with the specified projection, modelView and
     * transform matrices.
     */
    void set(const mat4& projectionMatrix, const mat4& modelViewMatrix, const mat4& transformMatrix,
             bool offset = false);

    /**
     * Sets the color associated with this shader.
     */
    void setColor(FloatColor color);

    /**
     * Name of the texCoords attribute if it exists (kBindingTexCoords), -1 otherwise.
     */
    int texCoords;

    /**
     * Name of the transform uniform.
     */
    int transform;

    /**
     * Name of the projection uniform.
     */
    int projection;

protected:
    /**
     * Adds an attribute with the specified name.
     *
     * @return The OpenGL name of the attribute.
     */
    int addAttrib(const char* name);

    /**
     * Binds the specified attribute name to the specified slot.
     */
    int bindAttrib(const char* name, ShaderBindings bindingSlot);

    /**
     * Adds a uniform with the specified name.
     *
     * @return The OpenGL name of the uniform.
     */
    int addUniform(const char* name);

private:
    /**
     * Compiles the specified shader of the specified type.
     *
     * @return The name of the compiled shader.
     */
    GLuint buildShader(const char* source, GLenum type);

    // Name of the OpenGL program and shaders
    GLuint mProgramId;
    GLuint mVertexShader;
    GLuint mFragmentShader;

    // Keeps track of attributes and uniforms slots
    KeyedVector<const char*, int> mAttributes;
    KeyedVector<const char*, int> mUniforms;

    bool mUse;
    bool mInitialized;

    // Uniforms caching
    bool mHasColorUniform;
    int mColorUniform;

    bool mHasSampler;

    mat4 mProjection;
    bool mOffset;
};  // class Program

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_PROGRAM_H
