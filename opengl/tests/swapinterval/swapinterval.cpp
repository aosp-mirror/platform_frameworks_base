/*
 **
 ** Copyright 2006, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing, software 
 ** distributed under the License is distributed on an "AS IS" BASIS, 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#include <stdlib.h>
#include <stdio.h>

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/StopWatch.h>
#include <ui/FramebufferNativeWindow.h>
#include "EGLUtils.h"

using namespace android;

int main(int argc, char** argv)
{
    EGLint configAttribs[] = {
            EGL_SURFACE_TYPE,   EGL_WINDOW_BIT,
            EGL_NONE
    };

    EGLint majorVersion;
    EGLint minorVersion;
    EGLContext context;
    EGLConfig config;
    EGLint numConfigs=0;
    EGLSurface surface;
    EGLint w, h;
    EGLDisplay dpy;

    
    EGLNativeWindowType window = android_createDisplaySurface();

    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, &majorVersion, &minorVersion);
    eglGetConfigs(dpy, NULL, 0, &numConfigs);
    printf("# configs = %d\n", numConfigs);

    status_t err = EGLUtils::selectConfigForNativeWindow(
            dpy, configAttribs, window, &config);
    if (err) {
        fprintf(stderr, "error: %s", EGLUtils::strerror(eglGetError()));
        eglTerminate(dpy);
        return 0;
    }

    EGLint r,g,b,a, vid;
    eglGetConfigAttrib(dpy, config, EGL_RED_SIZE,   &r);
    eglGetConfigAttrib(dpy, config, EGL_GREEN_SIZE, &g);
    eglGetConfigAttrib(dpy, config, EGL_BLUE_SIZE,  &b);
    eglGetConfigAttrib(dpy, config, EGL_ALPHA_SIZE, &a);
    eglGetConfigAttrib(dpy, config, EGL_NATIVE_VISUAL_ID, &vid);

    surface = eglCreateWindowSurface(dpy, config, window, NULL);
    if (surface == EGL_NO_SURFACE) {
        EGLint err = eglGetError();
        fprintf(stderr, "error: %s, config=%p, format = %d-%d-%d-%d, visual-id = %d\n",
                EGLUtils::strerror(err), config, r,g,b,a, vid);
        eglTerminate(dpy);
        return 0;
    } else {
        printf("config=%p, format = %d-%d-%d-%d, visual-id = %d\n",
                config, r,g,b,a, vid);
    }

    context = eglCreateContext(dpy, config, NULL, NULL);
    eglMakeCurrent(dpy, surface, surface, context);   
    eglQuerySurface(dpy, surface, EGL_WIDTH, &w);
    eglQuerySurface(dpy, surface, EGL_HEIGHT, &h);

    printf("w=%d, h=%d\n", w, h);

    glDisable(GL_DITHER);
    glEnable(GL_BLEND);

    glViewport(0, 0, w, h);
    glOrthof(0, w, 0, h, 0, 1);

    eglSwapInterval(dpy, 1);

    glClearColor(1,0,0,0);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(dpy, surface);


    int time = 10;
    printf("screen should flash red/green quickly for %d s...\n", time);

    int c = 0;
    nsecs_t start = systemTime();
    nsecs_t t;
    do {
        glClearColor(1,0,0,0);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(dpy, surface);
        glClearColor(0,1,0,0);
        glClear(GL_COLOR_BUFFER_BIT);
        eglSwapBuffers(dpy, surface);
        t = systemTime() - start;
        c += 2;
    } while (int(ns2s(t))<=time);

    double p =  (double(t) / c) / 1000000000.0;
    printf("refresh-rate is %f fps (%f ms)\n", 1.0f/p, p*1000.0);

    eglTerminate(dpy);

    return 0;
}
