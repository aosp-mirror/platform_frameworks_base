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

#define LOG_TAG "fillrate"

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
     
     printf("w=%d, h=%d\n", w, h);
     
     glBindTexture(GL_TEXTURE_2D, 0);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
     glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
     glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
     glDisable(GL_DITHER);
     glEnable(GL_BLEND);
     glEnable(GL_TEXTURE_2D);
     glColor4f(1,1,1,1);

     uint32_t* t32 = (uint32_t*)malloc(512*512*4); 
     for (int y=0 ; y<512 ; y++) {
         for (int x=0 ; x<512 ; x++) {
             int u = x-256;
             int v = y-256;
             if (u*u+v*v < 256*256) {
                 t32[x+y*512] = 0x10FFFFFF;
             } else {
                 t32[x+y*512] = 0x20FF0000;
             }
         }
     }

     const GLfloat vertices[4][2] = {
             { 0,  0 },
             { 0,  h },
             { w,  h },
             { w,  0 }
     };

     const GLfloat texCoords[4][2] = {
             { 0,  0 },
             { 0,  1 },
             { 1,  1 },
             { 1,  0 }
     };

     glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, t32);

     glViewport(0, 0, w, h);
     glMatrixMode(GL_PROJECTION);
     glLoadIdentity();
     glOrthof(0, w, 0, h, 0, 1);

     glEnableClientState(GL_VERTEX_ARRAY);
     glEnableClientState(GL_TEXTURE_COORD_ARRAY);
     glVertexPointer(2, GL_FLOAT, 0, vertices);
     glTexCoordPointer(2, GL_FLOAT, 0, texCoords);

     eglSwapInterval(dpy, 1);

     glClearColor(1,0,0,0);
     glClear(GL_COLOR_BUFFER_BIT);
     glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
     eglSwapBuffers(dpy, surface);
     

     nsecs_t times[32];

     for (int c=1 ; c<32 ; c++) {
         glClear(GL_COLOR_BUFFER_BIT);
         for (int i=0 ; i<c ; i++) {
             glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
         }
         eglSwapBuffers(dpy, surface);
     }


     //     for (int c=31 ; c>=1 ; c--) {
     int j=0;
     for (int c=1 ; c<32 ; c++) {
         glClear(GL_COLOR_BUFFER_BIT);
         nsecs_t now = systemTime();
         for (int i=0 ; i<c ; i++) {
             glDrawArrays(GL_TRIANGLE_FAN, 0, 4); 
         }
         eglSwapBuffers(dpy, surface);
         nsecs_t t = systemTime() - now;
         times[j++] = t;
     }

     for (int c=1, j=0 ; c<32 ; c++, j++) {
         nsecs_t t = times[j];
         printf("%lld\t%d\t%f\n", t, c, (double(t)/c)/1000000.0);
     }


       
     eglTerminate(dpy);
     
     return 0;
}
