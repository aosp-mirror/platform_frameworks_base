/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "AssetAtlasService"

#include "jni.h"
#include "JNIHelp.h"

#include <android_view_GraphicBuffer.h>
#include <cutils/log.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <SkCanvas.h>
#include <SkBitmap.h>

namespace android {

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Defines how long to wait for the GPU when uploading the atlas
// This timeout is defined in nanoseconds (see EGL_KHR_fence_sync extension)
#define FENCE_TIMEOUT 2000000000

// ----------------------------------------------------------------------------
// JNI Helpers
// ----------------------------------------------------------------------------

static struct {
    jfieldID mFinalizer;
    jfieldID mNativeCanvas;
} gCanvasClassInfo;

static struct {
    jfieldID mNativeCanvas;
} gCanvasFinalizerClassInfo;

#define GET_INT(object, field) \
    env->GetIntField(object, field)

#define SET_INT(object, field, value) \
    env->SetIntField(object, field, value)

// ----------------------------------------------------------------------------
// Canvas management
// ----------------------------------------------------------------------------

static inline void swapCanvasPtr(JNIEnv* env, jobject canvasObj, SkCanvas* newCanvas) {
    jobject canvasFinalizerObj = env->GetObjectField(canvasObj, gCanvasClassInfo.mFinalizer);
    SkCanvas* previousCanvas = reinterpret_cast<SkCanvas*>(
            GET_INT(canvasObj, gCanvasClassInfo.mNativeCanvas));
    SET_INT(canvasObj, gCanvasClassInfo.mNativeCanvas, (int) newCanvas);
    SET_INT(canvasFinalizerObj, gCanvasFinalizerClassInfo.mNativeCanvas, (int) newCanvas);
    SkSafeUnref(previousCanvas);
}

static SkBitmap* com_android_server_AssetAtlasService_acquireCanvas(JNIEnv* env, jobject,
        jobject canvas, jint width, jint height) {

    SkBitmap* bitmap = new SkBitmap;
    bitmap->setConfig(SkBitmap::kARGB_8888_Config, width, height);
    bitmap->allocPixels();
    bitmap->eraseColor(0);

    SkCanvas* nativeCanvas = SkNEW_ARGS(SkCanvas, (*bitmap));
    swapCanvasPtr(env, canvas, nativeCanvas);

    return bitmap;
}

static void com_android_server_AssetAtlasService_releaseCanvas(JNIEnv* env, jobject,
        jobject canvas, SkBitmap* bitmap) {

    SkCanvas* nativeCanvas = SkNEW(SkCanvas);
    swapCanvasPtr(env, canvas, nativeCanvas);

    delete bitmap;
}

#define CLEANUP_GL_AND_RETURN(result) \
    if (fence != EGL_NO_SYNC_KHR) eglDestroySyncKHR(display, fence); \
    if (image) eglDestroyImageKHR(display, image); \
    if (texture) glDeleteTextures(1, &texture); \
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface); \
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context); \
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); \
    eglReleaseThread(); \
    eglTerminate(display); \
    return result;

static jboolean com_android_server_AssetAtlasService_upload(JNIEnv* env, jobject,
        jobject graphicBuffer, SkBitmap* bitmap) {

    // The goal of this method is to copy the bitmap into the GraphicBuffer
    // using the GPU to swizzle the texture content
    sp<GraphicBuffer> buffer(graphicBufferForJavaObject(env, graphicBuffer));

    if (buffer != NULL) {
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) return false;

        EGLint major;
        EGLint minor;
        if (!eglInitialize(display, &major, &minor)) {
            ALOGW("Could not initialize EGL");
            return false;
        }

        // We're going to use a 1x1 pbuffer surface later on
        // The configuration doesn't really matter for what we're trying to do
        EGLint configAttrs[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 0,
                EGL_DEPTH_SIZE, 0,
                EGL_STENCIL_SIZE, 0,
                EGL_NONE
        };
        EGLConfig configs[1];
        EGLint configCount;
        if (!eglChooseConfig(display, configAttrs, configs, 1, &configCount)) {
            ALOGW("Could not select EGL configuration");
            eglReleaseThread();
            eglTerminate(display);
            return false;
        }
        if (configCount <= 0) {
            ALOGW("Could not find EGL configuration");
            eglReleaseThread();
            eglTerminate(display);
            return false;
        }

        // These objects are initialized below but the default "null"
        // values are used to cleanup properly at any point in the
        // initialization sequence
        GLuint texture = 0;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        EGLSurface surface = EGL_NO_SURFACE;
        EGLSyncKHR fence = EGL_NO_SYNC_KHR;

        EGLint attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        EGLContext context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, attrs);
        if (context == EGL_NO_CONTEXT) {
            ALOGW("Could not create EGL context");
            CLEANUP_GL_AND_RETURN(false);
        }

        // Create the 1x1 pbuffer
        EGLint surfaceAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        surface = eglCreatePbufferSurface(display, configs[0], surfaceAttrs);
        if (surface == EGL_NO_SURFACE) {
            ALOGW("Could not create EGL surface");
            CLEANUP_GL_AND_RETURN(false);
        }

        if (!eglMakeCurrent(display, surface, surface, context)) {
            ALOGW("Could not change current EGL context");
            CLEANUP_GL_AND_RETURN(false);
        }

        // We use an EGLImage to access the content of the GraphicBuffer
        // The EGL image is later bound to a 2D texture
        EGLClientBuffer clientBuffer = (EGLClientBuffer) buffer->getNativeBuffer();
        EGLint imageAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        image = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttrs);
        if (image == EGL_NO_IMAGE_KHR) {
            ALOGW("Could not create EGL image");
            CLEANUP_GL_AND_RETURN(false);
        }

        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
        if (glGetError() != GL_NO_ERROR) {
            ALOGW("Could not create/bind texture");
            CLEANUP_GL_AND_RETURN(false);
        }

        // Upload the content of the bitmap in the GraphicBuffer
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap->width(), bitmap->height(),
                GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        if (glGetError() != GL_NO_ERROR) {
            ALOGW("Could not upload to texture");
            CLEANUP_GL_AND_RETURN(false);
        }

        // The fence is used to wait for the texture upload to finish
        // properly. We cannot rely on glFlush() and glFinish() as
        // some drivers completely ignore these API calls
        fence = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, NULL);
        if (fence == EGL_NO_SYNC_KHR) {
            ALOGW("Could not create sync fence %#x", eglGetError());
            CLEANUP_GL_AND_RETURN(false);
        }

        // The flag EGL_SYNC_FLUSH_COMMANDS_BIT_KHR will trigger a
        // pipeline flush (similar to what a glFlush() would do.)
        EGLint waitStatus = eglClientWaitSyncKHR(display, fence,
                EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, FENCE_TIMEOUT);
        if (waitStatus != EGL_CONDITION_SATISFIED_KHR) {
            ALOGW("Failed to wait for the fence %#x", eglGetError());
            CLEANUP_GL_AND_RETURN(false);
        }

        CLEANUP_GL_AND_RETURN(true);
    }

    return false;
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

const char* const kClassPathName = "com/android/server/AssetAtlasService";

static JNINativeMethod gMethods[] = {
    { "nAcquireAtlasCanvas", "(Landroid/graphics/Canvas;II)I",
            (void*) com_android_server_AssetAtlasService_acquireCanvas },
    { "nReleaseAtlasCanvas", "(Landroid/graphics/Canvas;I)V",
            (void*) com_android_server_AssetAtlasService_releaseCanvas },
    { "nUploadAtlas", "(Landroid/view/GraphicBuffer;I)Z",
            (void*) com_android_server_AssetAtlasService_upload },
};

int register_android_server_AssetAtlasService(JNIEnv* env) {
    jclass clazz;

    FIND_CLASS(clazz, "android/graphics/Canvas");
    GET_FIELD_ID(gCanvasClassInfo.mFinalizer, clazz, "mFinalizer",
            "Landroid/graphics/Canvas$CanvasFinalizer;");
    GET_FIELD_ID(gCanvasClassInfo.mNativeCanvas, clazz, "mNativeCanvas", "I");

    FIND_CLASS(clazz, "android/graphics/Canvas$CanvasFinalizer");
    GET_FIELD_ID(gCanvasFinalizerClassInfo.mNativeCanvas, clazz, "mNativeCanvas", "I");

    return jniRegisterNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
