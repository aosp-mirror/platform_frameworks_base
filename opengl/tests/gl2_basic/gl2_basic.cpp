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
     const char *v = (const char *)glGetString(s);
     if (v)
         printf("GL %s = %s\n", name, v);
     else
         printf("GL %s = (null)\n", name);
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

     dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     eglInitialize(dpy, &majorVersion, &minorVersion);
     EGLUtils::selectConfigForNativeWindow(dpy, s_configAttribs, window, &config);
     surface = eglCreateWindowSurface(dpy, config, window, NULL);

    EGLint gl2_0Attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};

     context = eglCreateContext(dpy, config, NULL, gl2_0Attribs);
     eglMakeCurrent(dpy, surface, surface, context);
     eglQuerySurface(dpy, surface, EGL_WIDTH, &w);
     eglQuerySurface(dpy, surface, EGL_HEIGHT, &h);
     GLint dim = w<h ? w : h;

     printGLString("Version", GL_VERSION);
     printGLString("Vendor", GL_VENDOR);
     printGLString("Renderer", GL_RENDERER);
     printGLString("Extensions", GL_EXTENSIONS);

     return 0;
}
