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

#define LOG_TAG "OpenGLRenderer"

#include <utils/String8.h>

#include "Caches.h"
#include "ProgramCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define MODULATE_OP_NO_MODULATE 0
#define MODULATE_OP_MODULATE 1
#define MODULATE_OP_MODULATE_A8 2

///////////////////////////////////////////////////////////////////////////////
// Vertex shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gVS_Header_Attributes =
        "attribute vec4 position;\n";
const char* gVS_Header_Attributes_TexCoords =
        "attribute vec2 texCoords;\n";
const char* gVS_Header_Attributes_AAParameters =
        "attribute float vtxWidth;\n"
        "attribute float vtxLength;\n";
const char* gVS_Header_Uniforms_TextureTransform =
        "uniform mat4 mainTextureTransform;\n";
const char* gVS_Header_Uniforms =
        "uniform mat4 transform;\n";
const char* gVS_Header_Uniforms_IsPoint =
        "uniform mediump float pointSize;\n";
const char* gVS_Header_Uniforms_HasGradient[3] = {
        // Linear
        "uniform mat4 screenSpace;\n",
        // Circular
        "uniform mat4 screenSpace;\n",
        // Sweep
        "uniform mat4 screenSpace;\n"
};
const char* gVS_Header_Uniforms_HasBitmap =
        "uniform mat4 textureTransform;\n"
        "uniform mediump vec2 textureDimension;\n";
const char* gVS_Header_Varyings_HasTexture =
        "varying vec2 outTexCoords;\n";
const char* gVS_Header_Varyings_IsAA =
        "varying float widthProportion;\n"
        "varying float lengthProportion;\n";
const char* gVS_Header_Varyings_HasBitmap[2] = {
        // Default precision
        "varying vec2 outBitmapTexCoords;\n",
        // High precision
        "varying highp vec2 outBitmapTexCoords;\n"
};
const char* gVS_Header_Varyings_PointHasBitmap[2] = {
        // Default precision
        "varying vec2 outPointBitmapTexCoords;\n",
        // High precision
        "varying highp vec2 outPointBitmapTexCoords;\n"
};
const char* gVS_Header_Varyings_HasGradient[3] = {
        // Linear
        "varying vec2 linear;\n",
        // Circular
        "varying vec2 circular;\n",
        // Sweep
        "varying vec2 sweep;\n"
};
const char* gVS_Main =
        "\nvoid main(void) {\n";
const char* gVS_Main_OutTexCoords =
        "    outTexCoords = texCoords;\n";
const char* gVS_Main_OutTransformedTexCoords =
        "    outTexCoords = (mainTextureTransform * vec4(texCoords, 0.0, 1.0)).xy;\n";
const char* gVS_Main_OutGradient[3] = {
        // Linear
        "    linear = vec2((screenSpace * position).x, 0.5);\n",
        // Circular
        "    circular = (screenSpace * position).xy;\n",
        // Sweep
        "    sweep = (screenSpace * position).xy;\n"
};
const char* gVS_Main_OutBitmapTexCoords =
        "    outBitmapTexCoords = (textureTransform * position).xy * textureDimension;\n";
const char* gVS_Main_OutPointBitmapTexCoords =
        "    outPointBitmapTexCoords = (textureTransform * position).xy * textureDimension;\n";
const char* gVS_Main_Position =
        "    gl_Position = transform * position;\n";
const char* gVS_Main_PointSize =
        "    gl_PointSize = pointSize;\n";
const char* gVS_Main_AA =
        "    widthProportion = vtxWidth;\n"
        "    lengthProportion = vtxLength;\n";
const char* gVS_Footer =
        "}\n\n";

///////////////////////////////////////////////////////////////////////////////
// Fragment shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gFS_Header_Extension_FramebufferFetch =
        "#extension GL_NV_shader_framebuffer_fetch : enable\n\n";
const char* gFS_Header_Extension_ExternalTexture =
        "#extension GL_OES_EGL_image_external : require\n\n";
const char* gFS_Header =
        "precision mediump float;\n\n";
const char* gFS_Uniforms_Color =
        "uniform vec4 color;\n";
const char* gFS_Uniforms_AA =
        "uniform float boundaryWidth;\n"
        "uniform float inverseBoundaryWidth;\n"
        "uniform float boundaryLength;\n"
        "uniform float inverseBoundaryLength;\n";
const char* gFS_Header_Uniforms_PointHasBitmap =
        "uniform vec2 textureDimension;\n"
        "uniform float pointSize;\n";
const char* gFS_Uniforms_TextureSampler =
        "uniform sampler2D sampler;\n";
const char* gFS_Uniforms_ExternalTextureSampler =
        "uniform samplerExternalOES sampler;\n";
const char* gFS_Uniforms_GradientSampler[3] = {
        // Linear
        "uniform sampler2D gradientSampler;\n",
        // Circular
        "uniform sampler2D gradientSampler;\n",
        // Sweep
        "uniform sampler2D gradientSampler;\n"
};
const char* gFS_Uniforms_BitmapSampler =
        "uniform sampler2D bitmapSampler;\n";
const char* gFS_Uniforms_ColorOp[4] = {
        // None
        "",
        // Matrix
        "uniform mat4 colorMatrix;\n"
        "uniform vec4 colorMatrixVector;\n",
        // Lighting
        "uniform vec4 lightingMul;\n"
        "uniform vec4 lightingAdd;\n",
        // PorterDuff
        "uniform vec4 colorBlend;\n"
};
const char* gFS_Main =
        "\nvoid main(void) {\n"
        "    lowp vec4 fragColor;\n";

const char* gFS_Main_PointBitmapTexCoords =
        "    vec2 outBitmapTexCoords = outPointBitmapTexCoords + "
        "((gl_PointCoord - vec2(0.5, 0.5)) * textureDimension * vec2(pointSize, pointSize));\n";

// Fast cases
const char* gFS_Fast_SingleColor =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color;\n"
        "}\n\n";
const char* gFS_Fast_SingleTexture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = texture2D(sampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleModulateTexture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color.a * texture2D(sampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleA8Texture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = texture2D(sampler, outTexCoords);\n"
        "}\n\n";
const char* gFS_Fast_SingleModulateA8Texture =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color * texture2D(sampler, outTexCoords).a;\n"
        "}\n\n";
const char* gFS_Fast_SingleGradient =
        "\nvoid main(void) {\n"
        "    gl_FragColor = texture2D(gradientSampler, linear);\n"
        "}\n\n";
const char* gFS_Fast_SingleModulateGradient =
        "\nvoid main(void) {\n"
        "    gl_FragColor = color.a * texture2D(gradientSampler, linear);\n"
        "}\n\n";

// General case
const char* gFS_Main_FetchColor =
        "    fragColor = color;\n";
const char* gFS_Main_ModulateColor =
        "    fragColor *= color.a;\n";
const char* gFS_Main_AccountForAA =
        "    if (widthProportion < boundaryWidth) {\n"
        "        fragColor *= (widthProportion * inverseBoundaryWidth);\n"
        "    } else if (widthProportion > (1.0 - boundaryWidth)) {\n"
        "        fragColor *= ((1.0 - widthProportion) * inverseBoundaryWidth);\n"
        "    }\n"
        "    if (lengthProportion < boundaryLength) {\n"
        "        fragColor *= (lengthProportion * inverseBoundaryLength);\n"
        "    } else if (lengthProportion > (1.0 - boundaryLength)) {\n"
        "        fragColor *= ((1.0 - lengthProportion) * inverseBoundaryLength);\n"
        "    }\n";
const char* gFS_Main_FetchTexture[2] = {
        // Don't modulate
        "    fragColor = texture2D(sampler, outTexCoords);\n",
        // Modulate
        "    fragColor = color * texture2D(sampler, outTexCoords);\n"
};
const char* gFS_Main_FetchA8Texture[2] = {
        // Don't modulate
        "    fragColor = texture2D(sampler, outTexCoords);\n",
        // Modulate
        "    fragColor = color * texture2D(sampler, outTexCoords).a;\n"
};
const char* gFS_Main_FetchGradient[3] = {
        // Linear
        "    vec4 gradientColor = texture2D(gradientSampler, linear);\n",
        // Circular
        "    float index = length(circular);\n"
        "    vec4 gradientColor = texture2D(gradientSampler, vec2(index, 0.5));\n",
        // Sweep
        "    float index = atan(sweep.y, sweep.x) * 0.15915494309; // inv(2 * PI)\n"
        "    vec4 gradientColor = texture2D(gradientSampler, vec2(index - floor(index), 0.5));\n"
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
        " * fragColor.a;\n",
        // Modulate with alpha 8 texture
        " * texture2D(sampler, outTexCoords).a;\n"
};
const char* gFS_Main_GradientShader_Modulate[3] = {
        // Don't modulate
        "    fragColor = gradientColor;\n",
        // Modulate
        "    fragColor = gradientColor * fragColor.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = gradientColor * texture2D(sampler, outTexCoords).a;\n"
    };
const char* gFS_Main_BitmapShader_Modulate[3] = {
        // Don't modulate
        "    fragColor = bitmapColor;\n",
        // Modulate
        "    fragColor = bitmapColor * fragColor.a;\n",
        // Modulate with alpha 8 texture
        "    fragColor = bitmapColor * texture2D(sampler, outTexCoords).a;\n"
    };
const char* gFS_Main_FragColor =
        "    gl_FragColor = fragColor;\n";
const char* gFS_Main_FragColor_Blend =
        "    gl_FragColor = blendFramebuffer(fragColor, gl_LastFragColor);\n";
const char* gFS_Main_FragColor_Blend_Swap =
        "    gl_FragColor = blendFramebuffer(gl_LastFragColor, fragColor);\n";
const char* gFS_Main_ApplyColorOp[4] = {
        // None
        "",
        // Matrix
        // TODO: Fix premultiplied alpha computations for color matrix
        "    fragColor *= colorMatrix;\n"
        "    fragColor += colorMatrixVector;\n"
        "    fragColor.rgb *= fragColor.a;\n",
        // Lighting
        "    float lightingAlpha = fragColor.a;\n"
        "    fragColor = min(fragColor * lightingMul + (lightingAdd * lightingAlpha), lightingAlpha);\n"
        "    fragColor.a = lightingAlpha;\n",
        // PorterDuff
        "    fragColor = blendColors(colorBlend, fragColor);\n"
};
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
        // Add
        "return min(src + dst, 1.0);\n",
        // Multiply
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

ProgramCache::ProgramCache() {
}

ProgramCache::~ProgramCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Cache management
///////////////////////////////////////////////////////////////////////////////

void ProgramCache::clear() {
    PROGRAM_LOGD("Clearing program cache");

    size_t count = mCache.size();
    for (size_t i = 0; i < count; i++) {
        delete mCache.valueAt(i);
    }
    mCache.clear();
}

Program* ProgramCache::get(const ProgramDescription& description) {
    programid key = description.key();
    ssize_t index = mCache.indexOfKey(key);
    Program* program = NULL;
    if (index < 0) {
        description.log("Could not find program");
        program = generateProgram(description, key);
        mCache.add(key, program);
    } else {
        program = mCache.valueAt(index);
    }
    return program;
}

///////////////////////////////////////////////////////////////////////////////
// Program generation
///////////////////////////////////////////////////////////////////////////////

Program* ProgramCache::generateProgram(const ProgramDescription& description, programid key) {
    String8 vertexShader = generateVertexShader(description);
    String8 fragmentShader = generateFragmentShader(description);

    Program* program = new Program(description, vertexShader.string(), fragmentShader.string());
    return program;
}

String8 ProgramCache::generateVertexShader(const ProgramDescription& description) {
    // Add attributes
    String8 shader(gVS_Header_Attributes);
    if (description.hasTexture || description.hasExternalTexture) {
        shader.append(gVS_Header_Attributes_TexCoords);
    }
    if (description.isAA) {
        shader.append(gVS_Header_Attributes_AAParameters);
    }
    // Uniforms
    shader.append(gVS_Header_Uniforms);
    if (description.hasTextureTransform) {
        shader.append(gVS_Header_Uniforms_TextureTransform);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Uniforms_HasGradient[description.gradientType]);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Uniforms_HasBitmap);
    }
    if (description.isPoint) {
        shader.append(gVS_Header_Uniforms_IsPoint);
    }
    // Varyings
    if (description.hasTexture || description.hasExternalTexture) {
        shader.append(gVS_Header_Varyings_HasTexture);
    }
    if (description.isAA) {
        shader.append(gVS_Header_Varyings_IsAA);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient[description.gradientType]);
    }
    if (description.hasBitmap) {
        int index = Caches::getInstance().extensions.needsHighpTexCoords() ? 1 : 0;
        shader.append(description.isPoint ?
                gVS_Header_Varyings_PointHasBitmap[index] :
                gVS_Header_Varyings_HasBitmap[index]);
    }

    // Begin the shader
    shader.append(gVS_Main); {
        if (description.hasTextureTransform) {
            shader.append(gVS_Main_OutTransformedTexCoords);
        } else if (description.hasTexture || description.hasExternalTexture) {
            shader.append(gVS_Main_OutTexCoords);
        }
        if (description.isAA) {
            shader.append(gVS_Main_AA);
        }
        if (description.hasGradient) {
            shader.append(gVS_Main_OutGradient[description.gradientType]);
        }
        if (description.hasBitmap) {
            shader.append(description.isPoint ?
                    gVS_Main_OutPointBitmapTexCoords :
                    gVS_Main_OutBitmapTexCoords);
        }
        if (description.isPoint) {
            shader.append(gVS_Main_PointSize);
        }
        // Output transformed position
        shader.append(gVS_Main_Position);
    }
    // End the shader
    shader.append(gVS_Footer);

    PROGRAM_LOGD("*** Generated vertex shader:\n\n%s", shader.string());

    return shader;
}

String8 ProgramCache::generateFragmentShader(const ProgramDescription& description) {
    String8 shader;

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
    if (description.isAA) {
        shader.append(gVS_Header_Varyings_IsAA);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient[description.gradientType]);
    }
    if (description.hasBitmap) {
        int index = Caches::getInstance().extensions.needsHighpTexCoords() ? 1 : 0;
        shader.append(description.isPoint ?
                gVS_Header_Varyings_PointHasBitmap[index] :
                gVS_Header_Varyings_HasBitmap[index]);
    }

    // Uniforms
    int modulateOp = MODULATE_OP_NO_MODULATE;
    const bool singleColor = !description.hasTexture && !description.hasExternalTexture &&
            !description.hasGradient && !description.hasBitmap;

    if (description.modulate || singleColor) {
        shader.append(gFS_Uniforms_Color);
        if (!singleColor) modulateOp = MODULATE_OP_MODULATE;
    }
    if (description.hasTexture) {
        shader.append(gFS_Uniforms_TextureSampler);
    } else if (description.hasExternalTexture) {
        shader.append(gFS_Uniforms_ExternalTextureSampler);
    }
    if (description.isAA) {
        shader.append(gFS_Uniforms_AA);
    }
    if (description.hasGradient) {
        shader.append(gFS_Uniforms_GradientSampler[description.gradientType]);
    }
    if (description.hasBitmap && description.isPoint) {
        shader.append(gFS_Header_Uniforms_PointHasBitmap);
    }

    // Optimization for common cases
    if (!description.isAA && !blendFramebuffer &&
            description.colorOp == ProgramDescription::kColorNone && !description.isPoint) {
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
                shader.append(gFS_Fast_SingleGradient);
            } else {
                shader.append(gFS_Fast_SingleModulateGradient);
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
    shader.append(gFS_Uniforms_ColorOp[description.colorOp]);

    // Generate required functions
    if (description.hasGradient && description.hasBitmap) {
        generateBlend(shader, "blendShaders", description.shadersMode);
    }
    if (description.colorOp == ProgramDescription::kColorBlend) {
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
            if ((!description.hasGradient && !description.hasBitmap) || description.modulate) {
                shader.append(gFS_Main_FetchColor);
            }
        }
        if (description.isAA) {
            shader.append(gFS_Main_AccountForAA);
        }
        if (description.hasGradient) {
            shader.append(gFS_Main_FetchGradient[description.gradientType]);
        }
        if (description.hasBitmap) {
            if (description.isPoint) {
                shader.append(gFS_Main_PointBitmapTexCoords);
            }
            if (!description.isBitmapNpot) {
                shader.append(gFS_Main_FetchBitmap);
            } else {
                shader.append(gFS_Main_FetchBitmapNpot);
            }
        }
        bool applyModulate = false;
        // Case when we have two shaders set
        if (description.hasGradient && description.hasBitmap) {
            int op = description.hasAlpha8Texture ? MODULATE_OP_MODULATE_A8 : modulateOp;
            if (description.isBitmapFirst) {
                shader.append(gFS_Main_BlendShadersBG);
            } else {
                shader.append(gFS_Main_BlendShadersGB);
            }
            shader.append(gFS_Main_BlendShaders_Modulate[op]);
            applyModulate = true;
        } else {
            if (description.hasGradient) {
                int op = description.hasAlpha8Texture ? MODULATE_OP_MODULATE_A8 : modulateOp;
                shader.append(gFS_Main_GradientShader_Modulate[op]);
                applyModulate = true;
            } else if (description.hasBitmap) {
                int op = description.hasAlpha8Texture ? MODULATE_OP_MODULATE_A8 : modulateOp;
                shader.append(gFS_Main_BitmapShader_Modulate[op]);
                applyModulate = true;
            }
        }
        if (description.modulate && applyModulate) {
            shader.append(gFS_Main_ModulateColor);
        }
        // Apply the color op if needed
        shader.append(gFS_Main_ApplyColorOp[description.colorOp]);
        // Output the fragment
        if (!blendFramebuffer) {
            shader.append(gFS_Main_FragColor);
        } else {
            shader.append(!description.swapSrcDst ?
                    gFS_Main_FragColor_Blend : gFS_Main_FragColor_Blend_Swap);
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
    shader.append("\nvec2 wrap(vec2 texCoords) {\n");
    if (wrapS == GL_MIRRORED_REPEAT) {
        shader.append("    float xMod2 = mod(texCoords.x, 2.0);\n");
        shader.append("    if (xMod2 > 1.0) xMod2 = 2.0 - xMod2;\n");
    }
    if (wrapT == GL_MIRRORED_REPEAT) {
        shader.append("    float yMod2 = mod(texCoords.y, 2.0);\n");
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
        PROGRAM_LOGD("%s", line.string());
        index++;
        str += (index - lastIndex);
        lastIndex = index;
    }
}

}; // namespace uirenderer
}; // namespace android
