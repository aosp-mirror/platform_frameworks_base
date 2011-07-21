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
        mANW = mSTC;

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
        mANW.clear();

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
    sp<ANativeWindow> mANW;

    EGLDisplay mEglDisplay;
    EGLSurface mEglSurface;
    EGLContext mEglContext;
};

TEST_F(SurfaceTextureClientTest, GetISurfaceTextureIsNotNull) {
    sp<ISurfaceTexture> ist(mSTC->getISurfaceTexture());
    ASSERT_TRUE(ist != NULL);
}

TEST_F(SurfaceTextureClientTest, QueuesToWindowCompositorIsFalse) {
    int result = -123;
    int err = mANW->query(mANW.get(), NATIVE_WINDOW_QUEUES_TO_WINDOW_COMPOSER,
            &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(0, result);
}

TEST_F(SurfaceTextureClientTest, ConcreteTypeIsSurfaceTextureClient) {
    int result = -123;
    int err = mANW->query(mANW.get(), NATIVE_WINDOW_CONCRETE_TYPE, &result);
    EXPECT_EQ(NO_ERROR, err);
    EXPECT_EQ(NATIVE_WINDOW_SURFACE_TEXTURE_CLIENT, result);
}

TEST_F(SurfaceTextureClientTest, EglCreateWindowSurfaceSucceeds) {
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

    EGLSurface eglSurface = eglCreateWindowSurface(dpy, myConfig, mANW.get(),
            NULL);
    EXPECT_NE(EGL_NO_SURFACE, eglSurface);
    EXPECT_EQ(EGL_SUCCESS, eglGetError());

    eglTerminate(dpy);
}

TEST_F(SurfaceTextureClientTest, BufferGeometryInvalidSizesFail) {
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(), -1,  0,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(),  0, -1,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(),  0,  0, -1));
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(), -1, -1,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(),  0,  8,  0));
    EXPECT_GT(OK, native_window_set_buffers_geometry(mANW.get(),  8,  0,  0));
}

TEST_F(SurfaceTextureClientTest, DefaultGeometryValues) {
    ANativeWindowBuffer* buf;
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometryCanBeSet) {
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 16, 8, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometryDefaultSizeSetFormat) {
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 0, 0, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySetSizeDefaultFormat) {
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 16, 8, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySizeCanBeUnset) {
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 16, 8, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 0, 0, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, BufferGeometrySizeCanBeChangedWithoutFormat) {
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 0, 0, PIXEL_FORMAT_RGB_565));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(1, buf->width);
    EXPECT_EQ(1, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 16, 8, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGB_565, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSize) {
    sp<SurfaceTexture> st(mST);
    ANativeWindowBuffer* buf;
    EXPECT_EQ(OK, st->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf));
    EXPECT_EQ(16, buf->width);
    EXPECT_EQ(8, buf->height);
    EXPECT_EQ(PIXEL_FORMAT_RGBA_8888, buf->format);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSizeAfterDequeue) {
    ANativeWindowBuffer* buf[2];
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[1]));
    EXPECT_EQ(OK, mST->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(16, buf[0]->width);
    EXPECT_EQ(16, buf[1]->width);
    EXPECT_EQ(8, buf[0]->height);
    EXPECT_EQ(8, buf[1]->height);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[1]));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSetDefaultSizeVsGeometry) {
    ANativeWindowBuffer* buf[2];
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    EXPECT_EQ(OK, mST->setDefaultBufferSize(16, 8));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(16, buf[0]->width);
    EXPECT_EQ(16, buf[1]->width);
    EXPECT_EQ(8, buf[0]->height);
    EXPECT_EQ(8, buf[1]->height);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[1]));
    EXPECT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 12, 24, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_EQ(12, buf[0]->width);
    EXPECT_EQ(12, buf[1]->width);
    EXPECT_EQ(24, buf[0]->height);
    EXPECT_EQ(24, buf[1]->height);
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[1]));
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureTooManyUpdateTexImage) {
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, mST->setSynchronousMode(false));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(OK, mST->updateTexImage());

    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 3));

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));

    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(OK, mST->updateTexImage());
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeSlowRetire) {
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[2]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[0]);
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[1]);
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeFastRetire) {
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[0]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[1]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[2]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeDQQR) {
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 3));

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[0]);

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_NE(buf[0], buf[1]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[1]);

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));
    EXPECT_NE(buf[1], buf[2]);
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[2]));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[2]);
}

// XXX: We currently have no hardware that properly handles dequeuing the
// buffer that is currently bound to the texture.
TEST_F(SurfaceTextureClientTest, DISABLED_SurfaceTextureSyncModeDequeueCurrent) {
    android_native_buffer_t* buf[3];
    android_native_buffer_t* firstBuf;
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 3));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &firstBuf));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), firstBuf));
    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), firstBuf);
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[2]));
    EXPECT_NE(buf[0], buf[1]);
    EXPECT_NE(buf[1], buf[2]);
    EXPECT_NE(buf[2], buf[0]);
    EXPECT_EQ(firstBuf, buf[2]);
}

TEST_F(SurfaceTextureClientTest, SurfaceTextureSyncModeMinUndequeued) {
    android_native_buffer_t* buf[3];
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 3));

    // We should be able to dequeue all the buffers before we've queued mANWy.
    EXPECT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    EXPECT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    EXPECT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));

    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[2]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));

    EXPECT_EQ(OK, mST->updateTexImage());
    EXPECT_EQ(mST->getCurrentBuffer().get(), buf[1]);

    EXPECT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));

    // Once we've queued a buffer, however we should not be able to dequeue more
    // than (buffer-count - MIN_UNDEQUEUED_BUFFERS), which is 2 in this case.
    EXPECT_EQ(-EBUSY, mANW->dequeueBuffer(mANW.get(), &buf[1]));

    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mANW->cancelBuffer(mANW.get(), buf[2]));
}

// XXX: This is not expected to pass until the synchronization hacks are removed
// from the SurfaceTexture class.
TEST_F(SurfaceTextureClientTest, DISABLED_SurfaceTextureSyncModeWaitRetire) {
    class MyThread : public Thread {
        sp<SurfaceTexture> mST;
        EGLContext ctx;
        EGLSurface sur;
        EGLDisplay dpy;
        bool mBufferRetired;
        Mutex mLock;
        virtual bool threadLoop() {
            eglMakeCurrent(dpy, sur, sur, ctx);
            usleep(20000);
            Mutex::Autolock _l(mLock);
            mST->updateTexImage();
            mBufferRetired = true;
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            return false;
        }
    public:
        MyThread(const sp<SurfaceTexture>& mST)
            : mST(mST), mBufferRetired(false) {
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
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 3));
    // dequeue/queue/update so we have a current buffer
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    mST->updateTexImage();

    MyThread* thread = new MyThread(mST);
    sp<Thread> threadBase(thread);

    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    thread->run();
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[1]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[1]));
    //ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[2]));
    //ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[2]));
    thread->bufferDequeued();
    thread->requestExitAndWait();
}

TEST_F(SurfaceTextureClientTest, GetTransformMatrixReturnsVerticalFlip) {
    android_native_buffer_t* buf[3];
    float mtx[16] = {};
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mST->updateTexImage());
    mST->getTransformMatrix(mtx);

    EXPECT_EQ(1.f, mtx[0]);
    EXPECT_EQ(0.f, mtx[1]);
    EXPECT_EQ(0.f, mtx[2]);
    EXPECT_EQ(0.f, mtx[3]);

    EXPECT_EQ(0.f, mtx[4]);
    EXPECT_EQ(-1.f, mtx[5]);
    EXPECT_EQ(0.f, mtx[6]);
    EXPECT_EQ(0.f, mtx[7]);

    EXPECT_EQ(0.f, mtx[8]);
    EXPECT_EQ(0.f, mtx[9]);
    EXPECT_EQ(1.f, mtx[10]);
    EXPECT_EQ(0.f, mtx[11]);

    EXPECT_EQ(0.f, mtx[12]);
    EXPECT_EQ(1.f, mtx[13]);
    EXPECT_EQ(0.f, mtx[14]);
    EXPECT_EQ(1.f, mtx[15]);
}

TEST_F(SurfaceTextureClientTest, GetTransformMatrixSucceedsAfterFreeingBuffers) {
    android_native_buffer_t* buf[3];
    float mtx[16] = {};
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mST->updateTexImage());
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 6)); // frees buffers
    mST->getTransformMatrix(mtx);

    EXPECT_EQ(1.f, mtx[0]);
    EXPECT_EQ(0.f, mtx[1]);
    EXPECT_EQ(0.f, mtx[2]);
    EXPECT_EQ(0.f, mtx[3]);

    EXPECT_EQ(0.f, mtx[4]);
    EXPECT_EQ(-1.f, mtx[5]);
    EXPECT_EQ(0.f, mtx[6]);
    EXPECT_EQ(0.f, mtx[7]);

    EXPECT_EQ(0.f, mtx[8]);
    EXPECT_EQ(0.f, mtx[9]);
    EXPECT_EQ(1.f, mtx[10]);
    EXPECT_EQ(0.f, mtx[11]);

    EXPECT_EQ(0.f, mtx[12]);
    EXPECT_EQ(1.f, mtx[13]);
    EXPECT_EQ(0.f, mtx[14]);
    EXPECT_EQ(1.f, mtx[15]);
}

TEST_F(SurfaceTextureClientTest, GetTransformMatrixSucceedsAfterFreeingBuffersWithCrop) {
    android_native_buffer_t* buf[3];
    float mtx[16] = {};
    android_native_rect_t crop;
    crop.left = 0;
    crop.top = 0;
    crop.right = 5;
    crop.bottom = 5;

    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 4));
    ASSERT_EQ(OK, native_window_set_buffers_geometry(mANW.get(), 8, 8, 0));
    ASSERT_EQ(OK, mANW->dequeueBuffer(mANW.get(), &buf[0]));
    ASSERT_EQ(OK, native_window_set_crop(mANW.get(), &crop));
    ASSERT_EQ(OK, mANW->queueBuffer(mANW.get(), buf[0]));
    ASSERT_EQ(OK, mST->updateTexImage());
    ASSERT_EQ(OK, native_window_set_buffer_count(mANW.get(), 6)); // frees buffers
    mST->getTransformMatrix(mtx);

    // This accounts for the 1 texel shrink for each edge that's included in the
    // transform matrix to avoid texturing outside the crop region.
    EXPECT_EQ(.5f, mtx[0]);
    EXPECT_EQ(0.f, mtx[1]);
    EXPECT_EQ(0.f, mtx[2]);
    EXPECT_EQ(0.f, mtx[3]);

    EXPECT_EQ(0.f, mtx[4]);
    EXPECT_EQ(-.5f, mtx[5]);
    EXPECT_EQ(0.f, mtx[6]);
    EXPECT_EQ(0.f, mtx[7]);

    EXPECT_EQ(0.f, mtx[8]);
    EXPECT_EQ(0.f, mtx[9]);
    EXPECT_EQ(1.f, mtx[10]);
    EXPECT_EQ(0.f, mtx[11]);

    EXPECT_EQ(0.f, mtx[12]);
    EXPECT_EQ(.5f, mtx[13]);
    EXPECT_EQ(0.f, mtx[14]);
    EXPECT_EQ(1.f, mtx[15]);
}

// This test verifies that the buffer format can be queried immediately after
// it is set.
TEST_F(SurfaceTextureClientTest, QueryFormatAfterSettingWorks) {
    sp<ANativeWindow> anw(mSTC);
    int fmts[] = {
        // RGBA_8888 should not come first, as it's the default
        HAL_PIXEL_FORMAT_RGBX_8888,
        HAL_PIXEL_FORMAT_RGBA_8888,
        HAL_PIXEL_FORMAT_RGB_888,
        HAL_PIXEL_FORMAT_RGB_565,
        HAL_PIXEL_FORMAT_BGRA_8888,
        HAL_PIXEL_FORMAT_RGBA_5551,
        HAL_PIXEL_FORMAT_RGBA_4444,
        HAL_PIXEL_FORMAT_YV12,
    };

    const int numFmts = (sizeof(fmts) / sizeof(fmts[0]));
    for (int i = 0; i < numFmts; i++) {
      int fmt = -1;
      ASSERT_EQ(OK, native_window_set_buffers_geometry(anw.get(), 0, 0, fmts[i]));
      ASSERT_EQ(OK, anw->query(anw.get(), NATIVE_WINDOW_FORMAT, &fmt));
      EXPECT_EQ(fmts[i], fmt);
    }
}

class MultiSurfaceTextureClientTest : public ::testing::Test {

public:
    MultiSurfaceTextureClientTest() :
            mEglDisplay(EGL_NO_DISPLAY),
            mEglContext(EGL_NO_CONTEXT) {
        for (int i = 0; i < NUM_SURFACE_TEXTURES; i++) {
            mEglSurfaces[i] = EGL_NO_CONTEXT;
        }
    }

protected:

    enum { NUM_SURFACE_TEXTURES = 32 };

    virtual void SetUp() {
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_DISPLAY, mEglDisplay);

        EGLint majorVersion, minorVersion;
        EXPECT_TRUE(eglInitialize(mEglDisplay, &majorVersion, &minorVersion));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        EGLConfig myConfig;
        EGLint numConfigs = 0;
        EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE
        };
        EXPECT_TRUE(eglChooseConfig(mEglDisplay, configAttribs, &myConfig, 1,
                &numConfigs));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        mEglContext = eglCreateContext(mEglDisplay, myConfig, EGL_NO_CONTEXT,
                0);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_CONTEXT, mEglContext);

        for (int i = 0; i < NUM_SURFACE_TEXTURES; i++) {
            sp<SurfaceTexture> st(new SurfaceTexture(i));
            sp<SurfaceTextureClient> stc(new SurfaceTextureClient(st));
            mEglSurfaces[i] = eglCreateWindowSurface(mEglDisplay, myConfig,
                    static_cast<ANativeWindow*>(stc.get()), NULL);
            ASSERT_EQ(EGL_SUCCESS, eglGetError());
            ASSERT_NE(EGL_NO_SURFACE, mEglSurfaces[i]);
        }
    }

    virtual void TearDown() {
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
                EGL_NO_CONTEXT);

        for (int i = 0; i < NUM_SURFACE_TEXTURES; i++) {
            if (mEglSurfaces[i] != EGL_NO_SURFACE) {
                eglDestroySurface(mEglDisplay, mEglSurfaces[i]);
            }
        }

        if (mEglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(mEglDisplay, mEglContext);
        }

        if (mEglDisplay != EGL_NO_DISPLAY) {
            eglTerminate(mEglDisplay);
        }
    }

    EGLDisplay mEglDisplay;
    EGLSurface mEglSurfaces[NUM_SURFACE_TEXTURES];
    EGLContext mEglContext;
};

// XXX: This test is disabled because it causes a hang on some devices.  See bug
// 5015672.
TEST_F(MultiSurfaceTextureClientTest, DISABLED_MakeCurrentBetweenSurfacesWorks) {
    for (int iter = 0; iter < 8; iter++) {
        for (int i = 0; i < NUM_SURFACE_TEXTURES; i++) {
            eglMakeCurrent(mEglDisplay, mEglSurfaces[i], mEglSurfaces[i],
                    mEglContext);
            glClear(GL_COLOR_BUFFER_BIT);
            eglSwapBuffers(mEglDisplay, mEglSurfaces[i]);
        }
    }
}

} // namespace android
