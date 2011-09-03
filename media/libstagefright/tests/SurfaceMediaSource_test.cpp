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

// #define LOG_NDEBUG 0
#define LOG_TAG "SurfaceMediaSource_test"

#include <gtest/gtest.h>
#include <utils/String8.h>
#include <utils/Errors.h>
#include <fcntl.h>
#include <unistd.h>

#include <media/stagefright/SurfaceMediaSource.h>
#include <media/mediarecorder.h>

#include <gui/SurfaceTextureClient.h>
#include <ui/GraphicBuffer.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <binder/ProcessState.h>
#include <ui/FramebufferNativeWindow.h>

#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <OMX_Component.h>

#include "DummyRecorder.h"


namespace android {

class GLTest : public ::testing::Test {
protected:

    GLTest():
            mEglDisplay(EGL_NO_DISPLAY),
            mEglSurface(EGL_NO_SURFACE),
            mEglContext(EGL_NO_CONTEXT) {
    }

    virtual void SetUp() {
        LOGV("GLTest::SetUp()");
        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_DISPLAY, mEglDisplay);

        EGLint majorVersion;
        EGLint minorVersion;
        EXPECT_TRUE(eglInitialize(mEglDisplay, &majorVersion, &minorVersion));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        RecordProperty("EglVersionMajor", majorVersion);
        RecordProperty("EglVersionMajor", minorVersion);

        EGLint numConfigs = 0;
        EXPECT_TRUE(eglChooseConfig(mEglDisplay, getConfigAttribs(), &mGlConfig,
                1, &numConfigs));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        char* displaySecsEnv = getenv("GLTEST_DISPLAY_SECS");
        if (displaySecsEnv != NULL) {
            mDisplaySecs = atoi(displaySecsEnv);
            if (mDisplaySecs < 0) {
                mDisplaySecs = 0;
            }
        } else {
            mDisplaySecs = 0;
        }

        if (mDisplaySecs > 0) {
            mComposerClient = new SurfaceComposerClient;
            ASSERT_EQ(NO_ERROR, mComposerClient->initCheck());

            mSurfaceControl = mComposerClient->createSurface(
                    String8("Test Surface"), 0,
                    getSurfaceWidth(), getSurfaceHeight(),
                    PIXEL_FORMAT_RGB_888, 0);

            ASSERT_TRUE(mSurfaceControl != NULL);
            ASSERT_TRUE(mSurfaceControl->isValid());

            SurfaceComposerClient::openGlobalTransaction();
            ASSERT_EQ(NO_ERROR, mSurfaceControl->setLayer(0x7FFFFFFF));
            ASSERT_EQ(NO_ERROR, mSurfaceControl->show());
            SurfaceComposerClient::closeGlobalTransaction();

            sp<ANativeWindow> window = mSurfaceControl->getSurface();
            mEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
                    window.get(), NULL);
        } else {
            LOGV("No actual display. Choosing EGLSurface based on SurfaceMediaSource");
            sp<SurfaceMediaSource> sms = new SurfaceMediaSource(
                    getSurfaceWidth(), getSurfaceHeight());
            sp<SurfaceTextureClient> stc = new SurfaceTextureClient(sms);
            sp<ANativeWindow> window = stc;

            mEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
                    window.get(), NULL);
        }
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_SURFACE, mEglSurface);

        mEglContext = eglCreateContext(mEglDisplay, mGlConfig, EGL_NO_CONTEXT,
                getContextAttribs());
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_CONTEXT, mEglContext);

        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
                mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        EGLint w, h;
        EXPECT_TRUE(eglQuerySurface(mEglDisplay, mEglSurface, EGL_WIDTH, &w));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        EXPECT_TRUE(eglQuerySurface(mEglDisplay, mEglSurface, EGL_HEIGHT, &h));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        RecordProperty("EglSurfaceWidth", w);
        RecordProperty("EglSurfaceHeight", h);

        glViewport(0, 0, w, h);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
    }

    virtual void TearDown() {
        // Display the result
        if (mDisplaySecs > 0 && mEglSurface != EGL_NO_SURFACE) {
            eglSwapBuffers(mEglDisplay, mEglSurface);
            sleep(mDisplaySecs);
        }

        if (mComposerClient != NULL) {
            mComposerClient->dispose();
        }
        if (mEglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(mEglDisplay, mEglContext);
        }
        if (mEglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(mEglDisplay, mEglSurface);
        }
        if (mEglDisplay != EGL_NO_DISPLAY) {
            eglTerminate(mEglDisplay);
        }
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
    }

    virtual EGLint const* getConfigAttribs() {
        LOGV("GLTest getConfigAttribs");
        static EGLint sDefaultConfigAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 16,
            EGL_STENCIL_SIZE, 8,
            EGL_NONE };

        return sDefaultConfigAttribs;
    }

    virtual EGLint const* getContextAttribs() {
        static EGLint sDefaultContextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE };

        return sDefaultContextAttribs;
    }

    virtual EGLint getSurfaceWidth() {
        return 512;
    }

    virtual EGLint getSurfaceHeight() {
        return 512;
    }

    void loadShader(GLenum shaderType, const char* pSource, GLuint* outShader) {
        GLuint shader = glCreateShader(shaderType);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        if (shader) {
            glShaderSource(shader, 1, &pSource, NULL);
            ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
            glCompileShader(shader);
            ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
            GLint compiled = 0;
            glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
            ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
            if (!compiled) {
                GLint infoLen = 0;
                glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
                ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
                if (infoLen) {
                    char* buf = (char*) malloc(infoLen);
                    if (buf) {
                        glGetShaderInfoLog(shader, infoLen, NULL, buf);
                        printf("Shader compile log:\n%s\n", buf);
                        free(buf);
                        FAIL();
                    }
                } else {
                    char* buf = (char*) malloc(0x1000);
                    if (buf) {
                        glGetShaderInfoLog(shader, 0x1000, NULL, buf);
                        printf("Shader compile log:\n%s\n", buf);
                        free(buf);
                        FAIL();
                    }
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
        ASSERT_TRUE(shader != 0);
        *outShader = shader;
    }

    void createProgram(const char* pVertexSource, const char* pFragmentSource,
            GLuint* outPgm) {
        GLuint vertexShader, fragmentShader;
        {
            SCOPED_TRACE("compiling vertex shader");
            loadShader(GL_VERTEX_SHADER, pVertexSource, &vertexShader);
            if (HasFatalFailure()) {
                return;
            }
        }
        {
            SCOPED_TRACE("compiling fragment shader");
            loadShader(GL_FRAGMENT_SHADER, pFragmentSource, &fragmentShader);
            if (HasFatalFailure()) {
                return;
            }
        }

        GLuint program = glCreateProgram();
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        if (program) {
            glAttachShader(program, vertexShader);
            ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
            glAttachShader(program, fragmentShader);
            ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
            glLinkProgram(program);
            GLint linkStatus = GL_FALSE;
            glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
            if (linkStatus != GL_TRUE) {
                GLint bufLength = 0;
                glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
                if (bufLength) {
                    char* buf = (char*) malloc(bufLength);
                    if (buf) {
                        glGetProgramInfoLog(program, bufLength, NULL, buf);
                        printf("Program link log:\n%s\n", buf);
                        free(buf);
                        FAIL();
                    }
                }
                glDeleteProgram(program);
                program = 0;
            }
        }
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        ASSERT_TRUE(program != 0);
        *outPgm = program;
    }

    static int abs(int value) {
        return value > 0 ? value : -value;
    }

    ::testing::AssertionResult checkPixel(int x, int y, int r,
            int g, int b, int a, int tolerance=2) {
        GLubyte pixel[4];
        String8 msg;
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            msg += String8::format("error reading pixel: %#x", err);
            while ((err = glGetError()) != GL_NO_ERROR) {
                msg += String8::format(", %#x", err);
            }
            fprintf(stderr, "pixel check failure: %s\n", msg.string());
            return ::testing::AssertionFailure(
                    ::testing::Message(msg.string()));
        }
        if (r >= 0 && abs(r - int(pixel[0])) > tolerance) {
            msg += String8::format("r(%d isn't %d)", pixel[0], r);
        }
        if (g >= 0 && abs(g - int(pixel[1])) > tolerance) {
            if (!msg.isEmpty()) {
                msg += " ";
            }
            msg += String8::format("g(%d isn't %d)", pixel[1], g);
        }
        if (b >= 0 && abs(b - int(pixel[2])) > tolerance) {
            if (!msg.isEmpty()) {
                msg += " ";
            }
            msg += String8::format("b(%d isn't %d)", pixel[2], b);
        }
        if (a >= 0 && abs(a - int(pixel[3])) > tolerance) {
            if (!msg.isEmpty()) {
                msg += " ";
            }
            msg += String8::format("a(%d isn't %d)", pixel[3], a);
        }
        if (!msg.isEmpty()) {
            fprintf(stderr, "pixel check failure: %s\n", msg.string());
            return ::testing::AssertionFailure(
                    ::testing::Message(msg.string()));
        } else {
            return ::testing::AssertionSuccess();
        }
    }

    int mDisplaySecs;
    sp<SurfaceComposerClient> mComposerClient;
    sp<SurfaceControl> mSurfaceControl;

    EGLDisplay mEglDisplay;
    EGLSurface mEglSurface;
    EGLContext mEglContext;
    EGLConfig  mGlConfig;
};

///////////////////////////////////////////////////////////////////////
//    Class for  the NON-GL tests
///////////////////////////////////////////////////////////////////////
class SurfaceMediaSourceTest : public ::testing::Test {
public:

    SurfaceMediaSourceTest( ): mYuvTexWidth(176), mYuvTexHeight(144) { }
    void oneBufferPass(int width, int height );
    void oneBufferPassNoFill(int width, int height );
    static void fillYV12Buffer(uint8_t* buf, int w, int h, int stride) ;
    static void fillYV12BufferRect(uint8_t* buf, int w, int h,
                        int stride, const android_native_rect_t& rect) ;
protected:

    virtual void SetUp() {
        android::ProcessState::self()->startThreadPool();
        mSMS = new SurfaceMediaSource(mYuvTexWidth, mYuvTexHeight);
        mSMS->setSynchronousMode(true);
        mSTC = new SurfaceTextureClient(mSMS);
        mANW = mSTC;
    }

    virtual void TearDown() {
        mSMS.clear();
        mSTC.clear();
        mANW.clear();
    }

    const int mYuvTexWidth;
    const int mYuvTexHeight;

    sp<SurfaceMediaSource> mSMS;
    sp<SurfaceTextureClient> mSTC;
    sp<ANativeWindow> mANW;
};

///////////////////////////////////////////////////////////////////////
//    Class for  the GL tests
///////////////////////////////////////////////////////////////////////
class SurfaceMediaSourceGLTest : public GLTest {
public:

    SurfaceMediaSourceGLTest( ): mYuvTexWidth(176), mYuvTexHeight(144) { }
    virtual EGLint const* getConfigAttribs();
    void oneBufferPassGL(int num = 0);
    static sp<MediaRecorder> setUpMediaRecorder(int fileDescriptor, int videoSource,
        int outputFormat, int videoEncoder, int width, int height, int fps);
protected:

    virtual void SetUp() {
        LOGV("SMS-GLTest::SetUp()");
        android::ProcessState::self()->startThreadPool();
        mSMS = new SurfaceMediaSource(mYuvTexWidth, mYuvTexHeight);
        mSTC = new SurfaceTextureClient(mSMS);
        mANW = mSTC;

        // Doing the setup related to the GL Side
        GLTest::SetUp();
    }

    virtual void TearDown() {
        mSMS.clear();
        mSTC.clear();
        mANW.clear();
        GLTest::TearDown();
    }

    void setUpEGLSurfaceFromMediaRecorder(sp<MediaRecorder>& mr);

    const int mYuvTexWidth;
    const int mYuvTexHeight;

    sp<SurfaceMediaSource> mSMS;
    sp<SurfaceTextureClient> mSTC;
    sp<ANativeWindow> mANW;
};

/////////////////////////////////////////////////////////////////////
// Methods in SurfaceMediaSourceGLTest
/////////////////////////////////////////////////////////////////////
EGLint const* SurfaceMediaSourceGLTest::getConfigAttribs() {
        LOGV("SurfaceMediaSourceGLTest getConfigAttribs");
    static EGLint sDefaultConfigAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_RECORDABLE_ANDROID, EGL_TRUE,
        EGL_NONE };

    return sDefaultConfigAttribs;
}

// One pass of dequeuing and queuing a GLBuffer
void SurfaceMediaSourceGLTest::oneBufferPassGL(int num) {
    int d = num % 50;
    float f = 0.2f; // 0.1f * d;

    glClearColor(0, 0.3, 0, 0.6);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    glScissor(4 + d, 4 + d, 4, 4);
    glClearColor(1.0 - f, f, f, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glScissor(24 + d, 48 + d, 4, 4);
    glClearColor(f, 1.0 - f, f, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glScissor(37 + d, 17 + d, 4, 4);
    glClearColor(f, f, 1.0 - f, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    // The following call dequeues and queues the buffer
    eglSwapBuffers(mEglDisplay, mEglSurface);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    glDisable(GL_SCISSOR_TEST);
}

// Set up the MediaRecorder which runs in the same process as mediaserver
sp<MediaRecorder> SurfaceMediaSourceGLTest::setUpMediaRecorder(int fd, int videoSource,
        int outputFormat, int videoEncoder, int width, int height, int fps) {
    sp<MediaRecorder> mr = new MediaRecorder();
    mr->setVideoSource(videoSource);
    mr->setOutputFormat(outputFormat);
    mr->setVideoEncoder(videoEncoder);
    mr->setOutputFile(fd, 0, 0);
    mr->setVideoSize(width, height);
    mr->setVideoFrameRate(fps);
    mr->prepare();
    LOGV("Starting MediaRecorder...");
    CHECK_EQ(OK, mr->start());
    return mr;
}

// query the mediarecorder for a surfacemeidasource and create an egl surface with that
void SurfaceMediaSourceGLTest::setUpEGLSurfaceFromMediaRecorder(sp<MediaRecorder>& mr) {
    sp<ISurfaceTexture> iST = mr->querySurfaceMediaSourceFromMediaServer();
    mSTC = new SurfaceTextureClient(iST);
    mANW = mSTC;

    mEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
                                mANW.get(), NULL);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_SURFACE, mEglSurface) ;

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
            mEglContext));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
}


/////////////////////////////////////////////////////////////////////
// Methods in SurfaceMediaSourceTest
/////////////////////////////////////////////////////////////////////

// One pass of dequeuing and queuing the buffer. Fill it in with
// cpu YV12 buffer
void SurfaceMediaSourceTest::oneBufferPass(int width, int height ) {
    ANativeWindowBuffer* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    SurfaceMediaSourceTest::fillYV12Buffer(img, width, height, buf->getStride());
    buf->unlock();

    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));
}

// Dequeuing and queuing the buffer without really filling it in.
void SurfaceMediaSourceTest::oneBufferPassNoFill(int width, int height ) {
    ANativeWindowBuffer* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    // ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));
    // We do not fill the buffer in. Just queue it back.
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));
}

// Fill a YV12 buffer with a multi-colored checkerboard pattern
void SurfaceMediaSourceTest::fillYV12Buffer(uint8_t* buf, int w, int h, int stride) {
    const int blockWidth = w > 16 ? w / 16 : 1;
    const int blockHeight = h > 16 ? h / 16 : 1;
    const int yuvTexOffsetY = 0;
    int yuvTexStrideY = stride;
    int yuvTexOffsetV = yuvTexStrideY * h;
    int yuvTexStrideV = (yuvTexStrideY/2 + 0xf) & ~0xf;
    int yuvTexOffsetU = yuvTexOffsetV + yuvTexStrideV * h/2;
    int yuvTexStrideU = yuvTexStrideV;
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            int parityX = (x / blockWidth) & 1;
            int parityY = (y / blockHeight) & 1;
            unsigned char intensity = (parityX ^ parityY) ? 63 : 191;
            buf[yuvTexOffsetY + (y * yuvTexStrideY) + x] = intensity;
            if (x < w / 2 && y < h / 2) {
                buf[yuvTexOffsetU + (y * yuvTexStrideU) + x] = intensity;
                if (x * 2 < w / 2 && y * 2 < h / 2) {
                    buf[yuvTexOffsetV + (y*2 * yuvTexStrideV) + x*2 + 0] =
                    buf[yuvTexOffsetV + (y*2 * yuvTexStrideV) + x*2 + 1] =
                    buf[yuvTexOffsetV + ((y*2+1) * yuvTexStrideV) + x*2 + 0] =
                    buf[yuvTexOffsetV + ((y*2+1) * yuvTexStrideV) + x*2 + 1] =
                        intensity;
                }
            }
        }
    }
}

// Fill a YV12 buffer with red outside a given rectangle and green inside it.
void SurfaceMediaSourceTest::fillYV12BufferRect(uint8_t* buf, int w,
                  int h, int stride, const android_native_rect_t& rect) {
    const int yuvTexOffsetY = 0;
    int yuvTexStrideY = stride;
    int yuvTexOffsetV = yuvTexStrideY * h;
    int yuvTexStrideV = (yuvTexStrideY/2 + 0xf) & ~0xf;
    int yuvTexOffsetU = yuvTexOffsetV + yuvTexStrideV * h/2;
    int yuvTexStrideU = yuvTexStrideV;
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            bool inside = rect.left <= x && x < rect.right &&
                    rect.top <= y && y < rect.bottom;
            buf[yuvTexOffsetY + (y * yuvTexStrideY) + x] = inside ? 240 : 64;
            if (x < w / 2 && y < h / 2) {
                bool inside = rect.left <= 2*x && 2*x < rect.right &&
                        rect.top <= 2*y && 2*y < rect.bottom;
                buf[yuvTexOffsetU + (y * yuvTexStrideU) + x] = 16;
                buf[yuvTexOffsetV + (y * yuvTexStrideV) + x] =
                                                inside ? 16 : 255;
            }
        }
    }
}  ///////// End of class SurfaceMediaSourceTest

///////////////////////////////////////////////////////////////////
// Class to imitate the recording     /////////////////////////////
// ////////////////////////////////////////////////////////////////
struct SimpleDummyRecorder {
        sp<MediaSource> mSource;

        SimpleDummyRecorder
                (const sp<MediaSource> &source): mSource(source) {}

        status_t start() { return mSource->start();}
        status_t stop()  { return mSource->stop();}

        // fakes reading from a media source
        status_t readFromSource() {
            MediaBuffer *buffer;
            status_t err = mSource->read(&buffer);
            if (err != OK) {
                return err;
            }
            buffer->release();
            buffer = NULL;
            return OK;
        }
};
///////////////////////////////////////////////////////////////////
//           TESTS
// SurfaceMediaSourceTest class contains tests that fill the buffers
// using the cpu calls
// SurfaceMediaSourceGLTest class contains tests that fill the buffers
// using the GL calls.
// TODO: None of the tests actually verify the encoded images.. so at this point,
// these are mostly functionality tests + visual inspection
//////////////////////////////////////////////////////////////////////

// Just pass one buffer from the native_window to the SurfaceMediaSource
// Dummy Encoder
static int testId = 1;
TEST_F(SurfaceMediaSourceTest, DISABLED_DummyEncodingFromCpuFilledYV12BufferNpotOneBufferPass) {
    LOGV("Test # %d", testId++);
    LOGV("Testing OneBufferPass ******************************");

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
            HAL_PIXEL_FORMAT_YV12));
    oneBufferPass(mYuvTexWidth, mYuvTexHeight);
}

// Pass the buffer with the wrong height and weight and should not be accepted
// Dummy Encoder
TEST_F(SurfaceMediaSourceTest, DISABLED_DummyEncodingFromCpuFilledYV12BufferNpotWrongSizeBufferPass) {
    LOGV("Test # %d", testId++);
    LOGV("Testing Wrong size BufferPass ******************************");

    // setting the client side buffer size different than the server size
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_dimensions(mANW.get(),
             10, 10));
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
            HAL_PIXEL_FORMAT_YV12));

    ANativeWindowBuffer* anb;

    // Note: make sure we get an ERROR back when dequeuing!
    ASSERT_NE(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
}

// pass multiple buffers from the native_window the SurfaceMediaSource
// Dummy Encoder
TEST_F(SurfaceMediaSourceTest,  DISABLED_DummyEncodingFromCpuFilledYV12BufferNpotMultiBufferPass) {
    LOGV("Test # %d", testId++);
    LOGV("Testing MultiBufferPass, Dummy Recorder *********************");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
            HAL_PIXEL_FORMAT_YV12));

    SimpleDummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 0;
    while (nFramesCount < 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);

        ASSERT_EQ(NO_ERROR, writer.readFromSource());

        nFramesCount++;
    }
    writer.stop();
}

// Delayed pass of multiple buffers from the native_window the SurfaceMediaSource
// Dummy Encoder
TEST_F(SurfaceMediaSourceTest,  DISABLED_DummyLagEncodingFromCpuFilledYV12BufferNpotMultiBufferPass) {
    LOGV("Test # %d", testId++);
    LOGV("Testing MultiBufferPass, Dummy Recorder Lagging **************");

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
            HAL_PIXEL_FORMAT_YV12));

    SimpleDummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 1;
    const int FRAMES_LAG = mSMS->getBufferCount() - 1;
    while (nFramesCount <= 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);
        // Forcing the writer to lag behind a few frames
        if (nFramesCount > FRAMES_LAG) {
            ASSERT_EQ(NO_ERROR, writer.readFromSource());
        }
        nFramesCount++;
    }
    writer.stop();
}

// pass multiple buffers from the native_window the SurfaceMediaSource
// A dummy writer (MULTITHREADED) is used to simulate actual MPEG4Writer
TEST_F(SurfaceMediaSourceTest, DISABLED_DummyThreadedEncodingFromCpuFilledYV12BufferNpotMultiBufferPass) {
    LOGV("Test # %d", testId++);
    LOGV("Testing MultiBufferPass, Dummy Recorder Multi-Threaded **********");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
            HAL_PIXEL_FORMAT_YV12));

    DummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);

        nFramesCount++;
    }
    writer.stop();
}

// Test to examine actual encoding using mediarecorder
// We use the mediaserver to create a mediarecorder and send
// it back to us. So SurfaceMediaSource lives in the same process
// as the mediaserver.
// Very close to the actual camera, except that the
// buffers are filled and queueud by the CPU instead of GL.
TEST_F(SurfaceMediaSourceTest, DISABLED_EncodingFromCpuYV12BufferNpotWriteMediaServer) {
    LOGV("Test # %d", testId++);
    LOGV("************** Testing the whole pipeline with actual MediaRecorder ***********");
    LOGV("************** SurfaceMediaSource is same process as mediaserver    ***********");

    const char *fileName = "/sdcard/outputSurfEncMSource.mp4";
    int fd = open(fileName, O_RDWR | O_CREAT, 0744);
    if (fd < 0) {
        LOGE("ERROR: Could not open the the file %s, fd = %d !!", fileName, fd);
    }
    CHECK(fd >= 0);

    sp<MediaRecorder> mr = SurfaceMediaSourceGLTest::setUpMediaRecorder(fd,
            VIDEO_SOURCE_GRALLOC_BUFFER,
            OUTPUT_FORMAT_MPEG_4, VIDEO_ENCODER_H264, mYuvTexWidth,
            mYuvTexHeight, 30);
    // get the reference to the surfacemediasource living in
    // mediaserver that is created by stagefrightrecorder
    sp<ISurfaceTexture> iST = mr->querySurfaceMediaSourceFromMediaServer();
    mSTC = new SurfaceTextureClient(iST);
    mANW = mSTC;
    ASSERT_EQ(NO_ERROR, native_window_api_connect(mANW.get(), NATIVE_WINDOW_API_CPU));
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_format(mANW.get(),
                                                HAL_PIXEL_FORMAT_YV12));

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPassNoFill(mYuvTexWidth, mYuvTexHeight);
        nFramesCount++;
        LOGV("framesCount = %d", nFramesCount);
    }

    ASSERT_EQ(NO_ERROR, native_window_api_disconnect(mANW.get(), NATIVE_WINDOW_API_CPU));
    LOGV("Stopping MediaRecorder...");
    CHECK_EQ(OK, mr->stop());
    mr.clear();
    close(fd);
}

//////////////////////////////////////////////////////////////////////
// GL tests
/////////////////////////////////////////////////////////////////////

// Test to examine whether we can choose the Recordable Android GLConfig
// DummyRecorder used- no real encoding here
TEST_F(SurfaceMediaSourceGLTest, ChooseAndroidRecordableEGLConfigDummyWriter) {
    LOGV("Test # %d", testId++);
    LOGV("Verify creating a surface w/ right config + dummy writer*********");

    mSMS = new SurfaceMediaSource(mYuvTexWidth, mYuvTexHeight);
    mSTC = new SurfaceTextureClient(mSMS);
    mANW = mSTC;

    DummyRecorder writer(mSMS);
    writer.start();

    mEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
                                mANW.get(), NULL);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_SURFACE, mEglSurface) ;

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
            mEglContext));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPassGL();
        nFramesCount++;
        LOGV("framesCount = %d", nFramesCount);
    }

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
            EGL_NO_CONTEXT));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    eglDestroySurface(mEglDisplay, mEglSurface);
    mEglSurface = EGL_NO_SURFACE;

    writer.stop();
}
// Test to examine whether we can render GL buffers in to the surface
// created with the native window handle
TEST_F(SurfaceMediaSourceGLTest, RenderingToRecordableEGLSurfaceWorks) {
    LOGV("Test # %d", testId++);
    LOGV("RenderingToRecordableEGLSurfaceWorks *********************");
    // Do the producer side of things
    glClearColor(0.6, 0.6, 0.6, 0.6);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    glScissor(4, 4, 4, 4);
    glClearColor(1.0, 0.0, 0.0, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glScissor(24, 48, 4, 4);
    glClearColor(0.0, 1.0, 0.0, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glScissor(37, 17, 4, 4);
    glClearColor(0.0, 0.0, 1.0, 1.0);
    glClear(GL_COLOR_BUFFER_BIT);

    EXPECT_TRUE(checkPixel( 0,  0, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(63,  0, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(63, 63, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel( 0, 63, 153, 153, 153, 153));

    EXPECT_TRUE(checkPixel( 4,  7, 255,   0,   0, 255));
    EXPECT_TRUE(checkPixel(25, 51,   0, 255,   0, 255));
    EXPECT_TRUE(checkPixel(40, 19,   0,   0, 255, 255));
    EXPECT_TRUE(checkPixel(29, 51, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel( 5, 32, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(13,  8, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(46,  3, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(30, 33, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel( 6, 52, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(55, 33, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(16, 29, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel( 1, 30, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(41, 37, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(46, 29, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel(15, 25, 153, 153, 153, 153));
    EXPECT_TRUE(checkPixel( 3, 52, 153, 153, 153, 153));
}

// Test to examine the actual encoding with GL buffers
// Actual encoder, Actual GL Buffers Filled SurfaceMediaSource
// The same pattern is rendered every frame
TEST_F(SurfaceMediaSourceGLTest, EncodingFromGLRgbaSameImageEachBufNpotWrite) {
    LOGV("Test # %d", testId++);
    LOGV("************** Testing the whole pipeline with actual Recorder ***********");
    LOGV("************** GL Filling the buffers ***********");
    // Note: No need to set the colorformat for the buffers. The colorformat is
    // in the GRAlloc buffers itself.

    const char *fileName = "/sdcard/outputSurfEncMSourceGL.mp4";
    int fd = open(fileName, O_RDWR | O_CREAT, 0744);
    if (fd < 0) {
        LOGE("ERROR: Could not open the the file %s, fd = %d !!", fileName, fd);
    }
    CHECK(fd >= 0);

    sp<MediaRecorder> mr = setUpMediaRecorder(fd, VIDEO_SOURCE_GRALLOC_BUFFER,
            OUTPUT_FORMAT_MPEG_4, VIDEO_ENCODER_H264, mYuvTexWidth, mYuvTexHeight, 30);

    // get the reference to the surfacemediasource living in
    // mediaserver that is created by stagefrightrecorder
    setUpEGLSurfaceFromMediaRecorder(mr);

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPassGL();
        nFramesCount++;
        LOGV("framesCount = %d", nFramesCount);
    }

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
            EGL_NO_CONTEXT));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    eglDestroySurface(mEglDisplay, mEglSurface);
    mEglSurface = EGL_NO_SURFACE;

    LOGV("Stopping MediaRecorder...");
    CHECK_EQ(OK, mr->stop());
    mr.clear();
    close(fd);
}

// Test to examine the actual encoding from the GL Buffers
// Actual encoder, Actual GL Buffers Filled SurfaceMediaSource
// A different pattern is rendered every frame
TEST_F(SurfaceMediaSourceGLTest, EncodingFromGLRgbaDiffImageEachBufNpotWrite) {
    LOGV("Test # %d", testId++);
    LOGV("************** Testing the whole pipeline with actual Recorder ***********");
    LOGV("************** Diff GL Filling the buffers ***********");
    // Note: No need to set the colorformat for the buffers. The colorformat is
    // in the GRAlloc buffers itself.

    const char *fileName = "/sdcard/outputSurfEncMSourceGLDiff.mp4";
    int fd = open(fileName, O_RDWR | O_CREAT, 0744);
    if (fd < 0) {
        LOGE("ERROR: Could not open the the file %s, fd = %d !!", fileName, fd);
    }
    CHECK(fd >= 0);

    sp<MediaRecorder> mr = setUpMediaRecorder(fd, VIDEO_SOURCE_GRALLOC_BUFFER,
            OUTPUT_FORMAT_MPEG_4, VIDEO_ENCODER_H264, mYuvTexWidth, mYuvTexHeight, 30);

    // get the reference to the surfacemediasource living in
    // mediaserver that is created by stagefrightrecorder
    setUpEGLSurfaceFromMediaRecorder(mr);

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPassGL(nFramesCount);
        nFramesCount++;
        LOGV("framesCount = %d", nFramesCount);
    }

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
            EGL_NO_CONTEXT));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    eglDestroySurface(mEglDisplay, mEglSurface);
    mEglSurface = EGL_NO_SURFACE;

    LOGV("Stopping MediaRecorder...");
    CHECK_EQ(OK, mr->stop());
    mr.clear();
    close(fd);
}
} // namespace android
