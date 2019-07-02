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
