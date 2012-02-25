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
#include "EGLUtils.h"

using namespace android;

int main(int argc, char** argv)
{
    EGLint configAttribs[] = {
         EGL_DEPTH_SIZE, 0,
         EGL_NONE
     };
     
     EGLint majorVersion;
     EGLint minorVersion;
     EGLContext context;
     EGLConfig config;
     EGLSurface surface;
     EGLint w, h;
     EGLDisplay dpy;

     EGLNativeWindowType window = android_createDisplaySurface();
     
     dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     eglInitialize(dpy, &majorVersion, &minorVersion);
          
     status_t err = EGLUtils::selectConfigForNativeWindow(
             dpy, configAttribs, window, &config);
     if (err) {
         fprintf(stderr, "couldn't find an EGLConfig matching the screen format\n");
         return 0;
     }

     surface = eglCreateWindowSurface(dpy, config, window, NULL);
     context = eglCreateContext(dpy, config, NULL, NULL);
     eglMakeCurrent(dpy, surface, surface, context);   
     eglQuerySurface(dpy, surface, EGL_WIDTH, &w);
     eglQuerySurface(dpy, surface, EGL_HEIGHT, &h);
     GLint dim = w<h ? w : h;

     glBindTexture(GL_TEXTURE_2D, 0);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
     glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
     glEnable(GL_TEXTURE_2D);
     glColor4f(1,1,1,1);
     glDisable(GL_DITHER);
     glShadeModel(GL_FLAT);

     long long now, t;
     int i;

     char* texels = (char*)malloc(512*512*2);
     memset(texels,0xFF,512*512*2);
     
     glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
             512, 512, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, texels);

     char* dst = (char*)malloc(320*480*2);
     memset(dst, 0, 320*480*2);
     printf("307200 bytes memcpy\n");
     for (i=0 ; i<4 ; i++) {
         now = systemTime();
         memcpy(dst, texels, 320*480*2);
         t = systemTime();
         printf("memcpy() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
     }
     free(dst);

     free(texels);

     setpriority(PRIO_PROCESS, 0, -20);
     
     printf("512x512 unmodified texture, 512x512 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 512, 512, -512 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 512, 512);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }
     
     printf("512x512 unmodified texture, 1x1 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 1, 1, -1 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 1, 1);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }
     
     printf("512x512 unmodified texture, 512x512 blit (x2):\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 512, 512, -512 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 512, 512);
         glDrawTexiOES(0, 0, 0, 512, 512);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }

     printf("512x512 unmodified texture, 1x1 blit (x2):\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 1, 1, -1 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 1, 1);
         glDrawTexiOES(0, 0, 0, 1, 1);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }

     
     printf("512x512 (1x1 texel MODIFIED texture), 512x512 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         uint16_t green = 0x7E0;
         GLint crop[4] = { 0, 512, 512, -512 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, &green);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 512, 512);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }


     int16_t texel = 0xF800;
     glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
             1, 1, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, &texel);

     printf("1x1 unmodified texture, 1x1 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 1, 1, -1 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 1, 1);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         eglSwapBuffers(dpy, surface);
     }

     printf("1x1 unmodified texture, 512x512 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         GLint crop[4] = { 0, 1, 1, -1 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 512, 512);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }

     printf("1x1 (1x1 texel MODIFIED texture), 512x512 blit:\n");
     glClear(GL_COLOR_BUFFER_BIT);
     for (i=0 ; i<4 ; i++) {
         uint16_t green = 0x7E0;
         GLint crop[4] = { 0, 1, 1, -1 };
         glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
         glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, &green);
         now = systemTime();
         glDrawTexiOES(0, 0, 0, 1, 1);
         glFinish();
         t = systemTime();
         printf("glFinish() time = %llu us\n", (t-now)/1000);
         fflush(stdout);
         eglSwapBuffers(dpy, surface);
     }

     return 0;
}
