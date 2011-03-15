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

#include <gui/SurfaceTextureClient.h>
#include <gtest/gtest.h>

namespace android {

class SurfaceTextureClientTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mST = new SurfaceTexture(123);
        mSTC = new SurfaceTextureClient(mST);
    }

    virtual void TearDown() {
        mST.clear();
        mSTC.clear();
    }

    sp<SurfaceTexture> mST;
    sp<SurfaceTextureClient> mSTC;
};

TEST_F(SurfaceTextureClientTest, QueuesToWindowCompositorIsFalse) {
    sp<ANativeWindow> anw(mSTC);
    int result = -123;
    int err = anw->query(anw.get(), NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER,
            &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(0, result);
}

TEST_F(SurfaceTextureClientTest, ConcreteTypeIsSurfaceTextureClient) {
    sp<ANativeWindow> anw(mSTC);
    int result = -123;
    int err = anw->query(anw.get(), NATIVE_WINDOW_CONCRETE_TYPE, &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(NATIVE_WINDOW_SURFACE_TEXTURE_CLIENT, result);
}

}
