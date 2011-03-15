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
#include <gui/SurfaceTexture.h>
#include <gui/SurfaceTextureClient.h>
#include <ui/GraphicBuffer.h>
#include <utils/String8.h>

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
        EGLBoolean returnValue;

        mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_DISPLAY, mEglDisplay);

        EGLint majorVersion;
        EGLint minorVersion;
        EXPECT_TRUE(eglInitialize(mEglDisplay, &majorVersion, &minorVersion));
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        RecordProperty("EglVersionMajor", majorVersion);
        RecordProperty("EglVersionMajor", minorVersion);

        EGLConfig myConfig = {0};
        EGLint numConfigs = 0;
        EXPECT_TRUE(eglChooseConfig(mEglDisplay, getConfigAttribs(), &myConfig,
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

            mSurfaceControl = mComposerClient->createSurface(getpid(),
                    String8("Test Surface"), 0,
                    getSurfaceWidth(), getSurfaceHeight(),
                    PIXEL_FORMAT_RGB_888, 0);

            ASSERT_TRUE(mSurfaceControl != NULL);
            ASSERT_TRUE(mSurfaceControl->isValid());

            ASSERT_EQ(NO_ERROR, mComposerClient->openTransaction());
            ASSERT_EQ(NO_ERROR, mSurfaceControl->setLayer(30000));
            ASSERT_EQ(NO_ERROR, mSurfaceControl->show());
            ASSERT_EQ(NO_ERROR, mComposerClient->closeTransaction());

            sp<ANativeWindow> window = mSurfaceControl->getSurface();
            mEglSurface = eglCreateWindowSurface(mEglDisplay, myConfig,
                    window.get(), NULL);
        } else {
            EGLint pbufferAttribs[] = {
                EGL_WIDTH, getSurfaceWidth(),
                EGL_HEIGHT, getSurfaceHeight(),
                EGL_NONE };

            mEglSurface = eglCreatePbufferSurface(mEglDisplay, myConfig,
                    pbufferAttribs);
        }
        ASSERT_EQ(EGL_SUCCESS, eglGetError());
        ASSERT_NE(EGL_NO_SURFACE, mEglSurface);

        mEglContext = eglCreateContext(mEglDisplay, myConfig, EGL_NO_CONTEXT,
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
        return 64;
    }

    virtual EGLint getSurfaceHeight() {
        return 64;
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

    ::testing::AssertionResult checkPixel(int x, int y, int r,
            int g, int b, int a) {
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
        if (r >= 0 && GLubyte(r) != pixel[0]) {
            msg += String8::format("r(%d isn't %d)", pixel[0], r);
        }
        if (g >= 0 && GLubyte(g) != pixel[1]) {
            if (!msg.isEmpty()) {
                msg += " ";
            }
            msg += String8::format("g(%d isn't %d)", pixel[1], g);
        }
        if (b >= 0 && GLubyte(b) != pixel[2]) {
            if (!msg.isEmpty()) {
                msg += " ";
            }
            msg += String8::format("b(%d isn't %d)", pixel[2], b);
        }
        if (a >= 0 && GLubyte(a) != pixel[3]) {
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
};

// XXX: Code above this point should live elsewhere

class SurfaceTextureGLTest : public GLTest {
protected:
    static const GLint TEX_ID = 123;

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

        GLfloat texMatrix[16];
        mST->getTransformMatrix(texMatrix);
        glUniformMatrix4fv(mTexMatrixHandle, 1, GL_FALSE, texMatrix);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        ASSERT_EQ(GLenum(GL_NO_ERROR), glGetError());
    }

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

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BufferNpot) {
    const int yuvTexWidth = 64;
    const int yuvTexHeight = 66;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            yuvTexWidth, yuvTexHeight, HAL_PIXEL_FORMAT_YV12));
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
    fillYV12Buffer(img, yuvTexWidth, yuvTexHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(63,  0,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel(63, 63,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel( 0, 63, 255, 127, 255, 255));

    EXPECT_TRUE(checkPixel(22, 44, 247,  70, 255, 255));
    EXPECT_TRUE(checkPixel(45, 52, 209,  32, 235, 255));
    EXPECT_TRUE(checkPixel(52, 51, 100, 255,  73, 255));
    EXPECT_TRUE(checkPixel( 7, 31, 155,   0, 118, 255));
    EXPECT_TRUE(checkPixel(31,  9, 148,  71, 110, 255));
    EXPECT_TRUE(checkPixel(29, 35, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(36, 22, 155,  29,   0, 255));
}

// XXX: This test is disabled because it it currently broken on all devices to
// which I have access.  Some of the checkPixel calls are not correct because
// I just copied them from the npot test above and haven't bothered to figure
// out the correct values.
TEST_F(SurfaceTextureGLTest, DISABLED_TexturingFromCpuFilledYV12BufferPow2) {
    const int yuvTexWidth = 64;
    const int yuvTexHeight = 64;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            yuvTexWidth, yuvTexHeight, HAL_PIXEL_FORMAT_YV12));
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
    fillYV12Buffer(img, yuvTexWidth, yuvTexHeight, buf->getStride());
    buf->unlock();
    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

    mST->updateTexImage();

    glClearColor(0.2, 0.2, 0.2, 0.2);
    glClear(GL_COLOR_BUFFER_BIT);

    drawTexture();

    EXPECT_TRUE(checkPixel( 0,  0, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(63,  0,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel(63, 63,   0, 133,   0, 255));
    EXPECT_TRUE(checkPixel( 0, 63, 255, 127, 255, 255));

    EXPECT_TRUE(checkPixel(22, 19, 247,  70, 255, 255));
    EXPECT_TRUE(checkPixel(45, 11, 209,  32, 235, 255));
    EXPECT_TRUE(checkPixel(52, 12, 100, 255,  73, 255));
    EXPECT_TRUE(checkPixel( 7, 32, 155,   0, 118, 255));
    EXPECT_TRUE(checkPixel(31, 54, 148,  71, 110, 255));
    EXPECT_TRUE(checkPixel(29, 28, 255, 127, 255, 255));
    EXPECT_TRUE(checkPixel(36, 41, 155,  29,   0, 255));
}

TEST_F(SurfaceTextureGLTest, TexturingFromCpuFilledYV12BufferWithCrop) {
    const int yuvTexWidth = 64;
    const int yuvTexHeight = 66;

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            yuvTexWidth, yuvTexHeight, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    android_native_rect_t crops[] = {
        {4, 6, 22, 36},
        {0, 6, 22, 36},
        {4, 0, 22, 36},
        {4, 6, yuvTexWidth, 36},
        {4, 6, 22, yuvTexHeight},
    };

    for (int i = 0; i < 5; i++) {
        const android_native_rect_t& crop(crops[i]);
        SCOPED_TRACE(String8::format("rect{ l: %d t: %d r: %d b: %d }", crop.left,
                crop.top, crop.right, crop.bottom).string());

        ASSERT_EQ(NO_ERROR, native_window_set_crop(mANW.get(), &crop));

        android_native_buffer_t* anb;
        ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
        ASSERT_TRUE(anb != NULL);

        sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
        ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

        uint8_t* img = NULL;
        buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
        fillYV12BufferRect(img, yuvTexWidth, yuvTexHeight, buf->getStride(), crop);
        buf->unlock();
        ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));

        mST->updateTexImage();

        glClearColor(0.2, 0.2, 0.2, 0.2);
        glClear(GL_COLOR_BUFFER_BIT);

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

}
