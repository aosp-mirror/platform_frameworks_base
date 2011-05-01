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

#include <EGL/egl.h>
#include <gtest/gtest.h>
#include <gui/SurfaceTextureClient.h>

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

TEST_F(SurfaceTextureClientTest, GetISurfaceTextureIsNotNull) {
    sp<ISurfaceTexture> ist(mSTC->getISurfaceTexture());
    ASSERT_TRUE(ist != NULL);
}

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

TEST_F(SurfaceTextureClientTest, ANativeWindowLockFails) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindow_Buffer buf;
    ASSERT_EQ(BAD_VALUE, ANativeWindow_lock(anw.get(), &buf, NULL));
}

TEST_F(SurfaceTextureClientTest, EglCreateWindowSurfaceSucceeds) {
    sp<ANativeWindow> anw(mSTC);

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_DISPLAY, dpy);

    EGLint majorVersion;
    EGLint minorVersion;
    EXPECT_TRUE(eglInitialize(dpy, &majorVersion, &minorVersion));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    EGLConfig myConfig = {0};
    EGLint numConfigs = 0;
    EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE };
    EXPECT_TRUE(eglChooseConfig(dpy, configAttribs, &myConfig, 1,
            &numConfigs));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    EGLSurface eglSurface = eglCreateWindowSurface(dpy, myConfig, anw.get(),
            NULL);
    ASSERT_NE(EGL_NO_SURFACE, eglSurface);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    eglTerminate(dpy);
}

TEST_F(SurfaceTextureClientTest, BufferGeometryInvalidSizesFail) {
    sp<ANativeWindow> anw(mSTC);

    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(), -1,  0,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(),  0, -1,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(),  0,  0, -1));
    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(), -1, -1,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(),  0,  8,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(anw.get(),  8,  0,  0));
}

TEST_F(SurfaceTextureClientTest, DefaultGeometryValues) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometryCanBeSet) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 16, 8, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometryDefaultSizeSetFormat) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 0, 0, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySetSizeDefaultFormat) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 16, 8, 0));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySizeCanBeUnset) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 16, 8, 0));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 0, 0, 0));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySizeCanBeChangedWithoutFormat) {
    sp<ANativeWindow> anw(mSTC);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 0, 0, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 16, 8, 0));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSize) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, st->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSizeAfterDequeue) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    ANativeWindowBuffer* buf[2];
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[1]));
    EXPECT_EQ(OK, st->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(16, buf[0]->width);
    EXPECT_EQ(16, buf[1]->width);
    EXPECT_EQ(8, buf[0]->height);
    EXPECT_EQ(8, buf[1]->height);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[1]));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSizeVsGeometry) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    ANativeWindowBuffer* buf[2];
    EXPECT_EQ(OK, st->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(16, buf[0]->width);
    EXPECT_EQ(16, buf[1]->width);
    EXPECT_EQ(8, buf[0]->height);
    EXPECT_EQ(8, buf[1]->height);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[1]));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 12, 24, 0));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(12, buf[0]->width);
    EXPECT_EQ(12, buf[1]->width);
    EXPECT_EQ(24, buf[0]->height);
    EXPECT_EQ(24, buf[1]->height);
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[1]));
}

}
