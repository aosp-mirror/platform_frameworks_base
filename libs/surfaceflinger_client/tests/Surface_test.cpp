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

namespace android {

class SurfaceTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mComposerClient = new SurfaceComposerClient;
        ASSERT_EQ(NO_ERROR, mComposerClient->initCheck());

        mSurfaceControl = mComposerClient->createSurface(getpid(),
                String8("Test Surface"), 0, 32, 32, PIXEL_FORMAT_RGB_888, 0);

        ASSERT_TRUE(mSurfaceControl != NULL);
        ASSERT_TRUE(mSurfaceControl->isValid());

        ASSERT_EQ(NO_ERROR, mComposerClient->openTransaction());
        ASSERT_EQ(NO_ERROR, mSurfaceControl->setLayer(30000));
        ASSERT_EQ(NO_ERROR, mSurfaceControl->show());
        ASSERT_EQ(NO_ERROR, mComposerClient->closeTransaction());

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
TEST_F(SurfaceTest, ScreenshotsOfProtectedBuffersFail) {
    sp<ANativeWindow> anw(mSurface);

    // Verify the screenshot works with no protected buffers.
    sp<IMemoryHeap> heap;
    uint32_t w=0, h=0;
    PixelFormat fmt=0;
    sp<ISurfaceComposer> sf(ComposerService::getComposerService());
    ASSERT_EQ(NO_ERROR, sf->captureScreen(0, &heap, &w, &h, &fmt, 64, 64, 0,
            40000));
    ASSERT_TRUE(heap != NULL);

    // Set the PROTECTED usage bit and verify that the screenshot fails.  Note
    // that we need to dequeue a buffer in order for it to actually get
    // allocated in SurfaceFlinger.
    ASSERT_EQ(NO_ERROR, native_window_set_usage(anw.get(),
            GRALLOC_USAGE_PROTECTED));
    ASSERT_EQ(NO_ERROR, native_window_set_buffer_count(anw.get(), 3));
    android_native_buffer_t* buf = 0;
    for (int i = 0; i < 4; i++) {
        // Loop to make sure SurfaceFlinger has retired a protected buffer.
        ASSERT_EQ(NO_ERROR, anw->dequeueBuffer(anw.get(), &buf));
        ASSERT_EQ(NO_ERROR, anw->lockBuffer(anw.get(), buf));
        ASSERT_EQ(NO_ERROR, anw->queueBuffer(anw.get(), buf));
    }
    heap = 0;
    w = h = fmt = 0;
    ASSERT_EQ(INVALID_OPERATION, sf->captureScreen(0, &heap, &w, &h, &fmt,
            64, 64, 0, 40000));
    ASSERT_TRUE(heap == NULL);

    // XXX: This should not be needed, but it seems that the new buffers don't
    // correctly show up after the upcoming dequeue/lock/queue loop without it.
    // We should look into this at some point.
    ASSERT_EQ(NO_ERROR, native_window_set_buffer_count(anw.get(), 3));

    // Un-set the PROTECTED usage bit and verify that the screenshot works
    // again.  Note that we have to change the buffers geometry to ensure that
    // the buffers get reallocated, as the new usage bits are a subset of the
    // old.
    ASSERT_EQ(NO_ERROR, native_window_set_usage(anw.get(), 0));
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(anw.get(), 32, 32, 0));
    for (int i = 0; i < 4; i++) {
        // Loop to make sure SurfaceFlinger has retired a protected buffer.
        ASSERT_EQ(NO_ERROR, anw->dequeueBuffer(anw.get(), &buf));
        ASSERT_EQ(NO_ERROR, anw->lockBuffer(anw.get(), buf));
        ASSERT_EQ(NO_ERROR, anw->queueBuffer(anw.get(), buf));
    }
    heap = 0;
    w = h = fmt = 0;
    ASSERT_EQ(NO_ERROR, sf->captureScreen(0, &heap, &w, &h, &fmt, 64, 64, 0,
            40000));
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
