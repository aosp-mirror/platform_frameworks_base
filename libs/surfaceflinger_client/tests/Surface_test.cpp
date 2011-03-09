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
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <utils/String8.h>

namespace android {

class SurfaceTest : public ::testing::Test {
protected:
    virtual sp<SurfaceComposerClient> getSurfaceComposerClient() {
        return sp<SurfaceComposerClient>(new SurfaceComposerClient);
    }

    virtual void SetUp() {
        mComposerClient = getSurfaceComposerClient();
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

}
