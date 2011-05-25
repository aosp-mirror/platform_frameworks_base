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
#include <utils/threads.h>

namespace android {

class SurfaceTextureClientTest : public ::testing::Test {
protected:
    SurfaceTextureClientTest():
            mEglDisplay(EGL_NO_DISPLAY),
            mEglSurface(EGL_NO_SURFACE),
            mEglContext(EGL_NO_CONTEXT) {
    }

    virtual void SetUp() {
        mST = new SurfaceTexture(123);
        mSTC = new SurfaceTextureClient(mST);

        // We need a valid GL context so we can test updateTexImage()
        // This initializes EGL and create a dummy GL context with a
        // pbuffer render target.
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_DISPLAY, mEglDisplay);

        EGLint majorVersion, minorVersion;
        EXPECT_TRUE(eglInitialize(mEglDisplay, &majorVersion, &minorVersion));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        EGLConfig myConfig;
        EGLint numConfigs = 0;
        EXPECT_TRUE(eglChooseConfig(mEglDisplay, getConfigAttribs(),
                &myConfig, 1, &numConfigs));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        EGLint pbufferAttribs[] = {
            EGL_WIDTH, 16,
            EGL_HEIGHT, 16,
            EGL_NONE };
        mEglSurface = eglCreatePbufferSurface(mEglDisplay, myConfig, pbufferAttribs);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_SURFACE, mEglSurface);

        mEglContext = eglCreateContext(mEglDisplay, myConfig, EGL_NO_CONTEXT, 0);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_CONTEXT, mEglContext);

        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
    }

    virtual void TearDown() {
        mST.clear();
        mSTC.clear();
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(mEglDisplay, mEglContext);
        eglDestroySurface(mEglDisplay, mEglSurface);
        eglTerminate(mEglDisplay);
    }

    virtual EGLint const* getConfigAttribs() {
        static EGLint sDefaultConfigAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_NONE
        };

        return sDefaultConfigAttribs;
    }

    sp<SurfaceTexture> mST;
    sp<SurfaceTextureClient> mSTC;
    EGLDisplay mEglDisplay;
    EGLSurface mEglSurface;
    EGLContext mEglContext;
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
    EXPECT_NE(EGL_NO_SURFACE, eglSurface);
    EXPECT_EQ(EGL_SUCCESS, eglGetError());

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
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 4));
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
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 4));
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

TEST_F(SurfaceTextureClientTest, SurfaceTextureTooManyUpdateTexImage) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(false));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 4));

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(OK, st->updateTexImage());

    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 3));

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));

    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(OK, st->updateTexImage());
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeSlowRetire) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 4));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[2]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[0]);
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[1]);
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeFastRetire) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 4));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[0]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[1]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[2]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeDQQR) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 3));

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[0]);

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[1]);

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));
    EXPECT_NE(buf[1], buf[2]);
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[2]));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[2]);
}

// XXX: We currently have no hardware that properly handles dequeuing the
// buffer that is currently bound to the texture.
TEST_F(SurfaceTextureClientTest, DISABLED_SurfaceTextureSyncModeDequeueCurrent) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    android_native_buffer_t* firstBuf;
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 3));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &firstBuf));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), firstBuf));
    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), firstBuf);
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    EXPECT_EQ(firstBuf, buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeMinUndequeued) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 3));

    // We should be able to dequeue all the buffers before we've queued any.
    EXPECT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    EXPECT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    EXPECT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));

    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[2]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));

    EXPECT_EQ(OK, st->updateTexImage());
    EXPECT_EQ(st->getCurrentBuffer().get(), buf[1]);

    EXPECT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));

    // Once we've queued a buffer, however we should not be able to dequeue more
    // than (buffer-count - MIN_UNDEQUEUED_BUFFERS), which is 2 in this case.
    EXPECT_EQ(-EBUSY, anw->dequeueBuffer(anw.get(), &buf[1]));

    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->cancelBuffer(anw.get(), buf[2]));
}

// XXX: This is not expected to pass until the synchronization hacks are removed
// from the SurfaceTexture class.
TEST_F(SurfaceTextureClientTest, DISABLED_SurfaceTextureSyncModeWaitRetire) {
    sp<ANativeWindow> anw(mSTC);
    sp<SurfaceTexture> st(mST);

    class MyThread : public Thread {
        sp<SurfaceTexture> st;
        EGLContext ctx;
        EGLSurface sur;
        EGLDisplay dpy;
        bool mBufferRetired;
        Mutex mLock;
        virtual bool threadLoop() {
            eglMakeCurrent(dpy, sur, sur, ctx);
            usleep(20000);
            Mutex::Autolock _l(mLock);
            st->updateTexImage();
            mBufferRetired = true;
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            return false;
        }
    public:
        MyThread(const sp<SurfaceTexture>& st)
            : st(st), mBufferRetired(false) {
            ctx = eglGetCurrentContext();
            sur = eglGetCurrentSurface(EGL_DRAW);
            dpy = eglGetCurrentDisplay();
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        ~MyThread() {
            eglMakeCurrent(dpy, sur, sur, ctx);
        }
        void bufferDequeued() {
            Mutex::Autolock _l(mLock);
            EXPECT_EQ(true, mBufferRetired);
        }
    };

    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, st->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(anw.get(), 3));
    // dequeue/queue/update so we have a current buffer
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    st->updateTexImage();

    MyThread* thread = new MyThread(st);
    sp<Thread> threadBase(thread);

    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[0]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[0]));
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[1]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[1]));
    thread->run();
    ASSERT_EQ(OK, anw->dequeueBuffer(anw.get(), &buf[2]));
    ASSERT_EQ(OK, anw->queueBuffer(anw.get(), buf[2]));
    thread->bufferDequeued();
    thread->requestExitAndWait();
}

}
