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

#include "GlesDriver.h"
#include "DefaultGlesDriver.h"
#include "GlesErrorCheckWrapper.h"

namespace android {
namespace uirenderer {
namespace debug {

static DefaultGlesDriver sDefaultDriver;

static std::unique_ptr<GlesDriver> sGlesDriver(new GlesErrorCheckWrapper(sDefaultDriver));

GlesDriver* GlesDriver::get() {
    return sGlesDriver.get();
}

std::unique_ptr<GlesDriver> GlesDriver::replace(std::unique_ptr<GlesDriver>&& driver) {
    std::unique_ptr<GlesDriver> ret = std::move(sGlesDriver);
    sGlesDriver = std::move(driver);
    return ret;
}

sk_sp<const GrGLInterface> GlesDriver::getSkiaInterface() {
    sk_sp<const GrGLInterface> skiaInterface(GrGLCreateNativeInterface());
    return skiaInterface;
}

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
