#include <stdlib.h>
#include <stdio.h>

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <ui/FramebufferNativeWindow.h>
#include "EGLUtils.h"

using namespace android;

#define USE_DRAW_TEXTURE 1

int main(int argc, char** argv)
{
    if (argc!=2 && argc!=3) {
        printf("usage: %s <0-6> [pbuffer]\n", argv[0]);
        return 0;
    }
    
    const int test = atoi(argv[1]);
    int usePbuffer = argc==3 && !strcmp(argv[2], "pbuffer");
    EGLint s_configAttribs[] = {
         EGL_SURFACE_TYPE, EGL_PBUFFER_BIT|EGL_WINDOW_BIT,
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
     if (!usePbuffer) {
         window = android_createDisplaySurface();
     }
     
     dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     eglInitialize(dpy, &majorVersion, &minorVersion);
     if (!usePbuffer) {
         EGLUtils::selectConfigForNativeWindow(
                 dpy, s_configAttribs, window, &config);
         surface = eglCreateWindowSurface(dpy, config, window, NULL);
     } else {
         printf("using pbuffer\n");
         eglChooseConfig(dpy, s_configAttribs, &config, 1, &numConfigs);
         EGLint attribs[] = { EGL_WIDTH, 320, EGL_HEIGHT, 480, EGL_NONE };
         surface = eglCreatePbufferSurface(dpy, config, attribs);
         if (surface == EGL_NO_SURFACE) {
             printf("eglCreatePbufferSurface error %x\n", eglGetError());
         }
     }
     context = eglCreateContext(dpy, config, NULL, NULL);
     eglMakeCurrent(dpy, surface, surface, context);   
     eglQuerySurface(dpy, surface, EGL_WIDTH, &w);
     eglQuerySurface(dpy, surface, EGL_HEIGHT, &h);
     GLint dim = w<h ? w : h;

     glViewport(0, 0, w, h);
     glMatrixMode(GL_PROJECTION);
     glLoadIdentity();
     glOrthof(0, w, 0, h, 0, 1);

     glClearColor(0,0,0,0);
     glClear(GL_COLOR_BUFFER_BIT);

     GLint crop[4] = { 0, 4, 4, -4 };
     glBindTexture(GL_TEXTURE_2D, 0);
     glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
     glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
     glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
     glEnable(GL_TEXTURE_2D);
     glColor4f(1,1,1,1);

     // packing is always 4
     uint8_t t8[]  = { 
             0x00, 0x55, 0x00, 0x55, 
             0xAA, 0xFF, 0xAA, 0xFF,
             0x00, 0x55, 0x00, 0x55, 
             0xAA, 0xFF, 0xAA, 0xFF  };

     uint16_t t16[]  = { 
             0x0000, 0x5555, 0x0000, 0x5555, 
             0xAAAA, 0xFFFF, 0xAAAA, 0xFFFF,
             0x0000, 0x5555, 0x0000, 0x5555, 
             0xAAAA, 0xFFFF, 0xAAAA, 0xFFFF  };

     uint16_t t5551[]  = { 
             0x0000, 0xFFFF, 0x0000, 0xFFFF, 
             0xFFFF, 0x0000, 0xFFFF, 0x0000,
             0x0000, 0xFFFF, 0x0000, 0xFFFF, 
             0xFFFF, 0x0000, 0xFFFF, 0x0000  };

     uint32_t t32[]  = { 
             0xFF000000, 0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 
             0xFF00FF00, 0xFFFF0000, 0xFF000000, 0xFF0000FF, 
             0xFF00FFFF, 0xFF00FF00, 0x00FF00FF, 0xFFFFFF00, 
             0xFF000000, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
     };

     switch(test) 
     {
     case 1:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE,
                 4, 4, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, t8);
         break;
     case 2:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB,
                 4, 4, 0, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, t16);
         break;
     case 3:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                 4, 4, 0, GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, t16);
         break;
     case 4:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA,
                 4, 4, 0, GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, t16);
         break;
     case 5:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                 4, 4, 0, GL_RGBA, GL_UNSIGNED_SHORT_5_5_5_1, t5551);
         break;
     case 6:
         glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, t32);
         break;
     }

     //glDrawTexiOES(0, 0, 0, dim, dim);
     
     const GLfloat vertices[4][2] = {
             { 0,    0   },
             { 0,    dim },
             { dim,  dim },
             { dim,  0   }
     };

     const GLfloat texCoords[4][2] = {
             { 0,  0 },
             { 0,  1 },
             { 1,  1 },
             { 1,  0 }
     };
     
     if (!usePbuffer) {
         eglSwapBuffers(dpy, surface);
     }
     
     glMatrixMode(GL_MODELVIEW);
     glScissor(0,dim,dim,h-dim);
     glDisable(GL_SCISSOR_TEST);
     
     for (int y=0 ; y<dim ; y++) {
         //glDisable(GL_SCISSOR_TEST);
         glClear(GL_COLOR_BUFFER_BIT);

         //glEnable(GL_SCISSOR_TEST);

#if USE_DRAW_TEXTURE && GL_OES_draw_texture
         glDrawTexiOES(0, y, 1, dim, dim);
#else
         glLoadIdentity();
         glTranslatef(0, y, 0);
         glEnableClientState(GL_VERTEX_ARRAY);
         glEnableClientState(GL_TEXTURE_COORD_ARRAY);
         glVertexPointer(2, GL_FLOAT, 0, vertices);
         glTexCoordPointer(2, GL_FLOAT, 0, texCoords);
         glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
#endif

         if (!usePbuffer) {
             eglSwapBuffers(dpy, surface);
         } else {
             glFinish();
         }
     }

     eglTerminate(dpy);
     return 0;
}
