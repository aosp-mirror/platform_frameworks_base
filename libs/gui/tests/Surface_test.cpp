/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <binder/IMemory.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>
#include <utils/String8.h>

#include <private/gui/ComposerService.h>

namespace android {

class SurfaceTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mComposerClient = new SurfaceComposerClient;
        ASSERT_EQ(NO_ERROR, mComposerClient->initCheck());

        mSurfaceControl = mComposerClient->createSurface(
                String8("Test Surface"), 0, 32, 32, PIXEL_FORMAT_RGBA_8888, 0);

        ASSERT_TRUE(mSurfaceControl != NULL);
        ASSERT_TRUE(mSurfaceControl->isValid());

        SurfaceComposerClient::openGlobalTransaction();
        ASSERT_EQ(NO_ERROR, mSurfaceControl->setLayer(0x7fffffff));
        ASSERT_EQ(NO_ERROR, mSurfaceControl->show());
        SurfaceComposerClient::closeGlobalTransaction();

        mSurface = mSurfaceControl->getSurface();
        ASSERT_TRUE(mSurface != NULL);
    }

    virtual void TearDown() {
        mComposerClient->dispose();
    }

    sp<Surface> mSurface;
    sp<SurfaceComposerClient> mComposerClient;
    sp<SurfaceControl> mSurfaceControl;
};

TEST_F(SurfaceTest, QueuesToWindowComposerIsTrueWhenVisible) {
    sp<ANativeWindow> anw(mSurface);
    int result = -123;
    int err = anw->query(anw.get(), NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER,
            &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(1, result);
}

TEST_F(SurfaceTest, QueuesToWindowComposerIsTrueWhenPurgatorized) {
    mSurfaceControl.clear();

    sp<ANativeWindow> anw(mSurface);
    int result = -123;
    int err = anw->query(anw.get(), NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER,
            &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(1, result);
}

// This test probably doesn't belong here.
TEST_F(SurfaceTest, ScreenshotsOfProtectedBuffersSucceed) {
    sp<ANativeWindow> anw(mSurface);

    // Verify the screenshot works with no protected buffers.
    sp<IMemoryHeap> heap;
    uint32_t w=0, h=0;
    PixelFormat fmt=0;
    sp<ISurfaceComposer> sf(ComposerService::getComposerService());
    ASSERT_EQ(NO_ERROR, sf->captureScreen(0, &heap, &w, &h, &fmt, 64, 64, 0,
            0x7fffffff));
    ASSERT_TRUE(heap != NULL);

    // Set the PROTECTED usage bit and verify that the screenshot fails.  Note
    // that we need to dequeue a buffer in order for it to actually get
    // allocated in SurfaceFlinger.
    ASSERT_EQ(NO_ERROR, native_window_set_usage(anw.get(),
            GRALLOC_USAGE_PROTECTED));
    ASSERT_EQ(NO_ERROR, native_window_set_buffer_count(anw.get(), 3));
    ANativeWindowBuffer* buf = 0;

    status_t err = anw->dequeueBuffer(anw.get(), &buf);
    if (err) {
        // we could fail if GRALLOC_USAGE_PROTECTED is not supported.
        // that's okay as long as this is the reason for the failure.
        // try again without the GRALLOC_USAGE_PROTECTED bit.
        ASSERT_EQ(NO_ERROR, native_window_set_usage(anw.get(), 0));
        ASSERT_EQ(NO_ERROR, anw->dequeueBuffer(anw.get(), &buf));
        return;
    }
    ASSERT_EQ(NO_ERROR, anw->cancelBuffer(anw.get(), buf));

    for (int i = 0; i < 4; i++) {
        // Loop to make sure SurfaceFlinger has retired a protected buffer.
        ASSERT_EQ(NO_ERROR, anw->dequeueBuffer(anw.get(), &buf));
        ASSERT_EQ(NO_ERROR, anw->lockBuffer(anw.get(), buf));
        ASSERT_EQ(NO_ERROR, anw->queueBuffer(anw.get(), buf));
    }
    heap = 0;
    w = h = fmt = 0;
    ASSERT_EQ(NO_ERROR, sf->captureScreen(0, &heap, &w, &h, &fmt,
            64, 64, 0, 0x7fffffff));
    ASSERT_TRUE(heap != NULL);
}

TEST_F(SurfaceTest, ConcreteTypeIsSurface) {
    sp<ANativeWindow> anw(mSurface);
    int result = -123;
    int err = anw->query(anw.get(), NATIVE_WINDOW_CONCRETE_TYPE, &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(NATIVE_WINDOW_SURFACE, result);
}

}
