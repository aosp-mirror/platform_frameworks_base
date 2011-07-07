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

#include <utils/String8.h>

#include <EGL/egl.h>

namespace android {

class EGLTest : public ::testing::Test {
protected:
    EGLDisplay mEglDisplay;

protected:
    EGLTest() :
            mEglDisplay(EGL_NO_DISPLAY) {
    }

    virtual void SetUp() {
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        ASSERT_NE(EGL_NO_DISPLAY, mEglDisplay);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        EGLint majorVersion;
        EGLint minorVersion;
        EXPECT_TRUE(eglInitialize(mEglDisplay, &majorVersion, &minorVersion));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        RecordProperty("EglVersionMajor", majorVersion);
        RecordProperty("EglVersionMajor", minorVersion);
    }

    virtual void TearDown() {
        EGLBoolean success = eglTerminate(mEglDisplay);
        ASSERT_EQ(EGL_TRUE, success);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
    }
};

TEST_F(EGLTest, DISABLED_EGLConfigEightBitFirst) {

    EGLint numConfigs;
    EGLConfig config;
    EGLBoolean success;
    EGLint attrs[] = {
            EGL_SURFACE_TYPE,       EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE,    EGL_OPENGL_ES2_BIT,
            EGL_NONE
    };

    success = eglChooseConfig(mEglDisplay, attrs, &config, 1, &numConfigs);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_GE(numConfigs, 1);

    EGLint components[3];

    success = eglGetConfigAttrib(mEglDisplay, config, EGL_RED_SIZE, &components[0]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    success = eglGetConfigAttrib(mEglDisplay, config, EGL_GREEN_SIZE, &components[1]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    success = eglGetConfigAttrib(mEglDisplay, config, EGL_BLUE_SIZE, &components[2]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    EXPECT_GE(components[0], 8);
    EXPECT_GE(components[1], 8);
    EXPECT_GE(components[2], 8);
}

TEST_F(EGLTest, EGLConfigRGBA8888First) {

    EGLint numConfigs;
    EGLConfig config;
    EGLBoolean success;
    EGLint attrs[] = {
            EGL_SURFACE_TYPE,       EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE,    EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,           8,
            EGL_GREEN_SIZE,         8,
            EGL_BLUE_SIZE,          8,
            EGL_ALPHA_SIZE,         8,
            EGL_NONE
    };

    success = eglChooseConfig(mEglDisplay, attrs, &config, 1, &numConfigs);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_GE(numConfigs, 1);

    EGLint components[4];

    success = eglGetConfigAttrib(mEglDisplay, config, EGL_RED_SIZE, &components[0]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    success = eglGetConfigAttrib(mEglDisplay, config, EGL_GREEN_SIZE, &components[1]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    success = eglGetConfigAttrib(mEglDisplay, config, EGL_BLUE_SIZE, &components[2]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    success = eglGetConfigAttrib(mEglDisplay, config, EGL_ALPHA_SIZE, &components[3]);
    ASSERT_EQ(EGL_TRUE, success);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    EXPECT_GE(components[0], 8);
    EXPECT_GE(components[1], 8);
    EXPECT_GE(components[2], 8);
    EXPECT_GE(components[3], 8);
}


}
