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

#include "FatalBaseDriver.h"

#include <gmock/gmock.h>

namespace android {
namespace uirenderer {
namespace debug {

class MockGlesDriver : public FatalBaseDriver {
public:
    MOCK_METHOD2(glBindBuffer_, void(GLenum target, GLuint buffer));
    MOCK_METHOD4(glBufferData_,
                 void(GLenum target, GLsizeiptr size, const void* data, GLenum usage));
    MOCK_METHOD2(glGenBuffers_, void(GLsizei n, GLuint* buffers));
};

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
