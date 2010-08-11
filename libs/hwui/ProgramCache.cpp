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

#include "ProgramCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Vertex shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gVS_Header_Attributes =
        "attribute vec4 position;\n";
const char* gVS_Header_Attributes_TexCoords =
        "attribute vec2 texCoords;\n";
const char* gVS_Header_Uniforms =
        "uniform mat4 transform;\n";
const char* gVS_Header_Uniforms_HasGradient =
        "uniform float gradientLength;\n"
        "uniform vec2 gradient;\n"
        "uniform vec2 gradientStart;\n"
        "uniform mat4 screenSpace;\n";
const char* gVS_Header_Uniforms_HasBitmap =
        "uniform mat4 textureTransform;\n"
        "uniform vec2 textureDimension;\n";
const char* gVS_Header_Varyings_HasTexture =
        "varying vec2 outTexCoords;\n";
const char* gVS_Header_Varyings_HasBitmap =
        "varying vec2 outBitmapTexCoords;\n";
const char* gVS_Header_Varyings_HasGradient =
        "varying float index;\n";
const char* gVS_Main =
        "\nvoid main(void) {\n";
const char* gVS_Main_OutTexCoords =
        "    outTexCoords = texCoords;\n";
const char* gVS_Main_OutGradientIndex =
        "    vec4 location = screenSpace * position;\n"
        "    index = dot(location.xy - gradientStart, gradient) * gradientLength;\n";
const char* gVS_Main_OutBitmapTexCoords =
        "    vec4 bitmapCoords = textureTransform * position;\n"
        "    outBitmapTexCoords = bitmapCoords.xy * textureDimension;\n";
const char* gVS_Main_Position =
        "    gl_Position = transform * position;\n";
const char* gVS_Footer =
        "}\n\n";

///////////////////////////////////////////////////////////////////////////////
// Fragment shaders snippets
///////////////////////////////////////////////////////////////////////////////

const char* gFS_Header =
        "precision mediump float;\n\n";
const char* gFS_Uniforms_Color =
        "uniform vec4 color;\n";
const char* gFS_Uniforms_TextureSampler =
        "uniform sampler2D sampler;\n";
const char* gFS_Uniforms_GradientSampler =
        "uniform sampler2D gradientSampler;\n";
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
const char* gFS_Main_FetchColor =
        "    fragColor = color;\n";
const char* gFS_Main_FetchTexture =
        "    fragColor = color * texture2D(sampler, outTexCoords);\n";
const char* gFS_Main_FetchA8Texture =
        "    fragColor = color * texture2D(sampler, outTexCoords).a;\n";
const char* gFS_Main_FetchGradient =
        "    vec4 gradientColor = texture2D(gradientSampler, vec2(index, 0.5));\n";
const char* gFS_Main_FetchBitmap =
        "    vec4 bitmapColor = texture2D(bitmapSampler, outBitmapTexCoords);\n";
const char* gFS_Main_FetchBitmapNpot =
        "    vec4 bitmapColor = texture2D(bitmapSampler, wrap(outBitmapTexCoords));\n";
const char* gFS_Main_BlendShadersBG =
        "    fragColor = blendShaders(gradientColor, bitmapColor)";
const char* gFS_Main_BlendShadersGB =
        "    fragColor = blendShaders(bitmapColor, gradientColor)";
const char* gFS_Main_BlendShaders_Modulate =
        " * fragColor.a;\n";
const char* gFS_Main_GradientShader_Modulate =
        "    fragColor = gradientColor * fragColor.a;\n";
const char* gFS_Main_BitmapShader_Modulate =
        "    fragColor = bitmapColor * fragColor.a;\n";
const char* gFS_Main_FragColor =
        "    gl_FragColor = fragColor;\n";
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
        PROGRAM_LOGD("Could not find program with key 0x%x", key);
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

    Program* program = new Program(vertexShader.string(), fragmentShader.string());
    return program;
}

String8 ProgramCache::generateVertexShader(const ProgramDescription& description) {
    // Add attributes
    String8 shader(gVS_Header_Attributes);
    if (description.hasTexture) {
        shader.append(gVS_Header_Attributes_TexCoords);
    }
    // Uniforms
    shader.append(gVS_Header_Uniforms);
    if (description.hasGradient) {
        shader.append(gVS_Header_Uniforms_HasGradient);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Uniforms_HasBitmap);
    }
    // Varyings
    if (description.hasTexture) {
        shader.append(gVS_Header_Varyings_HasTexture);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Varyings_HasBitmap);
    }

    // Begin the shader
    shader.append(gVS_Main); {
        if (description.hasTexture) {
            shader.append(gVS_Main_OutTexCoords);
        }
        if (description.hasGradient) {
            shader.append(gVS_Main_OutGradientIndex);
        }
        if (description.hasBitmap) {
            shader.append(gVS_Main_OutBitmapTexCoords);
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
    // Set the default precision
    String8 shader(gFS_Header);

    // Varyings
    if (description.hasTexture) {
        shader.append(gVS_Header_Varyings_HasTexture);
    }
    if (description.hasGradient) {
        shader.append(gVS_Header_Varyings_HasGradient);
    }
    if (description.hasBitmap) {
        shader.append(gVS_Header_Varyings_HasBitmap);
    }


    // Uniforms
    shader.append(gFS_Uniforms_Color);
    if (description.hasTexture) {
        shader.append(gFS_Uniforms_TextureSampler);
    }
    if (description.hasGradient) {
        shader.append(gFS_Uniforms_GradientSampler);
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
    if (description.isBitmapNpot) {
        generateTextureWrap(shader, description.bitmapWrapS, description.bitmapWrapT);
    }

    // Begin the shader
    shader.append(gFS_Main); {
        // Stores the result in fragColor directly
        if (description.hasTexture) {
            if (description.hasAlpha8Texture) {
                shader.append(gFS_Main_FetchA8Texture);
            } else {
                shader.append(gFS_Main_FetchTexture);
            }
        } else {
            shader.append(gFS_Main_FetchColor);
        }
        if (description.hasGradient) {
            shader.append(gFS_Main_FetchGradient);
        }
        if (description.hasBitmap) {
            if (!description.isBitmapNpot) {
                shader.append(gFS_Main_FetchBitmap);
            } else {
                shader.append(gFS_Main_FetchBitmapNpot);
            }
        }
        // Case when we have two shaders set
        if (description.hasGradient && description.hasBitmap) {
            if (description.isBitmapFirst) {
                shader.append(gFS_Main_BlendShadersBG);
            } else {
                shader.append(gFS_Main_BlendShadersGB);
            }
            shader.append(gFS_Main_BlendShaders_Modulate);
        } else {
            if (description.hasGradient) {
                shader.append(gFS_Main_GradientShader_Modulate);
            } else if (description.hasBitmap) {
                shader.append(gFS_Main_BitmapShader_Modulate);
            }
        }
        // Apply the color op if needed
        shader.append(gFS_Main_ApplyColorOp[description.colorOp]);
        // Output the fragment
        shader.append(gFS_Main_FragColor);
    }
    // End the shader
    shader.append(gFS_Footer);

    if (DEBUG_PROGRAM_CACHE) {
        PROGRAM_LOGD("*** Generated fragment shader:\n\n");
        printLongString(shader);
    }

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
