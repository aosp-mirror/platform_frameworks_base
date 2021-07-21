/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

#include <SkCanvas.h>
#include <SkDrawable.h>
#include <SkRuntimeEffect.h>
#include <math.h>
#include <utils/RefBase.h>
#include "CanvasProperty.h"
#include "CanvasTransform.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

class AnimatedRoundRect : public SkDrawable {
public:
    AnimatedRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                      uirenderer::CanvasPropertyPrimitive* top,
                      uirenderer::CanvasPropertyPrimitive* right,
                      uirenderer::CanvasPropertyPrimitive* bottom,
                      uirenderer::CanvasPropertyPrimitive* rx,
                      uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* p)
            : mLeft(left), mTop(top), mRight(right), mBottom(bottom), mRx(rx), mRy(ry), mPaint(p) {}

protected:
    virtual SkRect onGetBounds() override {
        return SkRect::MakeLTRB(mLeft->value, mTop->value, mRight->value, mBottom->value);
    }
    virtual void onDraw(SkCanvas* canvas) override {
        SkRect rect = SkRect::MakeLTRB(mLeft->value, mTop->value, mRight->value, mBottom->value);
        canvas->drawRoundRect(rect, mRx->value, mRy->value, mPaint->value);
    }

private:
    sp<uirenderer::CanvasPropertyPrimitive> mLeft;
    sp<uirenderer::CanvasPropertyPrimitive> mTop;
    sp<uirenderer::CanvasPropertyPrimitive> mRight;
    sp<uirenderer::CanvasPropertyPrimitive> mBottom;
    sp<uirenderer::CanvasPropertyPrimitive> mRx;
    sp<uirenderer::CanvasPropertyPrimitive> mRy;
    sp<uirenderer::CanvasPropertyPaint> mPaint;
};

struct RippleDrawableParams {
    sp<uirenderer::CanvasPropertyPrimitive> x;
    sp<uirenderer::CanvasPropertyPrimitive> y;
    sp<uirenderer::CanvasPropertyPrimitive> radius;
    sp<uirenderer::CanvasPropertyPrimitive> progress;
    sp<uirenderer::CanvasPropertyPrimitive> turbulencePhase;
    SkColor color;
    sp<uirenderer::CanvasPropertyPaint> paint;
    SkRuntimeShaderBuilder effectBuilder;
};

class AnimatedRippleDrawable {
public:
    static void draw(SkCanvas* canvas, const RippleDrawableParams& params) {
        auto& effectBuilder = const_cast<SkRuntimeShaderBuilder&>(params.effectBuilder);

        setUniform2f(effectBuilder, "in_origin", params.x->value, params.y->value);
        setUniform(effectBuilder, "in_radius", params.radius);
        setUniform(effectBuilder, "in_progress", params.progress);
        setUniform(effectBuilder, "in_turbulencePhase", params.turbulencePhase);
        setUniform(effectBuilder, "in_noisePhase", params.turbulencePhase->value * 0.001);

        SkRuntimeShaderBuilder::BuilderUniform uniform = effectBuilder.uniform("in_color");
        if (uniform.fVar != nullptr) {
            uniform = SkV4{SkColorGetR(params.color) / 255.0f, SkColorGetG(params.color) / 255.0f,
                           SkColorGetB(params.color) / 255.0f, SkColorGetA(params.color) / 255.0f};
        }

        const float CIRCLE_X_1 = 0.01 * cos(SCALE * 0.55);
        const float CIRCLE_Y_1 = 0.01 * sin(SCALE * 0.55);
        const float CIRCLE_X_2 = -0.0066 * cos(SCALE * 0.45);
        const float CIRCLE_Y_2 = -0.0066 * sin(SCALE * 0.45);
        const float CIRCLE_X_3 = -0.0066 * cos(SCALE * 0.35);
        const float CIRCLE_Y_3 = -0.0066 * sin(SCALE * 0.35);

        //
        // Keep in sync with:
        // frameworks/base/graphics/java/android/graphics/drawable/RippleShader.java
        //
        const float turbulencePhase = params.turbulencePhase->value;
        setUniform2f(effectBuilder, "in_tCircle1", SCALE * 0.5 + (turbulencePhase * CIRCLE_X_1),
                     SCALE * 0.5 + (turbulencePhase * CIRCLE_Y_1));
        setUniform2f(effectBuilder, "in_tCircle2", SCALE * 0.2 + (turbulencePhase * CIRCLE_X_2),
                     SCALE * 0.2 + (turbulencePhase * CIRCLE_Y_2));
        setUniform2f(effectBuilder, "in_tCircle3", SCALE + (turbulencePhase * CIRCLE_X_3),
                     SCALE + (turbulencePhase * CIRCLE_Y_3));
        const float rotation1 = turbulencePhase * PI_ROTATE_RIGHT + 1.7 * PI;
        setUniform2f(effectBuilder, "in_tRotation1", cos(rotation1), sin(rotation1));
        const float rotation2 = turbulencePhase * PI_ROTATE_LEFT + 2 * PI;
        setUniform2f(effectBuilder, "in_tRotation2", cos(rotation2), sin(rotation2));
        const float rotation3 = turbulencePhase * PI_ROTATE_RIGHT + 2.75 * PI;
        setUniform2f(effectBuilder, "in_tRotation3", cos(rotation3), sin(rotation3));

        params.paint->value.setShader(effectBuilder.makeShader(nullptr, false));
        canvas->drawCircle(params.x->value, params.y->value, params.radius->value,
                           params.paint->value);
    }

private:
    static constexpr float PI = 3.1415926535897932384626;
    static constexpr float PI_ROTATE_RIGHT = PI * 0.0078125;
    static constexpr float PI_ROTATE_LEFT = PI * -0.0078125;
    static constexpr float SCALE = 1.5;

    static void setUniform(SkRuntimeShaderBuilder& effectBuilder, const char* name,
                           sp<uirenderer::CanvasPropertyPrimitive> property) {
        SkRuntimeShaderBuilder::BuilderUniform uniform = effectBuilder.uniform(name);
        if (uniform.fVar != nullptr) {
            uniform = property->value;
        }
    }

    static void setUniform(SkRuntimeShaderBuilder& effectBuilder, const char* name, float value) {
        SkRuntimeShaderBuilder::BuilderUniform uniform = effectBuilder.uniform(name);
        if (uniform.fVar != nullptr) {
            uniform = value;
        }
    }

    static void setUniform2f(SkRuntimeShaderBuilder& effectBuilder, const char* name, float a,
                             float b) {
        SkRuntimeShaderBuilder::BuilderUniform uniform = effectBuilder.uniform(name);
        if (uniform.fVar != nullptr) {
            uniform = SkV2{a, b};
        }
    }
};

class AnimatedCircle : public SkDrawable {
public:
    AnimatedCircle(uirenderer::CanvasPropertyPrimitive* x, uirenderer::CanvasPropertyPrimitive* y,
                   uirenderer::CanvasPropertyPrimitive* radius,
                   uirenderer::CanvasPropertyPaint* paint)
            : mX(x), mY(y), mRadius(radius), mPaint(paint) {}

protected:
    virtual SkRect onGetBounds() override {
        const float x = mX->value;
        const float y = mY->value;
        const float radius = mRadius->value;
        return SkRect::MakeLTRB(x - radius, y - radius, x + radius, y + radius);
    }
    virtual void onDraw(SkCanvas* canvas) override {
        canvas->drawCircle(mX->value, mY->value, mRadius->value, mPaint->value);
    }

private:
    sp<uirenderer::CanvasPropertyPrimitive> mX;
    sp<uirenderer::CanvasPropertyPrimitive> mY;
    sp<uirenderer::CanvasPropertyPrimitive> mRadius;
    sp<uirenderer::CanvasPropertyPaint> mPaint;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
