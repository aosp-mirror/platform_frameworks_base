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
#include "ProgramCache.h"
#include "Properties.h"

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
const char* gVS_Header_Attributes_TexCoords = "attribute vec2 texCoords;\n";
const char* gVS_Header_Attributes_Colors = "attribute vec4 colors;\n";
const char* gVS_Header_Attributes_VertexAlphaParameters = "attribute float vtxAlpha;\n";
const char* gVS_Header_Uniforms_TextureTransform = "uniform mat4 mainTextureTransform;\n";
const char* gVS_Header_Uniforms =
        "uniform mat4 projection;\n"
        "uniform mat4 transform;\n";
const char* gVS_Header_Uniforms_HasGradient = "uniform mat4 screenSpace;\n";
const char* gVS_Header_Uniforms_HasBitmap =
        "uniform mat4 textureTransform;\n"
        "uniform mediump vec2 textureDimension;\n";
const char* gVS_Header_Uniforms_HasRoundRectClip =
        "uniform mat4 roundRectInvTransform;\n"
        "uniform mediump vec4 roundRectInnerRectLTWH;\n"
        "uniform mediump float roundRectRadius;\n";
const char* gVS_Header_Varyings_HasTexture = "varying vec2 outTexCoords;\n";
const char* gVS_Header_Varyings_HasColors = "varying vec4 outColors;\n";
const char* gVS_Header_Varyings_HasVertexAlpha = "varying float alpha;\n";
const char* gVS_Header_Varyings_HasBitmap = "varying highp vec2 outBitmapTexCoords;\n";
const char* gVS_Header_Varyings_HasGradient[6] = {
        // Linear
        "varying highp vec2 linear;\n", "varying float linear;\n",

        // Circular
        "varying highp vec2 circular;\n", "varying highp vec2 circular;\n",

        // Sweep
        "varying highp vec2 sweep;\n", "varying highp vec2 sweep;\n",
};
const char* gVS_Header_Varyings_HasRoundRectClip = "varying mediump vec2 roundRectPos;\n";
const char* gVS_Main = "\nvoid main(void) {\n";
const char* gVS_Main_OutTexCoords = "    outTexCoords = texCoords;\n";
const char* gVS_Main_OutColors = "    outColors = colors;\n";
const char* gVS_Main_OutTransformedTexCoords =
        "    outTexCoords = (mainTextureTransform * vec4(texCoords, 0.0, 1.0)).xy;\n";
const char* gVS_Main_OutGradient[6] = {
        // Linear
        "    linear = vec2((screenSpace * position).x, 0.5);\n",
        "    linear = (screenSpace * position).x;\n",

        // Circular
        "    circular = (screenSpace * position).xy;\n",
        "    circular = (screenSpace * position).xy;\n",

        // Sweep
        "    sweep = (screenSpace * position).xy;\n", "    sweep = (screenSpace * position).xy;\n"};
const char* gVS_Main_OutBitmapTexCoords =
        "    outBitmapTexCoords = (textureTransform * position).xy * textureDimension;\n";
const char* gVS_Main_Position =
        "    vec4 transformedPosition = projection * transform * position;\n"
        "    gl_Position = transformedPosition;\n";

const char* gVS_Main_VertexAlpha = "    alpha = vtxAlpha;\n";

const char* gVS_Main_HasRoundRectClip =
        "    roundRectPos = ((roundRectInvTransform * transformedPosition).xy / roundRectRadius) - "
        "roundRectInnerRectLTWH.xy;\n";
const char* gVS_Footer = "}\n\n";

///////////////////////////////////////////////////////////////////////////////
// Fragment shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gFS_Header_Start = "#version 100\n";
const char* gFS_Header_Extension_FramebufferFetch =
        "#extension GL_NV_shader_framebuffer_fetch : enable\n\n";
const char* gFS_Header_Extension_ExternalTexture =
        "#extension GL_OES_EGL_image_external : require\n\n";
const char* gFS_Header = "precision mediump float;\n\n";
const char* gFS_Uniforms_Color = "uniform vec4 color;\n";
const char* gFS_Uniforms_TextureSampler = "uniform sampler2D baseSampler;\n";
const char* gFS_Uniforms_ExternalTextureSampler = "uniform samplerExternalOES baseSampler;\n";
const char* gFS_Uniforms_GradientSampler[2] = {
        "uniform vec2 screenSize;\n"
        "uniform sampler2D gradientSampler;\n",

        "uniform vec2 screenSize;\n"
        "uniform vec4 startColor;\n"
        "uniform vec4 endColor;\n"};
const char* gFS_Uniforms_BitmapSampler = "uniform sampler2D bitmapSampler;\n";
const char* gFS_Uniforms_BitmapExternalSampler = "uniform samplerExternalOES bitmapSampler;\n";
const char* gFS_Uniforms_ColorOp[3] = {
        // None
        "",
        // Matrix
        "uniform mat4 colorMatrix;\n"
        "uniform vec4 colorMatrixVector;\n",
        // PorterDuff
        "uniform vec4 colorBlend;\n"};

const char* gFS_Uniforms_HasRoundRectClip =
        "uniform mediump vec4 roundRectInnerRectLTWH;\n"
        "uniform mediump float roundRectRadius;\n";

const char* gFS_Uniforms_ColorSpaceConversion =
        // TODO: Should we use a 3D LUT to combine the matrix and transfer functions?
        // 32x32x32 fp16 LUTs (for scRGB output) are large and heavy to generate...
        "uniform mat3 colorSpaceMatrix;\n";

const char* gFS_Uniforms_TransferFunction[4] = {
        // In this order: g, a, b, c, d, e, f
        // See ColorSpace::TransferParameters
        // We'll use hardware sRGB conversion as much as possible
        "", "uniform float transferFunction[7];\n", "uniform float transferFunction[5];\n",
        "uniform float transferFunctionGamma;\n"};

const char* gFS_OETF[2] = {
        R"__SHADER__(
        vec4 OETF(const vec4 linear) {
            return linear;
        }
        )__SHADER__",
        // We expect linear data to be scRGB so we mirror the gamma function
        R"__SHADER__(
        vec4 OETF(const vec4 linear) {
            return vec4(sign(linear.rgb) * OETF_sRGB(abs(linear.rgb)), linear.a);
        }
        )__SHADER__"};

const char* gFS_ColorConvert[3] = {
        // Just OETF
        R"__SHADER__(
        vec4 colorConvert(const vec4 color) {
            return OETF(color);
        }
        )__SHADER__",
        // Full color conversion for opaque bitmaps
        R"__SHADER__(
        vec4 colorConvert(const vec4 color) {
            return OETF(vec4(colorSpaceMatrix * EOTF_Parametric(color.rgb), color.a));
        }
        )__SHADER__",
        // Full color conversion for translucent bitmaps
        // Note: 0.5/256=0.0019
        R"__SHADER__(
        vec4 colorConvert(in vec4 color) {
            color.rgb /= color.a + 0.0019;
            color = OETF(vec4(colorSpaceMatrix * EOTF_Parametric(color.rgb), color.a));
            color.rgb *= color.a + 0.0019;
            return color;
        }
        )__SHADER__",
};

const char* gFS_sRGB_TransferFunctions = R"__SHADER__(
        float OETF_sRGB(const float linear) {
            // IEC 61966-2-1:1999
            return linear <= 0.0031308 ? linear * 12.92 : (pow(linear, 1.0 / 2.4) * 1.055) - 0.055;
        }

        vec3 OETF_sRGB(const vec3 linear) {
            return vec3(OETF_sRGB(linear.r), OETF_sRGB(linear.g), OETF_sRGB(linear.b));
        }

        float EOTF_sRGB(float srgb) {
            // IEC 61966-2-1:1999
            return srgb <= 0.04045 ? srgb / 12.92 : pow((srgb + 0.055) / 1.055, 2.4);
        }
)__SHADER__";

const char* gFS_TransferFunction[4] = {
        // Conversion done by the texture unit (sRGB)
        R"__SHADER__(
        vec3 EOTF_Parametric(const vec3 x) {
            return x;
        }
        )__SHADER__",
        // Full transfer function
        // TODO: We should probably use a 1D LUT (256x1 with texelFetch() since input is 8 bit)
        // TODO: That would cause 3 dependent texture fetches. Is it worth it?
        R"__SHADER__(
        float EOTF_Parametric(float x) {
            return x <= transferFunction[4]
                  ? transferFunction[3] * x + transferFunction[6]
                  : pow(transferFunction[1] * x + transferFunction[2], transferFunction[0])
                          + transferFunction[5];
        }

        vec3 EOTF_Parametric(const vec3 x) {
            return vec3(EOTF_Parametric(x.r), EOTF_Parametric(x.g), EOTF_Parametric(x.b));
        }
        )__SHADER__",
        // Limited transfer function, e = f = 0.0
        R"__SHADER__(
        float EOTF_Parametric(float x) {
            return x <= transferFunction[4]
                  ? transferFunction[3] * x
                  : pow(transferFunction[1] * x + transferFunction[2], transferFunction[0]);
        }

        vec3 EOTF_Parametric(const vec3 x) {
            return vec3(EOTF_Parametric(x.r), EOTF_Parametric(x.g), EOTF_Parametric(x.b));
        }
        )__SHADER__",
        // Gamma transfer function, e = f = 0.0
        R"__SHADER__(
        vec3 EOTF_Parametric(const vec3 x) {
            return vec3(pow(x.r, transferFunctionGamma),
                        pow(x.g, transferFunctionGamma),
                        pow(x.b, transferFunctionGamma));
        }
        )__SHADER__"};

// Dithering must be done in the quantization space
// When we are writing to an sRGB framebuffer, we must do the following:
//     EOTF(OETF(color) + dither)
// The dithering pattern is generated with a triangle noise generator in the range [-1.0,1.0]
// TODO: Handle linear fp16 render targets
const char* gFS_GradientFunctions = R"__SHADER__(
        float triangleNoise(const highp vec2 n) {
            highp vec2 p = fract(n * vec2(5.3987, 5.4421));
            p += dot(p.yx, p.xy + vec2(21.5351, 14.3137));
            highp float xy = p.x * p.y;
            return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
        }
)__SHADER__";

const char* gFS_GradientPreamble[2] = {
        // Linear framebuffer
        R"__SHADER__(
        vec4 dither(const vec4 color) {
            return color + (triangleNoise(gl_FragCoord.xy * screenSize.xy) / 255.0);
        }
        )__SHADER__",
        // sRGB framebuffer
        R"__SHADER__(
        vec4 dither(const vec4 color) {
            vec3 dithered = sqrt(color.rgb) + (triangleNoise(gl_FragCoord.xy * screenSize.xy) / 255.0);
            return vec4(dithered * dithered, color.a);
        }
        )__SHADER__",
};

// Uses luminance coefficients from Rec.709 to choose the appropriate gamma
// The gamma() function assumes that bright text will be displayed on a dark
// background and that dark text will be displayed on bright background
// The gamma coefficient is chosen to thicken or thin the text accordingly
// The dot product used to compute the luminance could be approximated with
// a simple max(color.r, color.g, color.b)
const char* gFS_Gamma_Preamble = R"__SHADER__(
        #define GAMMA (%.2f)
        #define GAMMA_INV (%.2f)

        float gamma(float a, const vec3 color) {
            float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
            return pow(a, luminance < 0.5 ? GAMMA_INV : GAMMA);
        }
)__SHADER__";

const char* gFS_Main =
        "\nvoid main(void) {\n"
        "    vec4 fragColor;\n";

const char* gFS_Main_AddDither = "    fragColor = dither(fragColor);\n";

// General case
const char* gFS_Main_FetchColor = "    fragColor = color;\n";
const char* gFS_Main_ModulateColor = "    fragColor *= color.a;\n";
const char* gFS_Main_ApplyVertexAlphaLinearInterp = "    fragColor *= alpha;\n";
const char* gFS_Main_ApplyVertexAlphaShadowInterp =
        // map alpha through shadow alpha sampler
        "    fragColor *= texture2D(baseSampler, vec2(alpha, 0.5)).a;\n";
const char* gFS_Main_FetchTexture[2] = {
        // Don't modulate
        "    fragColor = colorConvert(texture2D(baseSampler, outTexCoords));\n",
        // Modulate
        "    fragColor = color * colorConvert(texture2D(baseSampler, outTexCoords));\n"};
const char* gFS_Main_FetchA8Texture[4] = {
        // Don't modulate
        "    fragColor = texture2D(baseSampler, outTexCoords);\n",
        "    fragColor = texture2D(baseSampler, outTexCoords);\n",
        // Modulate
        "    fragColor = color * texture2D(baseSampler, outTexCoords).a;\n",
        "    fragColor = color * gamma(texture2D(baseSampler, outTexCoords).a, color.rgb);\n",
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
        "    vec4 gradientColor = mix(startColor, endColor, clamp(index - floor(index), 0.0, "
        "1.0));\n"};
const char* gFS_Main_FetchBitmap =
        "    vec4 bitmapColor = colorConvert(texture2D(bitmapSampler, outBitmapTexCoords));\n";
const char* gFS_Main_FetchBitmapNpot =
        "    vec4 bitmapColor = colorConvert(texture2D(bitmapSampler, "
        "wrap(outBitmapTexCoords)));\n";
const char* gFS_Main_BlendShadersBG = "    fragColor = blendShaders(gradientColor, bitmapColor)";
const char* gFS_Main_BlendShadersGB = "    fragColor = blendShaders(bitmapColor, gradientColor)";
const char* gFS_Main_BlendShaders_Modulate[6] = {
        // Don't modulate
        ";\n", ";\n",
        // Modulate
        " * color.a;\n", " * color.a;\n",
        // Modulate with alpha 8 texture
        " * texture2D(baseSampler, outTexCoords).a;\n",
        " * gamma(texture2D(baseSampler, outTexCoords).a, color.rgb);\n",
};
const char* gFS_Main_GradientShader_Modulate[6] = {
        // Don't modulate
        "    fragColor = gradientColor;\n", "    fragColor = gradientColor;\n",
        // Modulate
        "    fragColor = gradientColor * color.a;\n", "    fragColor = gradientColor * color.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = gradientColor * texture2D(baseSampler, outTexCoords).a;\n",
        "    fragColor = gradientColor * gamma(texture2D(baseSampler, outTexCoords).a, "
        "gradientColor.rgb);\n",
};
const char* gFS_Main_BitmapShader_Modulate[6] = {
        // Don't modulate
        "    fragColor = bitmapColor;\n", "    fragColor = bitmapColor;\n",
        // Modulate
        "    fragColor = bitmapColor * color.a;\n", "    fragColor = bitmapColor * color.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = bitmapColor * texture2D(baseSampler, outTexCoords).a;\n",
        "    fragColor = bitmapColor * gamma(texture2D(baseSampler, outTexCoords).a, "
        "bitmapColor.rgb);\n",
};
const char* gFS_Main_FragColor = "    gl_FragColor = fragColor;\n";
const char* gFS_Main_FragColor_HasColors = "    gl_FragColor *= outColors;\n";
const char* gFS_Main_FragColor_Blend =
        "    gl_FragColor = blendFramebuffer(fragColor, gl_LastFragColor);\n";
const char* gFS_Main_FragColor_Blend_Swap =
        "    gl_FragColor = blendFramebuffer(gl_LastFragColor, fragColor);\n";
const char* gFS_Main_ApplyColorOp[3] = {
        // None
        "",
        // Matrix
        "    fragColor.rgb /= (fragColor.a + 0.0019);\n"  // un-premultiply
        "    fragColor *= colorMatrix;\n"
        "    fragColor += colorMatrixVector;\n"
        "    fragColor.rgb *= (fragColor.a + 0.0019);\n",  // re-premultiply
        // PorterDuff
        "    fragColor = blendColors(colorBlend, fragColor);\n"};

// Note: LTWH (left top width height) -> xyzw
// roundRectPos is now divided by roundRectRadius in vertex shader
// after we also subtract roundRectInnerRectLTWH.xy from roundRectPos
const char* gFS_Main_FragColor_HasRoundRectClip =
        "    mediump vec2 fragToLT = -roundRectPos;\n"
        "    mediump vec2 fragFromRB = roundRectPos - roundRectInnerRectLTWH.zw;\n"

        // since distance is divided by radius, it's in [0;1] so precision is not an issue
        // this also lets us clamp(0.0, 1.0) instead of max() which is cheaper on GPUs
        "    mediump vec2 dist = clamp(max(fragToLT, fragFromRB), 0.0, 1.0);\n"
        "    mediump float linearDist = clamp(roundRectRadius - (length(dist) * roundRectRadius), "
        "0.0, 1.0);\n"
        "    gl_FragColor *= linearDist;\n";

const char* gFS_Main_DebugHighlight = "    gl_FragColor.rgb = vec3(0.0, gl_FragColor.a, 0.0);\n";
const char* gFS_Footer = "}\n\n";

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
        "src.a * dst.a - 2.0 * (dst.a - dst.rgb) * (src.a - src.rgb) + src.rgb * (1.0 - dst.a) + "
        "dst.rgb * (1.0 - src.a), "
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

ProgramCache::ProgramCache(const Extensions& extensions)
        : mHasES3(extensions.getMajorGlVersion() >= 3)
        , mHasLinearBlending(extensions.hasLinearBlending()) {}

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
    shader.append(gVS_Main);
    {
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

static bool shaderOp(const ProgramDescription& description, String8& shader, const int modulateOp,
                     const char** snippets) {
    int op = description.hasAlpha8Texture ? MODULATE_OP_MODULATE_A8 : modulateOp;
    op = op * 2 + description.hasGammaCorrection;
    shader.append(snippets[op]);
    return description.hasAlpha8Texture;
}

String8 ProgramCache::generateFragmentShader(const ProgramDescription& description) {
    String8 shader(gFS_Header_Start);

    const bool blendFramebuffer = description.framebufferMode >= SkBlendMode::kPlus;
    if (blendFramebuffer) {
        shader.append(gFS_Header_Extension_FramebufferFetch);
    }
    if (description.hasExternalTexture ||
        (description.hasBitmap && description.isShaderBitmapExternal)) {
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
        shader.append(gFS_Uniforms_GradientSampler[description.isSimpleGradient]);
    }
    if (description.hasRoundRectClip) {
        shader.append(gFS_Uniforms_HasRoundRectClip);
    }

    if (description.hasGammaCorrection) {
        shader.appendFormat(gFS_Gamma_Preamble, Properties::textGamma,
                            1.0f / Properties::textGamma);
    }

    if (description.hasBitmap) {
        if (description.isShaderBitmapExternal) {
            shader.append(gFS_Uniforms_BitmapExternalSampler);
        } else {
            shader.append(gFS_Uniforms_BitmapSampler);
        }
    }
    shader.append(gFS_Uniforms_ColorOp[static_cast<int>(description.colorOp)]);

    if (description.hasColorSpaceConversion) {
        shader.append(gFS_Uniforms_ColorSpaceConversion);
    }
    shader.append(gFS_Uniforms_TransferFunction[static_cast<int>(description.transferFunction)]);

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
    if (description.useShaderBasedWrap) {
        generateTextureWrap(shader, description.bitmapWrapS, description.bitmapWrapT);
    }
    if (description.hasGradient || description.hasLinearTexture ||
        description.hasColorSpaceConversion) {
        shader.append(gFS_sRGB_TransferFunctions);
    }
    if (description.hasBitmap || ((description.hasTexture || description.hasExternalTexture) &&
                                  !description.hasAlpha8Texture)) {
        shader.append(gFS_TransferFunction[static_cast<int>(description.transferFunction)]);
        shader.append(
                gFS_OETF[(description.hasLinearTexture || description.hasColorSpaceConversion) &&
                         !mHasLinearBlending]);
        shader.append(gFS_ColorConvert[description.hasColorSpaceConversion
                                               ? 1 + description.hasTranslucentConversion
                                               : 0]);
    }
    if (description.hasGradient) {
        shader.append(gFS_GradientFunctions);
        shader.append(gFS_GradientPreamble[mHasLinearBlending]);
    }

    // Begin the shader
    shader.append(gFS_Main);
    {
        // Stores the result in fragColor directly
        if (description.hasTexture || description.hasExternalTexture) {
            if (description.hasAlpha8Texture) {
                if (!description.hasGradient && !description.hasBitmap) {
                    shader.append(gFS_Main_FetchA8Texture[modulateOp * 2 +
                                                          description.hasGammaCorrection]);
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
        }
        if (description.hasBitmap) {
            if (!description.useShaderBasedWrap) {
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
            applyModulate =
                    shaderOp(description, shader, modulateOp, gFS_Main_BlendShaders_Modulate);
        } else {
            if (description.hasGradient) {
                applyModulate =
                        shaderOp(description, shader, modulateOp, gFS_Main_GradientShader_Modulate);
            } else if (description.hasBitmap) {
                applyModulate =
                        shaderOp(description, shader, modulateOp, gFS_Main_BitmapShader_Modulate);
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

        if (description.hasGradient) {
            shader.append(gFS_Main_AddDither);
        }

        // Output the fragment
        if (!blendFramebuffer) {
            shader.append(gFS_Main_FragColor);
        } else {
            shader.append(!description.swapSrcDst ? gFS_Main_FragColor_Blend
                                                  : gFS_Main_FragColor_Blend_Swap);
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

void ProgramCache::generateBlend(String8& shader, const char* name, SkBlendMode mode) {
    shader.append("\nvec4 ");
    shader.append(name);
    shader.append("(vec4 src, vec4 dst) {\n");
    shader.append("    ");
    shader.append(gBlendOps[(int)mode]);
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

};  // namespace uirenderer
};  // namespace android
