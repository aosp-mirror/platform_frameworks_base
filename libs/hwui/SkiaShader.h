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

#include "FloatColor.h"
#include "Matrix.h"

#include <GLES2/gl2.h>
#include <SkShader.h>
#include <SkXfermode.h>
#include <cutils/compiler.h>

namespace android {
namespace uirenderer {

class Caches;
class Extensions;
class Layer;
class Texture;
struct ProgramDescription;

/**
 * Type of Skia shader in use.
 *
 * Note that kBitmap | kGradient = kCompose, since Compose implies
 * both its component types are in use simultaneously. No other
 * composition of multiple types is supported.
 */
enum SkiaShaderType {
    kNone_SkiaShaderType = 0,
    kBitmap_SkiaShaderType = 1,
    kGradient_SkiaShaderType = 2,
    kCompose_SkiaShaderType = kBitmap_SkiaShaderType | kGradient_SkiaShaderType,
    kLayer_SkiaShaderType = 4,
};

struct SkiaShaderData {
    SkiaShaderType skiaShaderType;
    struct BitmapShaderData {
        Texture* bitmapTexture;
        GLuint bitmapSampler;
        GLenum wrapS;
        GLenum wrapT;

        Matrix4 textureTransform;
        float textureDimension[2];
    } bitmapData;
    struct GradientShaderData {
        Matrix4 screenSpace;
        GLuint ditherSampler;

        // simple gradient
        FloatColor startColor;
        FloatColor endColor;

        // complex gradient
        Texture* gradientTexture;
        GLuint gradientSampler;
        GLenum wrapST;

    } gradientData;
    struct LayerShaderData {
        Layer* layer;
        GLuint bitmapSampler;
        GLenum wrapS;
        GLenum wrapT;

        Matrix4 textureTransform;
        float textureDimension[2];
    } layerData;
};

class SkiaShader {
public:
    static void store(Caches& caches, const SkShader& shader, const Matrix4& modelViewMatrix,
            GLuint* textureUnit, ProgramDescription* description,
            SkiaShaderData* outData);
    static void apply(Caches& caches, const SkiaShaderData& data);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SKIA_SHADER_H
