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

#include "GlFunctorLifecycleListener.h"

#include <SkCanvas.h>
#include <SkDrawable.h>

#include <utils/Functor.h>
#include <utils/RefBase.h>

namespace android {
namespace uirenderer {

namespace skiapipeline {

/**
 * This drawable wraps a OpenGL functor enabling it to be recorded into a list
 * of Skia drawing commands.
 */
class GLFunctorDrawable : public SkDrawable {
public:
    GLFunctorDrawable(Functor* functor, GlFunctorLifecycleListener* listener, SkCanvas* canvas)
            : mFunctor(functor), mListener(listener), mBounds(canvas->getLocalClipBounds()) {}
    virtual ~GLFunctorDrawable();

    void syncFunctor() const;

protected:
    virtual SkRect onGetBounds() override { return mBounds; }
    virtual void onDraw(SkCanvas* canvas) override;

private:
    Functor* mFunctor;
    sp<GlFunctorLifecycleListener> mListener;
    const SkRect mBounds;
};

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
