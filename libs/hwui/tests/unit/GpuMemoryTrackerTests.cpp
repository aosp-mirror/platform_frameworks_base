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

#include <GpuMemoryTracker.h>
#include <gtest/gtest.h>

#include "renderthread/EglManager.h"
#include "renderthread/RenderThread.h"
#include "tests/common/TestUtils.h"

#include <utils/StrongPointer.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

class TestGPUObject : public GpuMemoryTracker {
public:
    TestGPUObject() : GpuMemoryTracker(GpuObjectType::Texture) {}

    void changeSize(int newSize) { notifySizeChanged(newSize); }
};

// Other tests may have created a renderthread and EGL context.
// This will destroy the EGLContext on RenderThread if it exists so that the
// current thread can spoof being a GPU thread
static void destroyEglContext() {
    if (TestUtils::isRenderThreadRunning()) {
        TestUtils::runOnRenderThread([](RenderThread& thread) { thread.destroyGlContext(); });
    }
}

TEST(GpuMemoryTracker, sizeCheck) {
    destroyEglContext();

    GpuMemoryTracker::onGpuContextCreated();
    ASSERT_EQ(0, GpuMemoryTracker::getTotalSize(GpuObjectType::Texture));
    ASSERT_EQ(0, GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture));
    {
        TestGPUObject myObj;
        ASSERT_EQ(1, GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture));
        myObj.changeSize(500);
        ASSERT_EQ(500, GpuMemoryTracker::getTotalSize(GpuObjectType::Texture));
        myObj.changeSize(1000);
        ASSERT_EQ(1000, GpuMemoryTracker::getTotalSize(GpuObjectType::Texture));
        myObj.changeSize(300);
        ASSERT_EQ(300, GpuMemoryTracker::getTotalSize(GpuObjectType::Texture));
    }
    ASSERT_EQ(0, GpuMemoryTracker::getTotalSize(GpuObjectType::Texture));
    ASSERT_EQ(0, GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture));
    GpuMemoryTracker::onGpuContextDestroyed();
}
