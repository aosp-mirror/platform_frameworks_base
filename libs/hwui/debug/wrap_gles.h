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

// #include'ing this file is bad, bad things should be compile errors
#ifdef HWUI_GLES_WRAP_ENABLED
#error wrap_gles.h should only be used as an auto-included header, don't directly #include it
#endif
#define HWUI_GLES_WRAP_ENABLED

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl32.h>

// constant used by the NULL GPU implementation as well as HWUI's unit tests
constexpr int NULL_GPU_MAX_TEXTURE_SIZE = 2048;

// Generate stubs that route all the calls to our function table
#include "gles_redefine.h"

#define GL_ENTRY(ret, api, ...) ret api(__VA_ARGS__);

#include "gles_decls.in"
#undef GL_ENTRY
