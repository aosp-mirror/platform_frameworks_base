/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include <EGL/eglext.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static EGLDisplay gDisplay = (EGLDisplay) 1;

typedef struct {
    EGLSurface surface;
    EGLContext context;
} ThreadState;

static pthread_key_t ThreadStateKey;
static pthread_once_t ThreadStateSetupOnce = PTHREAD_ONCE_INIT;

static void destroyThreadState(void* state) {
    free(state);
}

static void makeThreadState() {
    pthread_key_create(&ThreadStateKey, destroyThreadState);
}

ThreadState* getThreadState() {
    ThreadState* ptr;
    pthread_once(&ThreadStateSetupOnce, makeThreadState);
    if ((ptr = (ThreadState*) pthread_getspecific(ThreadStateKey)) == NULL) {
        ptr = (ThreadState*) calloc(1, sizeof(ThreadState));
        ptr->context = EGL_NO_CONTEXT;
        ptr->surface = EGL_NO_SURFACE;
        pthread_setspecific(ThreadStateKey, ptr);
    }
    return ptr;
}

EGLint eglGetError(void) {
    return EGL_SUCCESS;
}

EGLDisplay eglGetDisplay(EGLNativeDisplayType display_id) {
    return gDisplay;
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor) {
    return EGL_TRUE;
}

EGLBoolean eglTerminate(EGLDisplay dpy) {
    return EGL_TRUE;
}

const char * eglQueryString(EGLDisplay dpy, EGLint name) {
    return "";
}

EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list,
               EGLConfig *configs, EGLint config_size,
               EGLint *num_config) {
    memset(configs, 9, sizeof(EGLConfig) * config_size);
    *num_config = config_size;
    return EGL_TRUE;
}

EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                  EGLNativeWindowType win,
                  const EGLint *attrib_list) {
    return (EGLSurface) malloc(sizeof(void*));
}

EGLSurface eglCreatePbufferSurface(EGLDisplay dpy, EGLConfig config,
                   const EGLint *attrib_list) {
    return (EGLSurface) malloc(sizeof(void*));
}

EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    free(surface);
    return EGL_TRUE;
}

EGLBoolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface,
               EGLint attribute, EGLint *value) {
    *value = 1000;
    return EGL_TRUE;
}

EGLBoolean eglReleaseThread(void) {
    return EGL_TRUE;
}

EGLBoolean eglSurfaceAttrib(EGLDisplay dpy, EGLSurface surface,
                EGLint attribute, EGLint value) {
    return EGL_TRUE;
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval) {
    return EGL_TRUE;
}

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                EGLContext share_context,
                const EGLint *attrib_list) {
    return (EGLContext) malloc(sizeof(void*));
}
EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx) {
    free(ctx);
    return EGL_TRUE;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw,
              EGLSurface read, EGLContext ctx) {
    ThreadState* state = getThreadState();
    state->surface = draw;
    state->context = ctx;
    return EGL_TRUE;
}

EGLContext eglGetCurrentContext(void) {
    return getThreadState()->context;
}

EGLSurface eglGetCurrentSurface(EGLint readdraw) {
    return getThreadState()->surface;
}

EGLDisplay eglGetCurrentDisplay(void) {
    return gDisplay;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    return EGL_TRUE;
}

EGLImageKHR eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint *attrib_list) {
    return (EGLImageKHR) malloc(sizeof(EGLImageKHR));
}

EGLBoolean eglDestroyImageKHR(EGLDisplay dpy, EGLImageKHR image) {
    free(image);
    return EGL_TRUE;
}

void eglBeginFrame(EGLDisplay dpy, EGLSurface surface) {}
