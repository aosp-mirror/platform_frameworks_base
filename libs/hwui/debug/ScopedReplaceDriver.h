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

#include "GlesDriver.h"

namespace android {
namespace uirenderer {
namespace debug {

template <typename Driver>
class ScopedReplaceDriver {
public:
    ScopedReplaceDriver() {
        std::unique_ptr<Driver> glDriver = std::make_unique<Driver>();
        mCurrentDriver = glDriver.get();
        mOldDriver = GlesDriver::replace(std::move(glDriver));
    }

    Driver& get() { return *mCurrentDriver; }

    ~ScopedReplaceDriver() { GlesDriver::replace(std::move(mOldDriver)); }

private:
    std::unique_ptr<GlesDriver> mOldDriver;
    Driver* mCurrentDriver;
};

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
