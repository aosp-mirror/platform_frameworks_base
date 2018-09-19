/*
 * Copyright (C) 2018 The Android Open Source Project
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

namespace android {
namespace uirenderer {

namespace skiapipeline {

/**
 * This drawable wraps a functor enabling it to be recorded into a list
 * of Skia drawing commands.
 */
class FunctorDrawable : public SkDrawable {
public:
    FunctorDrawable(Functor* functor, GlFunctorLifecycleListener* listener, SkCanvas* canvas)
            : mFunctor(functor), mListener(listener), mBounds(canvas->getLocalClipBounds()) {}
    virtual ~FunctorDrawable() {}

    virtual void syncFunctor() const = 0;

protected:
    virtual SkRect onGetBounds() override { return mBounds; }

    Functor* mFunctor;
    sp<GlFunctorLifecycleListener> mListener;
    const SkRect mBounds;
};

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
