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

#ifndef HWUI_GLES_WRAP_ENABLED
#error Wrapping wasn't enabled, can't use this!
#endif

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl32.h>

#include <gl/GrGLInterface.h>
#include <memory>

namespace android {
namespace uirenderer {
namespace debug {

// All the gl methods on GlesDriver have a trailing underscore
// This is to avoid collision with gles_redefine/gles_undefine
class GlesDriver {
public:
    virtual ~GlesDriver() {}
    virtual sk_sp<const GrGLInterface> getSkiaInterface();

#define GL_ENTRY(ret, api, ...) virtual ret api##_(__VA_ARGS__) = 0;
#include "gles_decls.in"
#undef GL_ENTRY

    static GlesDriver* get();
    static std::unique_ptr<GlesDriver> replace(std::unique_ptr<GlesDriver>&& driver);
};

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
