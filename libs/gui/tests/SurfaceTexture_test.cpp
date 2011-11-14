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

#define LOG_TAG "SurfaceTexture_test"
//#define LOG_NDEBUG 0

#include <gtest/gtest.h>
#include <gui/SurfaceTexture.h>
#include <gui/SurfaceTextureClient.h>
#include <ui/GraphicBuffer.h>
#include <utils/String8.h>
#include <utils/threads.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/FramebufferNativeWindow.h>

namespace android {

class GLTest : public ::testing::Test {
protected:

    GLTest():
            mEglDisplay(EGL_NO_DISPLAY),
            mEglSurface(EGL_NO_SURFACE),
            mEglContext(EGL_NO_CONTEXT) {
    }

    virtual void SetUp() {
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
            EGLint pbufferAttribs[] = {
                EGL_WIDTH, getSurfaceWidth(),
                EGL_HEIGHT, getSurfaceHeight(),
                EGL_NONE };

            mEglSurface = eglCreatePbufferSurface(mEglDisplay, mGlConfig,
                    pbufferAttribs);
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
            eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
                    EGL_NO_CONTEXT);
            eglTerminate(mEglDisplay);
        }
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
    }

    virtual EGLint const* getConfigAttribs() {
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

// XXX: Code above this point should live elsewhere

class SurfaceTextureGLTest : public GLTest {
protected:
    enum { TEX_ID = 123 };

    virtual void SetUp() {
        GLTest::SetUp();
        mST = new SurfaceTexture(TEX_ID);
        mSTC = new SurfaceTextureClient(mST);
        mANW = mSTC;

        const char vsrc[] =
            "attribute vec4 vPosition;\n"
            "varying vec2 texCoords;\n"
            "uniform mat4 texMatrix;\n"
            "void main() {\n"
            "  vec2 vTexCoords = 0.5 * (vPosition.xy + vec2(1.0, 1.0));\n"
            "  texCoords = (texMatrix * vec4(vTexCoords, 0.0, 1.0)).xy;\n"
            "  gl_Position = vPosition;\n"
            "}\n";

        const char fsrc[] =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision mediump float;\n"
            "uniform samplerExternalOES texSampler;\n"
            "varying vec2 texCoords;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(texSampler, texCoords);\n"
            "}\n";

        {
            SCOPED_TRACE("creating shader program");
            createProgram(vsrc, fsrc, &mPgm);
            if (HasFatalFailure()) {
                return;
            }
        }

        mPositionHandle = glGetAttribLocation(mPgm, "vPosition");
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        ASSERT_NE(-1, mPositionHandle);
        mTexSamplerHandle = glGetUniformLocation(mPgm, "texSampler");
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        ASSERT_NE(-1, mTexSamplerHandle);
        mTexMatrixHandle = glGetUniformLocation(mPgm, "texMatrix");
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        ASSERT_NE(-1, mTexMatrixHandle);
    }

    virtual void TearDown() {
        mANW.clear();
        mSTC.clear();
        mST.clear();
        GLTest::TearDown();
    }

    // drawTexture draws the SurfaceTexture over the entire GL viewport.
    void drawTexture() {
        const GLfloat triangleVertices[] = {
            -1.0f, 1.0f,
            -1.0f, -1.0f,
            1.0f, -1.0f,
            1.0f, 1.0f,
        };

        glVertexAttribPointer(mPositionHandle, 2, GL_FLOAT, GL_FALSE, 0, triangleVertices);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        glEnableVertexAttribArray(mPositionHandle);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());

        glUseProgram(mPgm);
        glUniform1i(mTexSamplerHandle, 0);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, TEX_ID);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());

        // XXX: These calls are not needed for GL_TEXTURE_EXTERNAL_OES as
        // they're setting the defautls for that target, but when hacking things
        // to use GL_TEXTURE_2D they are needed to achieve the same behavior.
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());

        GLfloat texMatrix[16];
        mST->getTransformMatrix(texMatrix);
        glUniformMatrix4fv(mTexMatrixHandle, 1, GL_FALSE, texMatrix);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
    }

    class FrameWaiter : public SurfaceTexture::FrameAvailableListener {
    public:
        FrameWaiter():
                mPendingFrames(0) {
        }

        void waitForFrame() {
            Mutex::Autolock lock(mMutex);
            while (mPendingFrames == 0) {
                mCondition.wait(mMutex);
            }
            mPendingFrames--;
        }

        virtual void onFrameAvailable() {
            Mutex::Autolock lock(mMutex);
            mPendingFrames++;
            mCondition.signal();
        }

        int mPendingFrames;
        Mutex mMutex;
        Condition mCondition;
    };

    sp<SurfaceTexture> mST;
    sp<SurfaceTextureClient> mSTC;
    sp<ANativeWindow> mANW;

    GLuint mPgm;
    GLint mPositionHandle;
    GLint mTexSamplerHandle;
    GLint mTexMatrixHandle;
};

// Fill a YV12 buffer with a multi-colored checkerboard pattern
void fillYV12Buffer(uint8_t* buf, int w, int h, int stride) {
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
void fillYV12BufferRect(uint8_t* buf, int w, int h, int stride,
        const android_native_rect_t& rect) {
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
}

void fillRGBA8Buffer(uint8_t* buf, int w, int h, int stride) {
    const size_t PIXEL_SIZE = 4;
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            off_t offset = (y * stride + x) * PIXEL_SIZE;
            for (int c = 0; c < 4; c++) {
                int parityX = (x / (1 << (c+2))) & 1;
                int parityY = (y / (1 << (c+2))) & 1;
                buf[offset + c] = (parityX ^ parityY) ? 231 : 35;
            }
        }
    }
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BufferNpot) {
    const int texWidth = 64;
    const int texHeight = 66;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    ANativeWindowBuffer* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    fillYV12Buffer(img, texWidth, texHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, texWidth, texHeight);
    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(63,  0,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel(63, 65,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel( 0, 65, 255, 127, 255, 255));

    EXPECT_TRUE(checkPixel(22, 44, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(45, 52, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(52, 51,  98, 255,  73, 255));
    EXPECT_TRUE(checkPixel( 7, 31, 155,   0, 118, 255));
    EXPECT_TRUE(checkPixel(31,  9, 107,  24,  87, 255));
    EXPECT_TRUE(checkPixel(29, 35, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(36, 22, 155,  29,   0, 255));
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BufferPow2) {
    const int texWidth = 64;
    const int texHeight = 64;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    ANativeWindowBuffer* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    fillYV12Buffer(img, texWidth, texHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, texWidth, texHeight);
    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel(63,  0, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(63, 63,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel( 0, 63, 255, 127, 255, 255));

    EXPECT_TRUE(checkPixel(22, 19, 100, 255,  74, 255));
    EXPECT_TRUE(checkPixel(45, 11, 100, 255,  74, 255));
    EXPECT_TRUE(checkPixel(52, 12, 155,   0, 181, 255));
    EXPECT_TRUE(checkPixel( 7, 32, 150, 237, 170, 255));
    EXPECT_TRUE(checkPixel(31, 54,   0,  71, 117, 255));
    EXPECT_TRUE(checkPixel(29, 28,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel(36, 41, 100, 232, 255, 255));
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BufferWithCrop) {
    const int texWidth = 64;
    const int texHeight = 66;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    android_native_rect_t crops[] = {
        {4, 6, 22, 36},
        {0, 6, 22, 36},
        {4, 0, 22, 36},
        {4, 6, texWidth, 36},
        {4, 6, 22, texHeight},
    };

    for (int i = 0; i < 5; i++) {
        const android_native_rect_t& crop(crops[i]);
        SCOPED_TRACE(String8::format("rect{ l: %d t: %d r: %d b: %d }", crop.left,
                crop.top, crop.right, crop.bottom).string());

        ASSERT_EQ(NO_ERROR, native_window_set_crop(mANW.get(), &crop));

        ANativeWindowBuffer* anb;
        ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
        ASSERT_TRUE(anb != NULL);

        sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
        ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

        uint8_t* img = NULL;
        buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
        fillYV12BufferRect(img, texWidth, texHeight, buf->getStride(), crop);
        buf->unlock();
        ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

        mST->updateTexImage();

        glClearColor(0.2, 0.2, 0.2, 0.2);
        glClear(GL_COLOR_BUFFER_BIT);

        glViewport(0, 0, 64, 64);
        drawTexture();

        EXPECT_TRUE(checkPixel( 0,  0,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(63,  0,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(63, 63,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel( 0, 63,  82, 255,  35, 255));

        EXPECT_TRUE(checkPixel(25, 14,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(35, 31,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(57,  6,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel( 5, 42,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(32, 33,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(16, 26,  82, 255,  35, 255));
        EXPECT_TRUE(checkPixel(46, 51,  82, 255,  35, 255));
    }
}

// This test is intended to catch synchronization bugs between the CPU-written
// and GPU-read buffers.
TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BuffersRepeatedly) {
    enum { texWidth = 16 };
    enum { texHeight = 16 };
    enum { numFrames = 1024 };

    ASSERT_EQ(NO_ERROR, mST->setSynchronousMode(true));
    ASSERT_EQ(NO_ERROR, mST->setBufferCountServer(2));
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_WRITE_OFTEN));

    struct TestPixel {
        int x;
        int y;
    };
    const TestPixel testPixels[] = {
        {  4, 11 },
        { 12, 14 },
        {  7,  2 },
    };
    enum {numTestPixels = sizeof(testPixels) / sizeof(testPixels[0])};

    class ProducerThread : public Thread {
    public:
        ProducerThread(const sp<ANativeWindow>& anw, const TestPixel* testPixels):
                mANW(anw),
                mTestPixels(testPixels) {
        }

        virtual ~ProducerThread() {
        }

        virtual bool threadLoop() {
            for (int i = 0; i < numFrames; i++) {
                ANativeWindowBuffer* anb;
                if (mANW->dequeueBuffer(mANW.get(), &anb) != NO_ERROR) {
                    return false;
                }
                if (anb == NULL) {
                    return false;
                }

                sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
                if (mANW->lockBuffer(mANW.get(), buf->getNativeBuffer())
                        != NO_ERROR) {
                    return false;
                }

                const int yuvTexOffsetY = 0;
                int stride = buf->getStride();
                int yuvTexStrideY = stride;
                int yuvTexOffsetV = yuvTexStrideY * texHeight;
                int yuvTexStrideV = (yuvTexStrideY/2 + 0xf) & ~0xf;
                int yuvTexOffsetU = yuvTexOffsetV + yuvTexStrideV * texHeight/2;
                int yuvTexStrideU = yuvTexStrideV;

                uint8_t* img = NULL;
                buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));

                // Gray out all the test pixels first, so we're more likely to
                // see a failure if GL is still texturing from the buffer we
                // just dequeued.
                for (int j = 0; j < numTestPixels; j++) {
                    int x = mTestPixels[j].x;
                    int y = mTestPixels[j].y;
                    uint8_t value = 128;
                    img[y*stride + x] = value;
                }

                // Fill the buffer with gray.
                for (int y = 0; y < texHeight; y++) {
                    for (int x = 0; x < texWidth; x++) {
                        img[yuvTexOffsetY + y*yuvTexStrideY + x] = 128;
                        img[yuvTexOffsetU + (y/2)*yuvTexStrideU + x/2] = 128;
                        img[yuvTexOffsetV + (y/2)*yuvTexStrideV + x/2] = 128;
                    }
                }

                // Set the test pixels to either white or black.
                for (int j = 0; j < numTestPixels; j++) {
                    int x = mTestPixels[j].x;
                    int y = mTestPixels[j].y;
                    uint8_t value = 0;
                    if (j == (i % numTestPixels)) {
                        value = 255;
                    }
                    img[y*stride + x] = value;
                }

                buf->unlock();
                if (mANW->queueBuffer(mANW.get(), buf->getNativeBuffer())
                        != NO_ERROR) {
                    return false;
                }
            }
            return false;
        }

        sp<ANativeWindow> mANW;
        const TestPixel* mTestPixels;
    };

    sp<FrameWaiter> fw(new FrameWaiter);
    mST->setFrameAvailableListener(fw);

    sp<Thread> pt(new ProducerThread(mANW, testPixels));
    pt->run();

    glViewport(0, 0, texWidth, texHeight);

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    // We wait for the first two frames up front so that the producer will be
    // likely to dequeue the buffer that's currently being textured from.
    fw->waitForFrame();
    fw->waitForFrame();

    for (int i = 0; i < numFrames; i++) {
        SCOPED_TRACE(String8::format("frame %d", i).string());

        // We must wait for each frame to come in because if we ever do an
        // updateTexImage call that doesn't consume a newly available buffer
        // then the producer and consumer will get out of sync, which will cause
        // a deadlock.
        if (i > 1) {
            fw->waitForFrame();
        }
        mST->updateTexImage();
        drawTexture();

        for (int j = 0; j < numTestPixels; j++) {
            int x = testPixels[j].x;
            int y = testPixels[j].y;
            uint8_t value = 0;
            if (j == (i % numTestPixels)) {
                // We must y-invert the texture coords
                EXPECT_TRUE(checkPixel(x, texHeight-y-1, 255, 255, 255, 255));
            } else {
                // We must y-invert the texture coords
                EXPECT_TRUE(checkPixel(x, texHeight-y-1, 0, 0, 0, 255));
            }
        }
    }

    pt->requestExitAndWait();
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledRGBABufferNpot) {
    const int texWidth = 64;
    const int texHeight = 66;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_RGBA_8888));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    android_native_buffer_t* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    fillRGBA8Buffer(img, texWidth, texHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, texWidth, texHeight);
    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0,  35,  35,  35,  35));
    EXPECT_TRUE(checkPixel(63,  0, 231, 231, 231, 231));
    EXPECT_TRUE(checkPixel(63, 65, 231, 231, 231, 231));
    EXPECT_TRUE(checkPixel( 0, 65,  35,  35,  35,  35));

    EXPECT_TRUE(checkPixel(15, 10,  35, 231, 231, 231));
    EXPECT_TRUE(checkPixel(23, 65, 231,  35, 231,  35));
    EXPECT_TRUE(checkPixel(19, 40,  35, 231,  35,  35));
    EXPECT_TRUE(checkPixel(38, 30, 231,  35,  35,  35));
    EXPECT_TRUE(checkPixel(42, 54,  35,  35,  35, 231));
    EXPECT_TRUE(checkPixel(37, 34,  35, 231, 231, 231));
    EXPECT_TRUE(checkPixel(31,  8, 231,  35,  35, 231));
    EXPECT_TRUE(checkPixel(37, 47, 231,  35, 231, 231));
    EXPECT_TRUE(checkPixel(25, 38,  35,  35,  35,  35));
    EXPECT_TRUE(checkPixel(49,  6,  35, 231,  35,  35));
    EXPECT_TRUE(checkPixel(54, 50,  35, 231, 231, 231));
    EXPECT_TRUE(checkPixel(27, 26, 231, 231, 231, 231));
    EXPECT_TRUE(checkPixel(10,  6,  35,  35, 231, 231));
    EXPECT_TRUE(checkPixel(29,  4,  35,  35,  35, 231));
    EXPECT_TRUE(checkPixel(55, 28,  35,  35, 231,  35));
    EXPECT_TRUE(checkPixel(58, 55,  35,  35, 231, 231));
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledRGBABufferPow2) {
    const int texWidth = 64;
    const int texHeight = 64;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            texWidth, texHeight, HAL_PIXEL_FORMAT_RGBA_8888));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    android_native_buffer_t* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    fillRGBA8Buffer(img, texWidth, texHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, texWidth, texHeight);
    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0, 231, 231, 231, 231));
    EXPECT_TRUE(checkPixel(63,  0,  35,  35,  35,  35));
    EXPECT_TRUE(checkPixel(63, 63, 231, 231, 231, 231));
    EXPECT_TRUE(checkPixel( 0, 63,  35,  35,  35,  35));

    EXPECT_TRUE(checkPixel(12, 46, 231, 231, 231,  35));
    EXPECT_TRUE(checkPixel(16,  1, 231, 231,  35, 231));
    EXPECT_TRUE(checkPixel(21, 12, 231,  35,  35, 231));
    EXPECT_TRUE(checkPixel(26, 51, 231,  35, 231,  35));
    EXPECT_TRUE(checkPixel( 5, 32,  35, 231, 231,  35));
    EXPECT_TRUE(checkPixel(13,  8,  35, 231, 231, 231));
    EXPECT_TRUE(checkPixel(46,  3,  35,  35, 231,  35));
    EXPECT_TRUE(checkPixel(30, 33,  35,  35,  35,  35));
    EXPECT_TRUE(checkPixel( 6, 52, 231, 231,  35,  35));
    EXPECT_TRUE(checkPixel(55, 33,  35, 231,  35, 231));
    EXPECT_TRUE(checkPixel(16, 29,  35,  35, 231, 231));
    EXPECT_TRUE(checkPixel( 1, 30,  35,  35,  35, 231));
    EXPECT_TRUE(checkPixel(41, 37,  35,  35, 231, 231));
    EXPECT_TRUE(checkPixel(46, 29, 231, 231,  35,  35));
    EXPECT_TRUE(checkPixel(15, 25,  35, 231,  35, 231));
    EXPECT_TRUE(checkPixel( 3, 52,  35, 231,  35,  35));
}

TEST_F(SurfaceTextureGLTest, TexturingFromGLFilledRGBABufferPow2) {
    const int texWidth = 64;
    const int texHeight = 64;

    mST->setDefaultBufferSize(texWidth, texHeight);

    // Do the producer side of things
    EGLSurface stcEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
            mANW.get(), NULL);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_SURFACE, stcEglSurface);

    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, stcEglSurface, stcEglSurface,
            mEglContext));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

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

    eglSwapBuffers(mEglDisplay, stcEglSurface);

    // Do the consumer side of things
    EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
            mEglContext));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    glDisable(GL_SCISSOR_TEST);

    mST->updateTexImage();

    // We must wait until updateTexImage has been called to destroy the
    // EGLSurface because we're in synchronous mode.
    eglDestroySurface(mEglDisplay, stcEglSurface);

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, texWidth, texHeight);
    drawTexture();

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

TEST_F(SurfaceTextureGLTest, AbandonUnblocksDequeueBuffer) {
    class ProducerThread : public Thread {
    public:
        ProducerThread(const sp<ANativeWindow>& anw):
                mANW(anw),
                mDequeueError(NO_ERROR) {
        }

        virtual ~ProducerThread() {
        }

        virtual bool threadLoop() {
            Mutex::Autolock lock(mMutex);
            ANativeWindowBuffer* anb;

            // Frame 1
            if (mANW->dequeueBuffer(mANW.get(), &anb) != NO_ERROR) {
                return false;
            }
            if (anb == NULL) {
                return false;
            }
            if (mANW->queueBuffer(mANW.get(), anb)
                    != NO_ERROR) {
                return false;
            }

            // Frame 2
            if (mANW->dequeueBuffer(mANW.get(), &anb) != NO_ERROR) {
                return false;
            }
            if (anb == NULL) {
                return false;
            }
            if (mANW->queueBuffer(mANW.get(), anb)
                    != NO_ERROR) {
                return false;
            }

            // Frame 3 - error expected
            mDequeueError = mANW->dequeueBuffer(mANW.get(), &anb);
            return false;
        }

        status_t getDequeueError() {
            Mutex::Autolock lock(mMutex);
            return mDequeueError;
        }

    private:
        sp<ANativeWindow> mANW;
        status_t mDequeueError;
        Mutex mMutex;
    };

    sp<FrameWaiter> fw(new FrameWaiter);
    mST->setFrameAvailableListener(fw);
    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, mST->setBufferCountServer(2));

    sp<Thread> pt(new ProducerThread(mANW));
    pt->run();

    fw->waitForFrame();
    fw->waitForFrame();

    // Sleep for 100ms to allow the producer thread's dequeueBuffer call to
    // block waiting for a buffer to become available.
    usleep(100000);

    mST->abandon();

    pt->requestExitAndWait();
    ASSERT_EQ(NO_INIT,
            reinterpret_cast<ProducerThread*>(pt.get())->getDequeueError());
}

/*
 * This test is for testing GL -> GL texture streaming via SurfaceTexture.  It
 * contains functionality to create a producer thread that will perform GL
 * rendering to an ANativeWindow that feeds frames to a SurfaceTexture.
 * Additionally it supports interlocking the producer and consumer threads so
 * that a specific sequence of calls can be deterministically created by the
 * test.
 *
 * The intended usage is as follows:
 *
 * TEST_F(...) {
 *     class PT : public ProducerThread {
 *         virtual void render() {
 *             ...
 *             swapBuffers();
 *         }
 *     };
 *
 *     runProducerThread(new PT());
 *
 *     // The order of these calls will vary from test to test and may include
 *     // multiple frames and additional operations (e.g. GL rendering from the
 *     // texture).
 *     fc->waitForFrame();
 *     mST->updateTexImage();
 *     fc->finishFrame();
 * }
 *
 */
class SurfaceTextureGLToGLTest : public SurfaceTextureGLTest {
protected:

    // ProducerThread is an abstract base class to simplify the creation of
    // OpenGL ES frame producer threads.
    class ProducerThread : public Thread {
    public:
        virtual ~ProducerThread() {
        }

        void setEglObjects(EGLDisplay producerEglDisplay,
                EGLSurface producerEglSurface,
                EGLContext producerEglContext) {
            mProducerEglDisplay = producerEglDisplay;
            mProducerEglSurface = producerEglSurface;
            mProducerEglContext = producerEglContext;
        }

        virtual bool threadLoop() {
            eglMakeCurrent(mProducerEglDisplay, mProducerEglSurface,
                    mProducerEglSurface, mProducerEglContext);
            render();
            eglMakeCurrent(mProducerEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
                    EGL_NO_CONTEXT);
            return false;
        }

    protected:
        virtual void render() = 0;

        void swapBuffers() {
            eglSwapBuffers(mProducerEglDisplay, mProducerEglSurface);
        }

        EGLDisplay mProducerEglDisplay;
        EGLSurface mProducerEglSurface;
        EGLContext mProducerEglContext;
    };

    // FrameCondition is a utility class for interlocking between the producer
    // and consumer threads.  The FrameCondition object should be created and
    // destroyed in the consumer thread only.  The consumer thread should set
    // the FrameCondition as the FrameAvailableListener of the SurfaceTexture,
    // and should call both waitForFrame and finishFrame once for each expected
    // frame.
    //
    // This interlocking relies on the fact that onFrameAvailable gets called
    // synchronously from SurfaceTexture::queueBuffer.
    class FrameCondition : public SurfaceTexture::FrameAvailableListener {
    public:
        FrameCondition():
                mFrameAvailable(false),
                mFrameFinished(false) {
        }

        // waitForFrame waits for the next frame to arrive.  This should be
        // called from the consumer thread once for every frame expected by the
        // test.
        void waitForFrame() {
            Mutex::Autolock lock(mMutex);
            LOGV("+waitForFrame");
            while (!mFrameAvailable) {
                mFrameAvailableCondition.wait(mMutex);
            }
            mFrameAvailable = false;
            LOGV("-waitForFrame");
        }

        // Allow the producer to return from its swapBuffers call and continue
        // on to produce the next frame.  This should be called by the consumer
        // thread once for every frame expected by the test.
        void finishFrame() {
            Mutex::Autolock lock(mMutex);
            LOGV("+finishFrame");
            mFrameFinished = true;
            mFrameFinishCondition.signal();
            LOGV("-finishFrame");
        }

        // This should be called by SurfaceTexture on the producer thread.
        virtual void onFrameAvailable() {
            Mutex::Autolock lock(mMutex);
            LOGV("+onFrameAvailable");
            mFrameAvailable = true;
            mFrameAvailableCondition.signal();
            while (!mFrameFinished) {
                mFrameFinishCondition.wait(mMutex);
            }
            mFrameFinished = false;
            LOGV("-onFrameAvailable");
        }

    protected:
        bool mFrameAvailable;
        bool mFrameFinished;

        Mutex mMutex;
        Condition mFrameAvailableCondition;
        Condition mFrameFinishCondition;
    };

    SurfaceTextureGLToGLTest():
            mProducerEglSurface(EGL_NO_SURFACE),
            mProducerEglContext(EGL_NO_CONTEXT) {
    }

    virtual void SetUp() {
        SurfaceTextureGLTest::SetUp();

        EGLConfig myConfig = {0};
        EGLint numConfigs = 0;
        EXPECT_TRUE(eglChooseConfig(mEglDisplay, getConfigAttribs(), &myConfig,
                1, &numConfigs));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        mProducerEglSurface = eglCreateWindowSurface(mEglDisplay, myConfig,
                mANW.get(), NULL);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_SURFACE, mProducerEglSurface);

        mProducerEglContext = eglCreateContext(mEglDisplay, myConfig,
                EGL_NO_CONTEXT, getContextAttribs());
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_CONTEXT, mProducerEglContext);

        mFC = new FrameCondition();
        mST->setFrameAvailableListener(mFC);
    }

    virtual void TearDown() {
        if (mProducerThread != NULL) {
            mProducerThread->requestExitAndWait();
        }
        if (mProducerEglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(mEglDisplay, mProducerEglContext);
        }
        if (mProducerEglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(mEglDisplay, mProducerEglSurface);
        }
        mProducerThread.clear();
        mFC.clear();
        SurfaceTextureGLTest::TearDown();
    }

    void runProducerThread(const sp<ProducerThread> producerThread) {
        ASSERT_TRUE(mProducerThread == NULL);
        mProducerThread = producerThread;
        producerThread->setEglObjects(mEglDisplay, mProducerEglSurface,
                mProducerEglContext);
        producerThread->run();
    }

    EGLSurface mProducerEglSurface;
    EGLContext mProducerEglContext;
    sp<ProducerThread> mProducerThread;
    sp<FrameCondition> mFC;
};

TEST_F(SurfaceTextureGLToGLTest, UpdateTexImageBeforeFrameFinishedCompletes) {
    class PT : public ProducerThread {
        virtual void render() {
            glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            swapBuffers();
        }
    };

    runProducerThread(new PT());

    mFC->waitForFrame();
    mST->updateTexImage();
    mFC->finishFrame();

    // TODO: Add frame verification once RGB TEX_EXTERNAL_OES is supported!
}

TEST_F(SurfaceTextureGLToGLTest, UpdateTexImageAfterFrameFinishedCompletes) {
    class PT : public ProducerThread {
        virtual void render() {
            glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            swapBuffers();
        }
    };

    runProducerThread(new PT());

    mFC->waitForFrame();
    mFC->finishFrame();
    mST->updateTexImage();

    // TODO: Add frame verification once RGB TEX_EXTERNAL_OES is supported!
}

TEST_F(SurfaceTextureGLToGLTest, RepeatedUpdateTexImageBeforeFrameFinishedCompletes) {
    enum { NUM_ITERATIONS = 1024 };

    class PT : public ProducerThread {
        virtual void render() {
            for (int i = 0; i < NUM_ITERATIONS; i++) {
                glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                LOGV("+swapBuffers");
                swapBuffers();
                LOGV("-swapBuffers");
            }
        }
    };

    runProducerThread(new PT());

    for (int i = 0; i < NUM_ITERATIONS; i++) {
        mFC->waitForFrame();
        LOGV("+updateTexImage");
        mST->updateTexImage();
        LOGV("-updateTexImage");
        mFC->finishFrame();

        // TODO: Add frame verification once RGB TEX_EXTERNAL_OES is supported!
    }
}

TEST_F(SurfaceTextureGLToGLTest, RepeatedUpdateTexImageAfterFrameFinishedCompletes) {
    enum { NUM_ITERATIONS = 1024 };

    class PT : public ProducerThread {
        virtual void render() {
            for (int i = 0; i < NUM_ITERATIONS; i++) {
                glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                LOGV("+swapBuffers");
                swapBuffers();
                LOGV("-swapBuffers");
            }
        }
    };

    runProducerThread(new PT());

    for (int i = 0; i < NUM_ITERATIONS; i++) {
        mFC->waitForFrame();
        mFC->finishFrame();
        LOGV("+updateTexImage");
        mST->updateTexImage();
        LOGV("-updateTexImage");

        // TODO: Add frame verification once RGB TEX_EXTERNAL_OES is supported!
    }
}

// XXX: This test is disabled because it is currently hanging on some devices.
TEST_F(SurfaceTextureGLToGLTest, DISABLED_RepeatedSwapBuffersWhileDequeueStalledCompletes) {
    enum { NUM_ITERATIONS = 64 };

    class PT : public ProducerThread {
        virtual void render() {
            for (int i = 0; i < NUM_ITERATIONS; i++) {
                glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
                glClear(GL_COLOR_BUFFER_BIT);
                LOGV("+swapBuffers");
                swapBuffers();
                LOGV("-swapBuffers");
            }
        }
    };

    ASSERT_EQ(OK, mST->setSynchronousMode(true));
    ASSERT_EQ(OK, mST->setBufferCountServer(2));

    runProducerThread(new PT());

    // Allow three frames to be rendered and queued before starting the
    // rendering in this thread.  For the latter two frames we don't call
    // updateTexImage so the next dequeue from the producer thread will block
    // waiting for a frame to become available.
    mFC->waitForFrame();
    mFC->finishFrame();

    // We must call updateTexImage to consume the first frame so that the
    // SurfaceTexture is able to reduce the buffer count to 2.  This is because
    // the GL driver may dequeue a buffer when the EGLSurface is created, and
    // that happens before we call setBufferCountServer.  It's possible that the
    // driver does not dequeue a buffer at EGLSurface creation time, so we
    // cannot rely on this to cause the second dequeueBuffer call to block.
    mST->updateTexImage();

    mFC->waitForFrame();
    mFC->finishFrame();
    mFC->waitForFrame();
    mFC->finishFrame();

    // Sleep for 100ms to allow the producer thread's dequeueBuffer call to
    // block waiting for a buffer to become available.
    usleep(100000);

    // Render and present a number of images.  This thread should not be blocked
    // by the fact that the producer thread is blocking in dequeue.
    for (int i = 0; i < NUM_ITERATIONS; i++) {
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(mEglDisplay, mEglSurface);
    }

    // Consume the two pending buffers to unblock the producer thread.
    mST->updateTexImage();
    mST->updateTexImage();

    // Consume the remaining buffers from the producer thread.
    for (int i = 0; i < NUM_ITERATIONS-3; i++) {
        mFC->waitForFrame();
        mFC->finishFrame();
        LOGV("+updateTexImage");
        mST->updateTexImage();
        LOGV("-updateTexImage");
    }
}

TEST_F(SurfaceTextureGLTest, EglDestroySurfaceUnrefsBuffers) {
    EGLSurface stcEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
            mANW.get(), NULL);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_SURFACE, stcEglSurface);

    sp<GraphicBuffer> buffers[3];

    for (int i = 0; i < 3; i++) {
        // Produce a frame
        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, stcEglSurface, stcEglSurface,
                mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(mEglDisplay, stcEglSurface);

        // Consume a frame
        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
                mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        mST->updateTexImage();
        buffers[i] = mST->getCurrentBuffer();
    }

    // Destroy the GL texture object to release its ref on buffers[2].
    GLuint texID = TEX_ID;
    glDeleteTextures(1, &texID);

    // Destroy the EGLSurface
    EXPECT_TRUE(eglDestroySurface(mEglDisplay, stcEglSurface));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    // Release the ref that the SurfaceTexture has on buffers[2].
    mST->abandon();

    EXPECT_EQ(1, buffers[0]->getStrongCount());
    EXPECT_EQ(1, buffers[1]->getStrongCount());
    EXPECT_EQ(1, buffers[2]->getStrongCount());
}

TEST_F(SurfaceTextureGLTest, EglDestroySurfaceAfterAbandonUnrefsBuffers) {
    EGLSurface stcEglSurface = eglCreateWindowSurface(mEglDisplay, mGlConfig,
            mANW.get(), NULL);
    ASSERT_EQ(EGL_SUCCESS, eglGetError());
    ASSERT_NE(EGL_NO_SURFACE, stcEglSurface);

    sp<GraphicBuffer> buffers[3];

    for (int i = 0; i < 3; i++) {
        // Produce a frame
        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, stcEglSurface, stcEglSurface,
                mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        glClear(GL_COLOR_BUFFER_BIT);
        EXPECT_TRUE(eglSwapBuffers(mEglDisplay, stcEglSurface));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());

        // Consume a frame
        EXPECT_TRUE(eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface,
                mEglContext));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_EQ(NO_ERROR, mST->updateTexImage());
        buffers[i] = mST->getCurrentBuffer();
    }

    // Abandon the SurfaceTexture, releasing the ref that the SurfaceTexture has
    // on buffers[2].
    mST->abandon();

    // Destroy the GL texture object to release its ref on buffers[2].
    GLuint texID = TEX_ID;
    glDeleteTextures(1, &texID);

    // Destroy the EGLSurface.
    EXPECT_TRUE(eglDestroySurface(mEglDisplay, stcEglSurface));
    ASSERT_EQ(EGL_SUCCESS, eglGetError());

    EXPECT_EQ(1, buffers[0]->getStrongCount());
    EXPECT_EQ(1, buffers[1]->getStrongCount());
    EXPECT_EQ(1, buffers[2]->getStrongCount());
}

TEST_F(SurfaceTextureGLTest, InvalidWidthOrHeightFails) {
    int texHeight = 16;
    ANativeWindowBuffer* anb;

    GLint maxTextureSize;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);

    // make sure it works with small textures
    mST->setDefaultBufferSize(16, texHeight);
    EXPECT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    EXPECT_EQ(16, anb->width);
    EXPECT_EQ(texHeight, anb->height);
    EXPECT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), anb));
    EXPECT_EQ(NO_ERROR, mST->updateTexImage());

    // make sure it works with GL_MAX_TEXTURE_SIZE
    mST->setDefaultBufferSize(maxTextureSize, texHeight);
    EXPECT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    EXPECT_EQ(maxTextureSize, anb->width);
    EXPECT_EQ(texHeight, anb->height);
    EXPECT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), anb));
    EXPECT_EQ(NO_ERROR, mST->updateTexImage());

    // make sure it fails with GL_MAX_TEXTURE_SIZE+1
    mST->setDefaultBufferSize(maxTextureSize+1, texHeight);
    EXPECT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    EXPECT_EQ(maxTextureSize+1, anb->width);
    EXPECT_EQ(texHeight, anb->height);
    EXPECT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), anb));
    ASSERT_NE(NO_ERROR, mST->updateTexImage());
}

} // namespace android
