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

#include "RenderNodeDrawable.h"
#include "SkiaUtils.h"

#include <SkCanvas.h>
#include <SkDrawable.h>
#include <ui/FatVector.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaDisplayList;
class EndReorderBarrierDrawable;

/**
 * StartReorderBarrierDrawable and EndReorderBarrierDrawable work together to define
 * a sub-list in a display list that need to be drawn out-of-order sorted instead by render
 * node Z index.
 * StartReorderBarrierDrawable will sort the entire range and it will draw
 * render nodes in the range with negative Z index.
 */
class StartReorderBarrierDrawable : public SkDrawable {
public:
    explicit StartReorderBarrierDrawable(SkiaDisplayList* data);

protected:
    virtual SkRect onGetBounds() override { return SkRectMakeLargest(); }
    virtual void onDraw(SkCanvas* canvas) override;

private:
    int mEndChildIndex;
    int mBeginChildIndex;
    FatVector<RenderNodeDrawable*, 16> mChildren;
    SkiaDisplayList* mDisplayList;

    friend class EndReorderBarrierDrawable;
};

/**
 * See StartReorderBarrierDrawable.
 * EndReorderBarrierDrawable relies on StartReorderBarrierDrawable to host and sort the render
 * nodes by Z index. When EndReorderBarrierDrawable is drawn it will draw all render nodes in the
 * range with positive Z index. It is also responsible for drawing shadows for the nodes
 * corresponding to their z-index.
 */
class EndReorderBarrierDrawable : public SkDrawable {
public:
    explicit EndReorderBarrierDrawable(StartReorderBarrierDrawable* startBarrier);

protected:
    virtual SkRect onGetBounds() override { return SkRectMakeLargest(); }
    virtual void onDraw(SkCanvas* canvas) override;

private:
    void drawShadow(SkCanvas* canvas, RenderNodeDrawable* caster);
    StartReorderBarrierDrawable* mStartBarrier;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
