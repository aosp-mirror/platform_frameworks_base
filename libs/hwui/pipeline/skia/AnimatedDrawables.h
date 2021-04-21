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

class AnimatedRipple : public SkDrawable {
public:
    AnimatedRipple(uirenderer::CanvasPropertyPrimitive* x, uirenderer::CanvasPropertyPrimitive* y,
                   uirenderer::CanvasPropertyPrimitive* radius,
                   uirenderer::CanvasPropertyPaint* paint,
                   uirenderer::CanvasPropertyPrimitive* progress,
                   uirenderer::CanvasPropertyPrimitive* turbulencePhase,
                   const SkRuntimeShaderBuilder& effectBuilder)
            : mX(x)
            , mY(y)
            , mRadius(radius)
            , mPaint(paint)
            , mProgress(progress)
            , mTurbulencePhase(turbulencePhase)
            , mRuntimeEffectBuilder(effectBuilder) {}

protected:
    virtual SkRect onGetBounds() override {
        const float x = mX->value;
        const float y = mY->value;
        const float radius = mRadius->value;
        return SkRect::MakeLTRB(x - radius, y - radius, x + radius, y + radius);
    }
    virtual void onDraw(SkCanvas* canvas) override {
        setUniform2f("in_origin", mX->value, mY->value);
        setUniform("in_radius", mRadius);
        setUniform("in_progress", mProgress);
        setUniform("in_turbulencePhase", mTurbulencePhase);

        //
        // Keep in sync with:
        // frameworks/base/graphics/java/android/graphics/drawable/RippleShader.java
        //
        const float turbulencePhase = mTurbulencePhase->value;
        setUniform2f("in_tCircle1", SCALE * 0.5 + (turbulencePhase * CIRCLE_X_1),
                     SCALE * 0.5 + (turbulencePhase * CIRCLE_Y_1));
        setUniform2f("in_tCircle2", SCALE * 0.2 + (turbulencePhase * CIRCLE_X_2),
                     SCALE * 0.2 + (turbulencePhase * CIRCLE_Y_2));
        setUniform2f("in_tCircle3", SCALE + (turbulencePhase * CIRCLE_X_3),
                     SCALE + (turbulencePhase * CIRCLE_Y_3));
        const float rotation1 = turbulencePhase * PI_ROTATE_RIGHT + 1.7 * PI;
        setUniform2f("in_tRotation1", cos(rotation1), sin(rotation1));
        const float rotation2 = turbulencePhase * PI_ROTATE_LEFT + 2 * PI;
        setUniform2f("in_tRotation2", cos(rotation2), sin(rotation2));
        const float rotation3 = turbulencePhase * PI_ROTATE_RIGHT + 2.75 * PI;
        setUniform2f("in_tRotation3", cos(rotation3), sin(rotation3));

        SkPaint paint = mPaint->value;
        paint.setShader(mRuntimeEffectBuilder.makeShader(nullptr, false));
        canvas->drawCircle(mX->value, mY->value, mRadius->value, paint);
    }

private:
    sp<uirenderer::CanvasPropertyPrimitive> mX;
    sp<uirenderer::CanvasPropertyPrimitive> mY;
    sp<uirenderer::CanvasPropertyPrimitive> mRadius;
    sp<uirenderer::CanvasPropertyPaint> mPaint;
    sp<uirenderer::CanvasPropertyPrimitive> mProgress;
    sp<uirenderer::CanvasPropertyPrimitive> mTurbulencePhase;
    SkRuntimeShaderBuilder mRuntimeEffectBuilder;

    const float PI = 3.1415926535897932384626;
    const float PI_ROTATE_RIGHT = PI * 0.0078125;
    const float PI_ROTATE_LEFT = PI * -0.0078125;
    const float SCALE = 1.5;
    const float CIRCLE_X_1 = 0.01 * cos(SCALE * 0.55);
    const float CIRCLE_Y_1 = 0.01 * sin(SCALE * 0.55);
    const float CIRCLE_X_2 = -0.0066 * cos(SCALE * 0.45);
    const float CIRCLE_Y_2 = -0.0066 * sin(SCALE * 0.45);
    const float CIRCLE_X_3 = -0.0066 * cos(SCALE * 0.35);
    const float CIRCLE_Y_3 = -0.0066 * sin(SCALE * 0.35);

    virtual void setUniform(std::string name, sp<uirenderer::CanvasPropertyPrimitive> property) {
        SkRuntimeShaderBuilder::BuilderUniform uniform =
                mRuntimeEffectBuilder.uniform(name.c_str());
        if (uniform.fVar != nullptr) {
            uniform = property->value;
        }
    }

    virtual void setUniform2f(std::string name, float a, float b) {
        SkRuntimeShaderBuilder::BuilderUniform uniform =
                mRuntimeEffectBuilder.uniform(name.c_str());
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
