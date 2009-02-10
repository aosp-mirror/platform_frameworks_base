/* San Angeles Observation OpenGL ES version example
 * Copyright 2004-2005 Jetro Lauha
 * All rights reserved.
 * Web: http://iki.fi/jetro/
 *
 * This source is free software; you can redistribute it and/or
 * modify it under the terms of EITHER:
 *   (1) The GNU Lesser General Public License as published by the Free
 *       Software Foundation; either version 2.1 of the License, or (at
 *       your option) any later version. The text of the GNU Lesser
 *       General Public License is included with this source in the
 *       file LICENSE-LGPL.txt.
 *   (2) The BSD-style license that is included with this source in
 *       the file LICENSE-BSD.txt.
 *
 * This source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the files
 * LICENSE-LGPL.txt and LICENSE-BSD.txt for more details.
 *
 * $Id: app-linux.c,v 1.4 2005/02/08 18:42:48 tonic Exp $
 * $Revision: 1.4 $
 *
 * Parts of this source file is based on test/example code from
 * GLESonGL implementation by David Blythe. Here is copy of the
 * license notice from that source:
 *
 * Copyright (C) 2003  David Blythe   All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * DAVID BLYTHE BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

#include <stdlib.h>
#include <stdio.h>
#include <sys/time.h>

#include <EGL/egl.h>
#include <GLES/gl.h>

#include "app.h"


int gAppAlive = 1;

static const char sAppName[] =
    "San Angeles Observation OpenGL ES version example (Linux)";

static int sWindowWidth = WINDOW_DEFAULT_WIDTH;
static int sWindowHeight = WINDOW_DEFAULT_HEIGHT;
static EGLDisplay sEglDisplay = EGL_NO_DISPLAY;
static EGLContext sEglContext = EGL_NO_CONTEXT;
static EGLSurface sEglSurface = EGL_NO_SURFACE;

const char *egl_strerror(unsigned err)
{
    switch(err){
    case EGL_SUCCESS: return "SUCCESS";
    case EGL_NOT_INITIALIZED: return "NOT INITIALIZED";
    case EGL_BAD_ACCESS: return "BAD ACCESS";
    case EGL_BAD_ALLOC: return "BAD ALLOC";
    case EGL_BAD_ATTRIBUTE: return "BAD_ATTRIBUTE";
    case EGL_BAD_CONFIG: return "BAD CONFIG";
    case EGL_BAD_CONTEXT: return "BAD CONTEXT";
    case EGL_BAD_CURRENT_SURFACE: return "BAD CURRENT SURFACE";
    case EGL_BAD_DISPLAY: return "BAD DISPLAY";
    case EGL_BAD_MATCH: return "BAD MATCH";
    case EGL_BAD_NATIVE_PIXMAP: return "BAD NATIVE PIXMAP";
    case EGL_BAD_NATIVE_WINDOW: return "BAD NATIVE WINDOW";
    case EGL_BAD_PARAMETER: return "BAD PARAMETER";
    case EGL_BAD_SURFACE: return "BAD_SURFACE";
//    case EGL_CONTEXT_LOST: return "CONTEXT LOST";
    default: return "UNKNOWN";
    }
}

void egl_error(const char *name)
{
    unsigned err = eglGetError();
    if(err != EGL_SUCCESS) {
        fprintf(stderr,"%s(): egl error 0x%x (%s)\n", 
                name, err, egl_strerror(err));
    }
}

static void checkGLErrors()
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR)
        fprintf(stderr, "GL Error: 0x%04x\n", (int)error);
}


static void checkEGLErrors()
{
    EGLint error = eglGetError();
    // GLESonGL seems to be returning 0 when there is no errors?
    if (error && error != EGL_SUCCESS)
        fprintf(stderr, "EGL Error: 0x%04x\n", (int)error);
}

static int initGraphics()
{
    EGLint s_configAttribs[] = {
         EGL_RED_SIZE,       5,
         EGL_GREEN_SIZE,     6,
         EGL_BLUE_SIZE,      5,
 #if 1
         EGL_DEPTH_SIZE,     16,
         EGL_STENCIL_SIZE,   0,
 #else
         EGL_ALPHA_SIZE,     EGL_DONT_CARE,
         EGL_DEPTH_SIZE,     EGL_DONT_CARE,
         EGL_STENCIL_SIZE,   EGL_DONT_CARE,
         EGL_SURFACE_TYPE,   EGL_DONT_CARE,
 #endif
         EGL_NONE
     };
     
     EGLint numConfigs = -1;
     EGLint majorVersion;
     EGLint minorVersion;
     EGLConfig config;
     EGLContext context;
     EGLSurface surface;
     
     EGLDisplay dpy;

     dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     egl_error("eglGetDisplay");
     fprintf(stderr,"dpy = 0x%08x\n", (unsigned) dpy);
     
     eglInitialize(dpy, &majorVersion, &minorVersion);
     egl_error("eglInitialize");

     eglGetConfigs(dpy, NULL, 0, &numConfigs);
     egl_error("eglGetConfigs");
     fprintf(stderr,"num configs %d\n", numConfigs);
     
     eglChooseConfig(dpy, s_configAttribs, &config, 1, &numConfigs);
     egl_error("eglChooseConfig");

     surface = eglCreateWindowSurface(dpy, config,
             android_createDisplaySurface(), NULL);
     egl_error("eglMapWindowSurface");

     fprintf(stderr,"surface = %p\n", surface);

     context = eglCreateContext(dpy, config, NULL, NULL);
     egl_error("eglCreateContext");
     fprintf(stderr,"context = %p\n", context);
     
     eglMakeCurrent(dpy, surface, surface, context);   
     egl_error("eglMakeCurrent");

     eglQuerySurface(dpy, surface, EGL_WIDTH, &sWindowWidth);
     eglQuerySurface(dpy, surface, EGL_HEIGHT, &sWindowHeight);

    sEglDisplay = dpy;
    sEglSurface = surface;
    sEglContext = context;

    return EGL_TRUE;
}


static void deinitGraphics()
{
    eglMakeCurrent(sEglDisplay, NULL, NULL, NULL);
    eglDestroyContext(sEglDisplay, sEglContext);
    eglDestroySurface(sEglDisplay, sEglSurface);
    eglTerminate(sEglDisplay);
}


int main(int argc, char *argv[])
{
    // not referenced:
    argc = argc;
    argv = argv;

    if (!initGraphics())
    {
        fprintf(stderr, "Graphics initialization failed.\n");
        return EXIT_FAILURE;
    }

    appInit();
    
    while (gAppAlive)
    {
        struct timeval timeNow;

        if (gAppAlive)
        {
            gettimeofday(&timeNow, NULL);
            appRender(timeNow.tv_sec * 1000 + timeNow.tv_usec / 1000,
                      sWindowWidth, sWindowHeight);
            checkGLErrors();
            eglSwapBuffers(sEglDisplay, sEglSurface);
            checkEGLErrors();
        }
    }

    appDeinit();
    deinitGraphics();

    return EXIT_SUCCESS;
}
