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

#include <utils/String8.h>

#include "Caches.h"
#include "Dither.h"
#include "ProgramCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MODULATE_OP_NO_MODULATE 0
#define MODULATE_OP_MODULATE 1
#define MODULATE_OP_MODULATE_A8 2

#define STR(x) STR1(x)
#define STR1(x) #x

///////////////////////////////////////////////////////////////////////////////
// Vertex shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gVS_Header_Start =
        "#version 100\n"
        "attribute vec4 position;\n";
const char* gVS_Header_Attributes_TexCoords =
        "attribute vec2 texCoords;\n";
const char* gVS_Header_Attributes_Colors =
        "attribute vec4 colors;\n";
const char* gVS_Header_Attributes_VertexAlphaParameters =
        "attribute float vtxAlpha;\n";
const char* gVS_Header_Uniforms_TextureTransform =
        "uniform mat4 mainTextureTransform;\n";
const char* gVS_Header_Uniforms =
        "uniform mat4 projection;\n" \
        "uniform mat4 transform;\n";
const char* gVS_Header_Uniforms_HasGradient =
        "uniform mat4 screenSpace;\n";
const char* gVS_Header_Uniforms_HasBitmap =
        "uniform mat4 textureTransform;\n"
        "uniform mediump vec2 textureDimension;\n";
const char* gVS_Header_Uniforms_HasRoundRectClip =
        "uniform mat4 roundRectInvTransform;\n";
const char* gVS_Header_Varyings_HasTexture =
        "varying vec2 outTexCoords;\n";
const char* gVS_Header_Varyings_HasColors =
        "varying vec4 outColors;\n";
const char* gVS_Header_Varyings_HasVertexAlpha =
        "varying float alpha;\n";
const char* gVS_Header_Varyings_HasBitmap =
        "varying highp vec2 outBitmapTexCoords;\n";
const char* gVS_Header_Varyings_HasGradient[6] = {
        // Linear
        "varying highp vec2 linear;\n"
        "varying vec2 ditherTexCoords;\n",
        "varying float linear;\n"
        "varying vec2 ditherTexCoords;\n",

        // Circular
        "varying highp vec2 circular;\n"
        "varying vec2 ditherTexCoords;\n",
        "varying highp vec2 circular;\n"
        "varying vec2 ditherTexCoords;\n",

        // Sweep
        "varying highp vec2 sweep;\n"
        "varying vec2 ditherTexCoords;\n",
        "varying highp vec2 sweep;\n"
        "varying vec2 ditherTexCoords;\n",
};
const char* gVS_Header_Varyings_HasRoundRectClip =
        "varying highp vec2 roundRectPos;\n";
const char* gVS_Main =
        "\nvoid main(void) {\n";
const char* gVS_Main_OutTexCoords =
        "    outTexCoords = texCoords;\n";
const char* gVS_Main_OutColors =
        "    outColors = colors;\n";
const char* gVS_Main_OutTransformedTexCoords =
        "    outTexCoords = (mainTextureTransform * vec4(texCoords, 0.0, 1.0)).xy;\n";
const char* gVS_Main_OutGradient[6] = {
        // Linear
        "    linear = vec2((screenSpace * position).x, 0.5);\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",
        "    linear = (screenSpace * position).x;\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",

        // Circular
        "    circular = (screenSpace * position).xy;\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",
        "    circular = (screenSpace * position).xy;\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",

        // Sweep
        "    sweep = (screenSpace * position).xy;\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",
        "    sweep = (screenSpace * position).xy;\n"
        "    ditherTexCoords = (transform * position).xy * " STR(DITHER_KERNEL_SIZE_INV) ";\n",
};
const char* gVS_Main_OutBitmapTexCoords =
        "    outBitmapTexCoords = (textureTransform * position).xy * textureDimension;\n";
const char* gVS_Main_Position =
        "    vec4 transformedPosition = projection * transform * position;\n"
        "    gl_Position = transformedPosition;\n";

const char* gVS_Main_VertexAlpha =
        "    alpha = vtxAlpha;\n";

const char* gVS_Main_HasRoundRectClip =
        "    roundRectPos = (roundRectInvTransform * transformedPosition).xy;\n";
const char* gVS_Footer =
        "}\n\n";

///////////////////////////////////////////////////////////////////////////////
// Fragment shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gFS_Header_Start =
        "#version 100\n";
const char* gFS_Header_Extension_FramebufferFetch =
        "#extension GL_NV_shader_framebuffer_fetch : enable\n\n";
const char* gFS_Header_Extension_ExternalTexture =
        "#extension GL_OES_EGL_image_external : require\n\n";
const char* gFS_Header =
        "precision mediump float;\n\n";
const char* gFS_Uniforms_Color =
        "uniform vec4 color;\n";
const char* gFS_Uniforms_TextureSampler =
        "uniform sampler2D baseSampler;\n";
const char* gFS_Uniforms_ExternalTextureSampler =
        "uniform samplerExternalOES baseSampler;\n";
const char* gFS_Uniforms_Dither =
        "uniform sampler2D ditherSampler;";
const char* gFS_Uniforms_GradientSampler[2] = {
        "%s\n"
        "uniform sampler2D gradientSampler;\n",
        "%s\n"
        "uniform vec4 startColor;\n"
        "uniform vec4 endColor;\n"
};
const char* gFS_Uniforms_BitmapSampler =
        "uniform sampler2D bitmapSampler;\n";
const char* gFS_Uniforms_ColorOp[3] = {
        // None
        "",
        // Matrix
        "uniform mat4 colorMatrix;\n"
        "uniform vec4 colorMatrixVector;\n",
        // PorterDuff
        "uniform vec4 colorBlend;\n"
};

const char* gFS_Uniforms_HasRoundRectClip =
        "uniform vec4 roundRectInnerRectLTRB;\n"
        "uniform float roundRectRadius;\n";

const char* gFS_Main =
        "\nvoid main(void) {\n"
        "    lowp vec4 fragColor;\n";

const char* gFS_Main_Dither[2] = {
        // ES 2.0
        "texture2D(ditherSampler, ditherTexCoords).a * " STR(DITHER_KERNEL_SIZE_INV_SQUARE),
        // ES 3.0
        "texture2D(ditherSampler, ditherTexCoords).a"
};
const char* gFS_Main_AddDitherToGradient =
        "    gradientColor += %s;\n";

// Fast cases
const char* gFS_Fast_SingleColor =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color;\n"
        "}\n\n";
const char* gFS_Fast_SingleTexture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = texture2D(baseSampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleModulateTexture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color.a * texture2D(baseSampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleA8Texture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = texture2D(baseSampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleModulateA8Texture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color * texture2D(baseSampler, outTexCoords).a;\n"
        "}\n\n";
const char* gFS_Fast_SingleGradient[2] = {
        "\nvoid main(void) {\n"
        "    gl_FragColor = %s + texture2D(gradientSampler, linear);\n"
        "}\n\n",
        "\nvoid main(void) {\n"
        "    gl_FragColor = %s + mix(startColor, endColor, clamp(linear, 0.0, 1.0));\n"
        "}\n\n",
};
const char* gFS_Fast_SingleModulateGradient[2] = {
        "\nvoid main(void) {\n"
        "    gl_FragColor = %s + color.a * texture2D(gradientSampler, linear);\n"
        "}\n\n",
        "\nvoid main(void) {\n"
        "    gl_FragColor = %s + color.a * mix(startColor, endColor, clamp(linear, 0.0, 1.0));\n"
        "}\n\n"
};

// General case
const char* gFS_Main_FetchColor =
        "    fragColor = color;\n";
const char* gFS_Main_ModulateColor =
        "    fragColor *= color.a;\n";
const char* gFS_Main_ApplyVertexAlphaLinearInterp =
        "    fragColor *= alpha;\n";
const char* gFS_Main_ApplyVertexAlphaShadowInterp =
        // map alpha through shadow alpha sampler
        "    fragColor *= texture2D(baseSampler, vec2(alpha, 0.5)).a;\n";
const char* gFS_Main_FetchTexture[2] = {
        // Don't modulate
        "    fragColor = texture2D(baseSampler, outTexCoords);\n",
        // Modulate
        "    fragColor = color * texture2D(baseSampler, outTexCoords);\n"
};
const char* gFS_Main_FetchA8Texture[2] = {
        // Don't modulate
        "    fragColor = texture2D(baseSampler, outTexCoords);\n",
        // Modulate
        "    fragColor = color * texture2D(baseSampler, outTexCoords).a;\n",
};
const char* gFS_Main_FetchGradient[6] = {
        // Linear
        "    vec4 gradientColor = texture2D(gradientSampler, linear);\n",

        "    vec4 gradientColor = mix(startColor, endColor, clamp(linear, 0.0, 1.0));\n",

        // Circular
        "    vec4 gradientColor = texture2D(gradientSampler, vec2(length(circular), 0.5));\n",

        "    vec4 gradientColor = mix(startColor, endColor, clamp(length(circular), 0.0, 1.0));\n",

        // Sweep
        "    highp float index = atan(sweep.y, sweep.x) * 0.15915494309; // inv(2 * PI)\n"
        "    vec4 gradientColor = texture2D(gradientSampler, vec2(index - floor(index), 0.5));\n",

        "    highp float index = atan(sweep.y, sweep.x) * 0.15915494309; // inv(2 * PI)\n"
        "    vec4 gradientColor = mix(startColor, endColor, clamp(index - floor(index), 0.0, 1.0));\n"
};
const char* gFS_Main_FetchBitmap =
        "    vec4 bitmapColor = texture2D(bitmapSampler, outBitmapTexCoords);\n";
const char* gFS_Main_FetchBitmapNpot =
        "    vec4 bitmapColor = texture2D(bitmapSampler, wrap(outBitmapTexCoords));\n";
const char* gFS_Main_BlendShadersBG =
        "    fragColor = blendShaders(gradientColor, bitmapColor)";
const char* gFS_Main_BlendShadersGB =
        "    fragColor = blendShaders(bitmapColor, gradientColor)";
const char* gFS_Main_BlendShaders_Modulate[3] = {
        // Don't modulate
        ";\n",
        // Modulate
        " * color.a;\n",
        // Modulate with alpha 8 texture
        " * texture2D(baseSampler, outTexCoords).a;\n",
};
const char* gFS_Main_GradientShader_Modulate[3] = {
        // Don't modulate
        "    fragColor = gradientColor;\n",
        // Modulate
        "    fragColor = gradientColor * color.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = gradientColor * texture2D(baseSampler, outTexCoords).a;\n",
    };
const char* gFS_Main_BitmapShader_Modulate[3] = {
        // Don't modulate
        "    fragColor = bitmapColor;\n",
        // Modulate
        "    fragColor = bitmapColor * color.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = bitmapColor * texture2D(baseSampler, outTexCoords).a;\n",
    };
const char* gFS_Main_FragColor =
        "    gl_FragColor = fragColor;\n";
const char* gFS_Main_FragColor_HasColors =
        "    gl_FragColor *= outColors;\n";
const char* gFS_Main_FragColor_Blend =
        "    gl_FragColor = blendFramebuffer(fragColor, gl_LastFragColor);\n";
const char* gFS_Main_FragColor_Blend_Swap =
        "    gl_FragColor = blendFramebuffer(gl_LastFragColor, fragColor);\n";
const char* gFS_Main_ApplyColorOp[3] = {
        // None
        "",
        // Matrix
        "    fragColor.rgb /= (fragColor.a + 0.0019);\n" // un-premultiply
        "    fragColor *= colorMatrix;\n"
        "    fragColor += colorMatrixVector;\n"
        "    fragColor.rgb *= (fragColor.a + 0.0019);\n", // re-premultiply
        // PorterDuff
        "    fragColor = blendColors(colorBlend, fragColor);\n"
};

// Note: LTRB -> xyzw
const char* gFS_Main_FragColor_HasRoundRectClip =
        "    mediump vec2 fragToLT = roundRectInnerRectLTRB.xy - roundRectPos;\n"
        "    mediump vec2 fragFromRB = roundRectPos - roundRectInnerRectLTRB.zw;\n"

        // divide + multiply by 128 to avoid falling out of range in length() function
        "    mediump vec2 dist = max(max(fragToLT, fragFromRB), vec2(0.0, 0.0)) / 128.0;\n"
        "    mediump float linearDist = roundRectRadius - (length(dist) * 128.0);\n"
        "    gl_FragColor *= clamp(linearDist, 0.0, 1.0);\n";

const char* gFS_Main_DebugHighlight =
        "    gl_FragColor.rgb = vec3(0.0, gl_FragColor.a, 0.0);\n";
const char* gFS_Footer =
        "}\n\n";

///////////////////////////////////////////////////////////////////////////////
// PorterDuff snippets
///////////////////////////////////////////////////////////////////////////////

const char* gBlendOps[18] = {
        // Clear
        "return vec4(0.0, 0.0, 0.0, 0.0);\n",
        // Src
        "return src;\n",
        // Dst
        "return dst;\n",
        // SrcOver
        "return src + dst * (1.0 - src.a);\n",
        // DstOver
        "return dst + src * (1.0 - dst.a);\n",
        // SrcIn
        "return src * dst.a;\n",
        // DstIn
        "return dst * src.a;\n",
        // SrcOut
        "return src * (1.0 - dst.a);\n",
        // DstOut
        "return dst * (1.0 - src.a);\n",
        // SrcAtop
        "return vec4(src.rgb * dst.a + (1.0 - src.a) * dst.rgb, dst.a);\n",
        // DstAtop
        "return vec4(dst.rgb * src.a + (1.0 - dst.a) * src.rgb, src.a);\n",
        // Xor
        "return vec4(src.rgb * (1.0 - dst.a) + (1.0 - src.a) * dst.rgb, "
                "src.a + dst.a - 2.0 * src.a * dst.a);\n",
        // Plus
        "return min(src + dst, 1.0);\n",
        // Modulate
        "return src * dst;\n",
        // Screen
        "return src + dst - src * dst;\n",
        // Overlay
        "return clamp(vec4(mix("
                "2.0 * src.rgb * dst.rgb + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a), "
                "src.a * dst.a - 2.0 * (dst.a - dst.rgb) * (src.a - src.rgb) + src.rgb * (1.0 - dst.a) + dst.rgb * (1.0 - src.a), "
                "step(dst.a, 2.0 * dst.rgb)), "
                "src.a + dst.a - src.a * dst.a), 0.0, 1.0);\n",
        // Darken
        "return vec4(src.rgb * (1.0 - dst.a) + (1.0 - src.a) * dst.rgb + "
                "min(src.rgb * dst.a, dst.rgb * src.a), src.a + dst.a - src.a * dst.a);\n",
        // Lighten
        "return vec4(src.rgb * (1.0 - dst.a) + (1.0 - src.a) * dst.rgb + "
                "max(src.rgb * dst.a, dst.rgb * src.a), src.a + dst.a - src.a * dst.a);\n",
};

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructors
///////////////////////////////////////////////////////////////////////////////

ProgramCache::ProgramCache(Extensions& extensions)
        : mHasES3(extensions.getMajorGlVersion() >= 3) {
}

ProgramCache::~ProgramCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Cache management
///////////////////////////////////////////////////////////////////////////////

void ProgramCache::clear() {
    PROGRAM_LOGD("Clearing program cache");
    mCache.clear();
}

Program* ProgramCache::get(const ProgramDescription& description) {
    programid key = description.key();
    if (key == (PROGRAM_KEY_TEXTURE | PROGRAM_KEY_A8_TEXTURE)) {
        // program for A8, unmodulated, texture w/o shader (black text/path textures) is equivalent
        // to standard texture program (bitmaps, patches). Consider them equivalent.
        key = PROGRAM_KEY_TEXTURE;
    }

    auto iter = mCache.find(key);
    Program* program = nullptr;
    if (iter == mCache.end()) {
        description.log("Could not find program");
        program = generateProgram(description, key);
        mCache[key] = std::unique_ptr<Program>(program);
    } else {
        program = iter->second.get();
    }
    return program;
}

///////////////////////////////////////////////////////////////////////////////
// Program generation
///////////////////////////////////////////////////////////////////////////////

Program* ProgramCache::generateProgram(const ProgramDescription& description, programid key) {
    String8 vertexShader = generateVertexShader(description);
    String8 fragmentShader = generateFragmentShader(description);

    return new Program(description, vertexShader.string(), fragmentShader.string());
}

static inline size_t gradientIndex(const ProgramDescription& description) {
    return description.gradientType * 2 + description.isSimpleGradient;
}

String8 ProgramCache::generateVertexShader(const ProgramDescription& description) {
    // Add attributes
    String8 shader(gVS_Header_Start);
    if (description.hasTexture || description.hasExternalTexture) {
        shader.append(gVS_Header_Attributes_TexCoords);
    }
    if (description.hasVertexAlpha) {
        shader.append(gVS_Header_Attributes_VertexAlphaParameters);
    }
    if (description.hasColors) {
        shader.append(gVS_Header_Attributes_Colors);
    }
    // Uniforms
    shader.append(gVS_Header_Uniforms);
    if (description.hasTextureTransform) {
        shader.append(gVS_Header_Uniforms_TextureTransform);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Uniforms_HasGradient);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Uniforms_HasBitmap);
    }
    if (description.hasRoundRectClip) {
        shader.append(gVS_Header_Uniforms_HasRoundRectClip);
    }
    // Varyings
    if (description.hasTexture || description.hasExternalTexture) {
        shader.append(gVS_Header_Varyings_HasTexture);
    }
    if (description.hasVertexAlpha) {
        shader.append(gVS_Header_Varyings_HasVertexAlpha);
    }
    if (description.hasColors) {
        shader.append(gVS_Header_Varyings_HasColors);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient[gradientIndex(description)]);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Varyings_HasBitmap);
    }
    if (description.hasRoundRectClip) {
        shader.append(gVS_Header_Varyings_HasRoundRectClip);
    }

    // Begin the shader
    shader.append(gVS_Main); {
        if (description.hasTextureTransform) {
            shader.append(gVS_Main_OutTransformedTexCoords);
        } else if (description.hasTexture || description.hasExternalTexture) {
            shader.append(gVS_Main_OutTexCoords);
        }
        if (description.hasVertexAlpha) {
            shader.append(gVS_Main_VertexAlpha);
        }
        if (description.hasColors) {
            shader.append(gVS_Main_OutColors);
        }
        if (description.hasBitmap) {
            shader.append(gVS_Main_OutBitmapTexCoords);
        }
        // Output transformed position
        shader.append(gVS_Main_Position);
        if (description.hasGradient) {
            shader.append(gVS_Main_OutGradient[gradientIndex(description)]);
        }
        if (description.hasRoundRectClip) {
            shader.append(gVS_Main_HasRoundRectClip);
        }
    }
    // End the shader
    shader.append(gVS_Footer);

    PROGRAM_LOGD("*** Generated vertex shader:\n\n%s", shader.string());

    return shader;
}

static bool shaderOp(const ProgramDescription& description, String8& shader,
        const int modulateOp, const char** snippets) {
    int op = description.hasAlpha8Texture ? MODULATE_OP_MODULATE_A8 : modulateOp;
    shader.append(snippets[op]);
    return description.hasAlpha8Texture;
}

String8 ProgramCache::generateFragmentShader(const ProgramDescription& description) {
    String8 shader(gFS_Header_Start);

    const bool blendFramebuffer = description.framebufferMode >= SkXfermode::kPlus_Mode;
    if (blendFramebuffer) {
        shader.append(gFS_Header_Extension_FramebufferFetch);
    }
    if (description.hasExternalTexture) {
        shader.append(gFS_Header_Extension_ExternalTexture);
    }

    shader.append(gFS_Header);

    // Varyings
    if (description.hasTexture || description.hasExternalTexture) {
        shader.append(gVS_Header_Varyings_HasTexture);
    }
    if (description.hasVertexAlpha) {
        shader.append(gVS_Header_Varyings_HasVertexAlpha);
    }
    if (description.hasColors) {
        shader.append(gVS_Header_Varyings_HasColors);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient[gradientIndex(description)]);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Varyings_HasBitmap);
    }
    if (description.hasRoundRectClip) {
        shader.append(gVS_Header_Varyings_HasRoundRectClip);
    }

    // Uniforms
    int modulateOp = MODULATE_OP_NO_MODULATE;
    const bool singleColor = !description.hasTexture && !description.hasExternalTexture &&
            !description.hasGradient && !description.hasBitmap;

    if (description.modulate || singleColor) {
        shader.append(gFS_Uniforms_Color);
        if (!singleColor) modulateOp = MODULATE_OP_MODULATE;
    }
    if (description.hasTexture || description.useShadowAlphaInterp) {
        shader.append(gFS_Uniforms_TextureSampler);
    } else if (description.hasExternalTexture) {
        shader.append(gFS_Uniforms_ExternalTextureSampler);
    }
    if (description.hasGradient) {
        shader.appendFormat(gFS_Uniforms_GradientSampler[description.isSimpleGradient],
                gFS_Uniforms_Dither);
    }
    if (description.hasRoundRectClip) {
        shader.append(gFS_Uniforms_HasRoundRectClip);
    }

    // Optimization for common cases
    if (!description.hasVertexAlpha
            && !blendFramebuffer
            && !description.hasColors
            && description.colorOp == ProgramDescription::ColorFilterMode::None
            && !description.hasDebugHighlight
            && !description.hasRoundRectClip) {
        bool fast = false;

        const bool noShader = !description.hasGradient && !description.hasBitmap;
        const bool singleTexture = (description.hasTexture || description.hasExternalTexture) &&
                !description.hasAlpha8Texture && noShader;
        const bool singleA8Texture = description.hasTexture &&
                description.hasAlpha8Texture && noShader;
        const bool singleGradient = !description.hasTexture && !description.hasExternalTexture &&
                description.hasGradient && !description.hasBitmap &&
                description.gradientType == ProgramDescription::kGradientLinear;

        if (singleColor) {
            shader.append(gFS_Fast_SingleColor);
            fast = true;
        } else if (singleTexture) {
            if (!description.modulate) {
                shader.append(gFS_Fast_SingleTexture);
            } else {
                shader.append(gFS_Fast_SingleModulateTexture);
            }
            fast = true;
        } else if (singleA8Texture) {
            if (!description.modulate) {
                shader.append(gFS_Fast_SingleA8Texture);
            } else {
                shader.append(gFS_Fast_SingleModulateA8Texture);
            }
            fast = true;
        } else if (singleGradient) {
            if (!description.modulate) {
                shader.appendFormat(gFS_Fast_SingleGradient[description.isSimpleGradient],
                        gFS_Main_Dither[mHasES3]);
            } else {
                shader.appendFormat(gFS_Fast_SingleModulateGradient[description.isSimpleGradient],
                        gFS_Main_Dither[mHasES3]);
            }
            fast = true;
        }

        if (fast) {
#if DEBUG_PROGRAMS
                PROGRAM_LOGD("*** Fast case:\n");
                PROGRAM_LOGD("*** Generated fragment shader:\n\n");
                printLongString(shader);
#endif

            return shader;
        }
    }

    if (description.hasBitmap) {
        shader.append(gFS_Uniforms_BitmapSampler);
    }
    shader.append(gFS_Uniforms_ColorOp[static_cast<int>(description.colorOp)]);

    // Generate required functions
    if (description.hasGradient && description.hasBitmap) {
        generateBlend(shader, "blendShaders", description.shadersMode);
    }
    if (description.colorOp == ProgramDescription::ColorFilterMode::Blend) {
        generateBlend(shader, "blendColors", description.colorMode);
    }
    if (blendFramebuffer) {
        generateBlend(shader, "blendFramebuffer", description.framebufferMode);
    }
    if (description.isBitmapNpot) {
        generateTextureWrap(shader, description.bitmapWrapS, description.bitmapWrapT);
    }

    // Begin the shader
    shader.append(gFS_Main); {
        // Stores the result in fragColor directly
        if (description.hasTexture || description.hasExternalTexture) {
            if (description.hasAlpha8Texture) {
                if (!description.hasGradient && !description.hasBitmap) {
                    shader.append(gFS_Main_FetchA8Texture[modulateOp]);
                }
            } else {
                shader.append(gFS_Main_FetchTexture[modulateOp]);
            }
        } else {
            if (!description.hasGradient && !description.hasBitmap) {
                shader.append(gFS_Main_FetchColor);
            }
        }
        if (description.hasGradient) {
            shader.append(gFS_Main_FetchGradient[gradientIndex(description)]);
            shader.appendFormat(gFS_Main_AddDitherToGradient, gFS_Main_Dither[mHasES3]);
        }
        if (description.hasBitmap) {
            if (!description.isBitmapNpot) {
                shader.append(gFS_Main_FetchBitmap);
            } else {
                shader.append(gFS_Main_FetchBitmapNpot);
            }
        }
        bool applyModulate = false;
        // Case when we have two shaders set
        if (description.hasGradient && description.hasBitmap) {
            if (description.isBitmapFirst) {
                shader.append(gFS_Main_BlendShadersBG);
            } else {
                shader.append(gFS_Main_BlendShadersGB);
            }
            applyModulate = shaderOp(description, shader, modulateOp,
                    gFS_Main_BlendShaders_Modulate);
        } else {
            if (description.hasGradient) {
                applyModulate = shaderOp(description, shader, modulateOp,
                        gFS_Main_GradientShader_Modulate);
            } else if (description.hasBitmap) {
                applyModulate = shaderOp(description, shader, modulateOp,
                        gFS_Main_BitmapShader_Modulate);
            }
        }

        if (description.modulate && applyModulate) {
            shader.append(gFS_Main_ModulateColor);
        }

        // Apply the color op if needed
        shader.append(gFS_Main_ApplyColorOp[static_cast<int>(description.colorOp)]);

        if (description.hasVertexAlpha) {
            if (description.useShadowAlphaInterp) {
                shader.append(gFS_Main_ApplyVertexAlphaShadowInterp);
            } else {
                shader.append(gFS_Main_ApplyVertexAlphaLinearInterp);
            }
        }

        // Output the fragment
        if (!blendFramebuffer) {
            shader.append(gFS_Main_FragColor);
        } else {
            shader.append(!description.swapSrcDst ?
                    gFS_Main_FragColor_Blend : gFS_Main_FragColor_Blend_Swap);
        }
        if (description.hasColors) {
            shader.append(gFS_Main_FragColor_HasColors);
        }
        if (description.hasRoundRectClip) {
            shader.append(gFS_Main_FragColor_HasRoundRectClip);
        }
        if (description.hasDebugHighlight) {
            shader.append(gFS_Main_DebugHighlight);
        }
    }
    // End the shader
    shader.append(gFS_Footer);

#if DEBUG_PROGRAMS
        PROGRAM_LOGD("*** Generated fragment shader:\n\n");
        printLongString(shader);
#endif

    return shader;
}

void ProgramCache::generateBlend(String8& shader, const char* name, SkXfermode::Mode mode) {
    shader.append("\nvec4 ");
    shader.append(name);
    shader.append("(vec4 src, vec4 dst) {\n");
    shader.append("    ");
    shader.append(gBlendOps[mode]);
    shader.append("}\n");
}

void ProgramCache::generateTextureWrap(String8& shader, GLenum wrapS, GLenum wrapT) {
    shader.append("\nhighp vec2 wrap(highp vec2 texCoords) {\n");
    if (wrapS == GL_MIRRORED_REPEAT) {
        shader.append("    highp float xMod2 = mod(texCoords.x, 2.0);\n");
        shader.append("    if (xMod2 > 1.0) xMod2 = 2.0 - xMod2;\n");
    }
    if (wrapT == GL_MIRRORED_REPEAT) {
        shader.append("    highp float yMod2 = mod(texCoords.y, 2.0);\n");
        shader.append("    if (yMod2 > 1.0) yMod2 = 2.0 - yMod2;\n");
    }
    shader.append("    return vec2(");
    switch (wrapS) {
        case GL_CLAMP_TO_EDGE:
            shader.append("texCoords.x");
            break;
        case GL_REPEAT:
            shader.append("mod(texCoords.x, 1.0)");
            break;
        case GL_MIRRORED_REPEAT:
            shader.append("xMod2");
            break;
    }
    shader.append(", ");
    switch (wrapT) {
        case GL_CLAMP_TO_EDGE:
            shader.append("texCoords.y");
            break;
        case GL_REPEAT:
            shader.append("mod(texCoords.y, 1.0)");
            break;
        case GL_MIRRORED_REPEAT:
            shader.append("yMod2");
            break;
    }
    shader.append(");\n");
    shader.append("}\n");
}

void ProgramCache::printLongString(const String8& shader) const {
    ssize_t index = 0;
    ssize_t lastIndex = 0;
    const char* str = shader.string();
    while ((index = shader.find("\n", index)) > -1) {
        String8 line(str, index - lastIndex);
        if (line.length() == 0) line.append("\n");
        ALOGD("%s", line.string());
        index++;
        str += (index - lastIndex);
        lastIndex = index;
    }
}

}; // namespace uirenderer
}; // namespace android
