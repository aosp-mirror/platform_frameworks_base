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

#define LOG_TAG "EGL_test"
//#define LOG_NDEBUG 0

#include <gtest/gtest.h>

#include <utils/Log.h>

#include "egl_cache.h"
#include "egl_display.h"

namespace android {

class EGLCacheTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mCache = egl_cache_t::get();
    }

    virtual void TearDown() {
        mCache->setCacheFilename("");
        mCache->terminate();
    }

    egl_cache_t* mCache;
};

TEST_F(EGLCacheTest, UninitializedCacheAlwaysMisses) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mCache->setBlob("abcd", 4, "efgh", 4);
    ASSERT_EQ(0, mCache->getBlob("abcd", 4, buf, 4));
    ASSERT_EQ(0xee, buf[0]);
    ASSERT_EQ(0xee, buf[1]);
    ASSERT_EQ(0xee, buf[2]);
    ASSERT_EQ(0xee, buf[3]);
}

TEST_F(EGLCacheTest, InitializedCacheAlwaysHits) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mCache->initialize(egl_display_t::get(EGL_DEFAULT_DISPLAY));
    mCache->setBlob("abcd", 4, "efgh", 4);
    ASSERT_EQ(4, mCache->getBlob("abcd", 4, buf, 4));
    ASSERT_EQ('e', buf[0]);
    ASSERT_EQ('f', buf[1]);
    ASSERT_EQ('g', buf[2]);
    ASSERT_EQ('h', buf[3]);
}

TEST_F(EGLCacheTest, TerminatedCacheAlwaysMisses) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mCache->initialize(egl_display_t::get(EGL_DEFAULT_DISPLAY));
    mCache->setBlob("abcd", 4, "efgh", 4);
    mCache->terminate();
    ASSERT_EQ(0, mCache->getBlob("abcd", 4, buf, 4));
    ASSERT_EQ(0xee, buf[0]);
    ASSERT_EQ(0xee, buf[1]);
    ASSERT_EQ(0xee, buf[2]);
    ASSERT_EQ(0xee, buf[3]);
}

class EGLCacheSerializationTest : public EGLCacheTest {

protected:

    virtual void SetUp() {
        EGLCacheTest::SetUp();

        char* tn = tempnam("/sdcard", "EGL_test-cache-");
        mFilename = tn;
        free(tn);
    }

    virtual void TearDown() {
        unlink(mFilename.string());
        EGLCacheTest::TearDown();
    }

    String8 mFilename;
};

TEST_F(EGLCacheSerializationTest, ReinitializedCacheContainsValues) {
    char buf[4] = { 0xee, 0xee, 0xee, 0xee };
    mCache->setCacheFilename(mFilename);
    mCache->initialize(egl_display_t::get(EGL_DEFAULT_DISPLAY));
    mCache->setBlob("abcd", 4, "efgh", 4);
    mCache->terminate();
    mCache->initialize(egl_display_t::get(EGL_DEFAULT_DISPLAY));
    ASSERT_EQ(4, mCache->getBlob("abcd", 4, buf, 4));
    ASSERT_EQ('e', buf[0]);
    ASSERT_EQ('f', buf[1]);
    ASSERT_EQ('g', buf[2]);
    ASSERT_EQ('h', buf[3]);
}

}
