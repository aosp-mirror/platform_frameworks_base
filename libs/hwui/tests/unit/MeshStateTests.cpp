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

#include <debug/MockGlesDriver.h>
#include <debug/ScopedReplaceDriver.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <renderstate/MeshState.h>
#include <tests/common/TestUtils.h>

using namespace android::uirenderer;
using namespace testing;

RENDERTHREAD_OPENGL_PIPELINE_TEST(MeshState, genOrUpdate) {
    debug::ScopedReplaceDriver<debug::MockGlesDriver> driverRef;
    auto& mockGlDriver = driverRef.get();
    EXPECT_CALL(mockGlDriver, glGenBuffers_(_, _)).WillOnce(SetArgPointee<1>(35));
    EXPECT_CALL(mockGlDriver, glBindBuffer_(_, 35));
    EXPECT_CALL(mockGlDriver, glBufferData_(_, _, _, _));

    GLuint buffer = 0;
    renderThread.renderState().meshState().genOrUpdateMeshBuffer(&buffer, 10, nullptr,
                                                                 GL_DYNAMIC_DRAW);
}
