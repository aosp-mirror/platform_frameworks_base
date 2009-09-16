/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <stdlib.h>
#include <stdio.h>
#include <time.h>
#include <sched.h>
#include <sys/resource.h>

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/Timers.h>

#include <ui/FramebufferNativeWindow.h>
#include <ui/EGLUtils.h>

using namespace android;

static void printGLString(const char *name, GLenum s)
{
     fprintf(stderr, "printGLString %s, %d\n", name, s);
     const char *v = (const char *)glGetString(s);
     int error = glGetError();
     fprintf(stderr, "glGetError() = %d, result of glGetString = %x\n", error,
         (unsigned int)v);
     if ((v < (const char*) 0) || (v > (const char*) 0x1000))
         fprintf(stderr, "GL %s = %s\n", name, v);
     else
         fprintf(stderr, "GL %s = (null)\n", name);
}

static const char* eglErrorToString[] = {
    "EGL_SUCCESS",      // 0x3000 12288
    "EGL_NOT_INITIALIZED",
    "EGL_BAD_ACCESS",   // 0x3002 12290
    "EGL_BAD_ALLOC",
    "EGL_BAD_ATTRIBUTE",
    "EGL_BAD_CONFIG",
    "EGL_BAD_CONTEXT",  // 0x3006 12294
    "EGL_BAD_CURRENT_SURFACE",
    "EGL_BAD_DISPLAY",
    "EGL_BAD_MATCH",
    "EGL_BAD_NATIVE_PIXMAP",
    "EGL_BAD_NATIVE_WINDOW",
    "EGL_BAD_PARAMETER",  // 0x300c 12300
    "EGL_BAD_SURFACE"
};

static void checkEglError(const char* op) {
    for(EGLint error = eglGetError();
		error != EGL_SUCCESS;
	error = eglGetError()) {
        const char* errorString = "unknown";
        if (error >= EGL_SUCCESS && error <= EGL_BAD_SURFACE) {
            errorString = eglErrorToString[error - EGL_SUCCESS];
        }
        fprintf(stderr, "%s() returned eglError %s (0x%x)\n", op,
            errorString, error);
    }
}

int main(int argc, char** argv)
{
    EGLint s_configAttribs[] = {
         EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
         EGL_RED_SIZE,       5,
         EGL_GREEN_SIZE,     6,
         EGL_BLUE_SIZE,      5,
         EGL_NONE
     };

     EGLint numConfigs = -1;
     EGLint majorVersion;
     EGLint minorVersion;
     EGLConfig config;
     EGLContext context;
     EGLSurface surface;
     EGLint w, h;

     EGLDisplay dpy;

     EGLNativeWindowType window = 0;
     window = android_createDisplaySurface();

     checkEglError("<init>");
     dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     checkEglError("eglGetDisplay");
     eglInitialize(dpy, &majorVersion, &minorVersion);
     checkEglError("eglInitialize");
     fprintf(stderr, "EGL version %d.%d\n", majorVersion, minorVersion);
     EGLUtils::selectConfigForNativeWindow(dpy, s_configAttribs, window, &config);
     fprintf(stderr, "Chosen config: 0x%08x\n", (unsigned long) config);

     checkEglError("EGLUtils::selectConfigForNativeWindow");
     surface = eglCreateWindowSurface(dpy, config, window, NULL);
     checkEglError("eglCreateWindowSurface");

     EGLint gl2_0Attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};

     context = eglCreateContext(dpy, config, NULL, gl2_0Attribs);
     checkEglError("eglCreateContext");
     eglMakeCurrent(dpy, surface, surface, context);
     checkEglError("eglMakeCurrent");
     eglQuerySurface(dpy, surface, EGL_WIDTH, &w);
     checkEglError("eglQuerySurface");
     eglQuerySurface(dpy, surface, EGL_HEIGHT, &h);
     checkEglError("eglQuerySurface");
     GLint dim = w<h ? w : h;

     fprintf(stderr, "Window dimensions: %d x %d\n", w, h);

     printGLString("Version", GL_VERSION);
     printGLString("Vendor", GL_VENDOR);
     printGLString("Renderer", GL_RENDERER);
     printGLString("Extensions", GL_EXTENSIONS);

     return 0;
}
