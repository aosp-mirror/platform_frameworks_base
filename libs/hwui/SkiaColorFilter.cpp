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

#include "SkiaColorFilter.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Base color filter
///////////////////////////////////////////////////////////////////////////////

SkiaColorFilter::SkiaColorFilter(Type type, bool blend): mType(type), mBlend(blend) {
}

SkiaColorFilter::~SkiaColorFilter() {
}

///////////////////////////////////////////////////////////////////////////////
// Color matrix filter
///////////////////////////////////////////////////////////////////////////////

SkiaColorMatrixFilter::SkiaColorMatrixFilter(float* matrix, float* vector):
        SkiaColorFilter(kColorMatrix, true), mMatrix(matrix), mVector(vector) {
}

SkiaColorMatrixFilter::~SkiaColorMatrixFilter() {
    delete[] mMatrix;
    delete[] mVector;
}

void SkiaColorMatrixFilter::describe(ProgramDescription& description,
        const Extensions& extensions) {
    description.colorOp = ProgramDescription::kColorMatrix;
}

void SkiaColorMatrixFilter::setupProgram(Program* program) {
    glUniformMatrix4fv(program->getUniform("colorMatrix"), 1, GL_FALSE, &mMatrix[0]);
    glUniform4fv(program->getUniform("colorMatrixVector"), 1, mVector);
}

///////////////////////////////////////////////////////////////////////////////
// Lighting color filter
///////////////////////////////////////////////////////////////////////////////

SkiaLightingFilter::SkiaLightingFilter(int multiply, int add):
        SkiaColorFilter(kLighting, true) {
    mMulR = ((multiply >> 16) & 0xFF) / 255.0f;
    mMulG = ((multiply >>  8) & 0xFF) / 255.0f;
    mMulB = ((multiply      ) & 0xFF) / 255.0f;

    mAddR = ((add >> 16) & 0xFF) / 255.0f;
    mAddG = ((add >>  8) & 0xFF) / 255.0f;
    mAddB = ((add      ) & 0xFF) / 255.0f;
}

void SkiaLightingFilter::describe(ProgramDescription& description, const Extensions& extensions) {
    description.colorOp = ProgramDescription::kColorLighting;
}

void SkiaLightingFilter::setupProgram(Program* program) {
    glUniform4f(program->getUniform("lightingMul"), mMulR, mMulG, mMulB, 1.0f);
    glUniform4f(program->getUniform("lightingAdd"), mAddR, mAddG, mAddB, 0.0f);
}

///////////////////////////////////////////////////////////////////////////////
// Blend color filter
///////////////////////////////////////////////////////////////////////////////

SkiaBlendFilter::SkiaBlendFilter(int color, SkXfermode::Mode mode):
        SkiaColorFilter(kBlend, true), mMode(mode) {
    const int alpha = (color >> 24) & 0xFF;
    mA = alpha / 255.0f;
    mR = mA * ((color >> 16) & 0xFF) / 255.0f;
    mG = mA * ((color >>  8) & 0xFF) / 255.0f;
    mB = mA * ((color      ) & 0xFF) / 255.0f;
}

void SkiaBlendFilter::describe(ProgramDescription& description, const Extensions& extensions) {
    description.colorOp = ProgramDescription::kColorBlend;
    description.colorMode = mMode;
}

void SkiaBlendFilter::setupProgram(Program* program) {
    glUniform4f(program->getUniform("colorBlend"), mR, mG, mB, mA);
}

}; // namespace uirenderer
}; // namespace android
