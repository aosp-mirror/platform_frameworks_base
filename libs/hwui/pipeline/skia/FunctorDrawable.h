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

#include <WebViewFunctorManager.h>
#include <utils/Functor.h>
#include <variant>

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
            : mBounds(canvas->getLocalClipBounds())
            , mAnyFunctor(std::in_place_type<LegacyFunctor>, functor, listener) {}

    FunctorDrawable(int functor, SkCanvas* canvas)
            : mBounds(canvas->getLocalClipBounds())
            , mAnyFunctor(std::in_place_type<NewFunctor>, functor) {}

    virtual ~FunctorDrawable() {}

    virtual void syncFunctor(const WebViewSyncData& data) const {
        if (mAnyFunctor.index() == 0) {
            std::get<0>(mAnyFunctor).handle->sync(data);
        } else {
            (*(std::get<1>(mAnyFunctor).functor))(DrawGlInfo::kModeSync, nullptr);
        }
    }

protected:
    virtual SkRect onGetBounds() override { return mBounds; }

    const SkRect mBounds;

    struct LegacyFunctor {
        explicit LegacyFunctor(Functor* functor, GlFunctorLifecycleListener* listener)
                : functor(functor), listener(listener) {}
        Functor* functor;
        sp<GlFunctorLifecycleListener> listener;
    };

    struct NewFunctor {
        explicit NewFunctor(int functor) {
            handle = WebViewFunctorManager::instance().handleFor(functor);
        }
        sp<WebViewFunctor::Handle> handle;
    };

    std::variant<NewFunctor, LegacyFunctor> mAnyFunctor;
};

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
