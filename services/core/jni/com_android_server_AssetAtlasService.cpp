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
#include "android/graphics/GraphicsJNI.h"

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
    jmethodID setNativeBitmap;
} gCanvasClassInfo;

#define INVOKEV(object, method, ...) \
    env->CallVoidMethod(object, method, __VA_ARGS__)

// ----------------------------------------------------------------------------
// Canvas management
// ----------------------------------------------------------------------------

static jlong com_android_server_AssetAtlasService_acquireCanvas(JNIEnv* env, jobject,
        jobject canvas, jint width, jint height) {

    SkBitmap* bitmap = new SkBitmap;
    bitmap->allocN32Pixels(width, height);
    bitmap->eraseColor(0);
    INVOKEV(canvas, gCanvasClassInfo.setNativeBitmap, reinterpret_cast<jlong>(bitmap));

    return reinterpret_cast<jlong>(bitmap);
}

static void com_android_server_AssetAtlasService_releaseCanvas(JNIEnv* env, jobject,
        jobject canvas, jlong bitmapHandle) {

    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    INVOKEV(canvas, gCanvasClassInfo.setNativeBitmap, (jlong)0);

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
        jobject graphicBuffer, jlong bitmapHandle) {

    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    // The goal of this method is to copy the bitmap into the GraphicBuffer
    // using the GPU to swizzle the texture content
    sp<GraphicBuffer> buffer(graphicBufferForJavaObject(env, graphicBuffer));

    if (buffer != NULL) {
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) return JNI_FALSE;

        EGLint major;
        EGLint minor;
        if (!eglInitialize(display, &major, &minor)) {
            ALOGW("Could not initialize EGL");
            return JNI_FALSE;
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
            return JNI_FALSE;
        }
        if (configCount <= 0) {
            ALOGW("Could not find EGL configuration");
            eglReleaseThread();
            eglTerminate(display);
            return JNI_FALSE;
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
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        // Create the 1x1 pbuffer
        EGLint surfaceAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        surface = eglCreatePbufferSurface(display, configs[0], surfaceAttrs);
        if (surface == EGL_NO_SURFACE) {
            ALOGW("Could not create EGL surface");
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        if (!eglMakeCurrent(display, surface, surface, context)) {
            ALOGW("Could not change current EGL context");
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        // We use an EGLImage to access the content of the GraphicBuffer
        // The EGL image is later bound to a 2D texture
        EGLClientBuffer clientBuffer = (EGLClientBuffer) buffer->getNativeBuffer();
        EGLint imageAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
        image = eglCreateImageKHR(display, EGL_NO_CONTEXT,
                EGL_NATIVE_BUFFER_ANDROID, clientBuffer, imageAttrs);
        if (image == EGL_NO_IMAGE_KHR) {
            ALOGW("Could not create EGL image");
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
        if (glGetError() != GL_NO_ERROR) {
            ALOGW("Could not create/bind texture");
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        // Upload the content of the bitmap in the GraphicBuffer
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap->width(), bitmap->height(),
                GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        if (glGetError() != GL_NO_ERROR) {
            ALOGW("Could not upload to texture");
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        // The fence is used to wait for the texture upload to finish
        // properly. We cannot rely on glFlush() and glFinish() as
        // some drivers completely ignore these API calls
        fence = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, NULL);
        if (fence == EGL_NO_SYNC_KHR) {
            ALOGW("Could not create sync fence %#x", eglGetError());
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        // The flag EGL_SYNC_FLUSH_COMMANDS_BIT_KHR will trigger a
        // pipeline flush (similar to what a glFlush() would do.)
        EGLint waitStatus = eglClientWaitSyncKHR(display, fence,
                EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, FENCE_TIMEOUT);
        if (waitStatus != EGL_CONDITION_SATISFIED_KHR) {
            ALOGW("Failed to wait for the fence %#x", eglGetError());
            CLEANUP_GL_AND_RETURN(JNI_FALSE);
        }

        CLEANUP_GL_AND_RETURN(JNI_TRUE);
    }

    return JNI_FALSE;
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(!var, "Unable to find method " methodName);

const char* const kClassPathName = "com/android/server/AssetAtlasService";

static JNINativeMethod gMethods[] = {
    { "nAcquireAtlasCanvas", "(Landroid/graphics/Canvas;II)J",
            (void*) com_android_server_AssetAtlasService_acquireCanvas },
    { "nReleaseAtlasCanvas", "(Landroid/graphics/Canvas;J)V",
            (void*) com_android_server_AssetAtlasService_releaseCanvas },
    { "nUploadAtlas", "(Landroid/view/GraphicBuffer;J)Z",
            (void*) com_android_server_AssetAtlasService_upload },
};

int register_android_server_AssetAtlasService(JNIEnv* env) {
    jclass clazz;

    FIND_CLASS(clazz, "android/graphics/Canvas");
    GET_METHOD_ID(gCanvasClassInfo.setNativeBitmap, clazz, "setNativeBitmap", "(J)V");

    return jniRegisterNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
