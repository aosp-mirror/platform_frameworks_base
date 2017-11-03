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

#include "DefaultGlesDriver.h"

#include "gles_undefine.h"

#include <EGL/egl.h>

namespace android {
namespace uirenderer {
namespace debug {

// Generate the proxy
#define API_ENTRY(x) DefaultGlesDriver::x##_
#define CALL_GL_API(x, ...) x(__VA_ARGS__);
#define CALL_GL_API_RETURN(x, ...) return x(__VA_ARGS__);

#include "gles_stubs.in"

#undef API_ENTRY
#undef CALL_GL_API
#undef CALL_GL_API_RETURN

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
