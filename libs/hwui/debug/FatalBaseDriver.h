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

// A base driver that implements all the pure virtuals in the form of
// LOG_ALWAYS_FATALS. Suitable for selective-override implementations
// where only a known subset of methods need to be overridden
class FatalBaseDriver : public GlesDriver {
public:
#define GL_ENTRY(ret, api, ...) virtual ret api##_(__VA_ARGS__) override;
#include "gles_decls.in"
#undef GL_ENTRY
};

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
