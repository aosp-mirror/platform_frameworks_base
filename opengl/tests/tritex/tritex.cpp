// Calls glDrawElements() the number of times specified by
// ITERATIONS. Should draw a checkerboard on the screen after
// a few seconds.
//
// Ported from a Java version by Google.

#include <EGL/egl.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <ui/FramebufferNativeWindow.h>
#include "EGLUtils.h"

#include <stdio.h>
#include <stdlib.h>
#include <math.h>

using namespace android;

EGLDisplay eglDisplay;
EGLSurface eglSurface;
EGLContext eglContext;
GLuint texture;

#define FIXED_ONE 0x10000
#define ITERATIONS 50

int init_gl_surface(void);
void free_gl_surface(void);
void init_scene(void);
void render(int quads);
void create_texture(void);
int readTimer(void);

static void gluLookAt(float eyeX, float eyeY, float eyeZ,
        float centerX, float centerY, float centerZ, float upX, float upY,
        float upZ)
{
    // See the OpenGL GLUT documentation for gluLookAt for a description
    // of the algorithm. We implement it in a straightforward way:

    float fx = centerX - eyeX;
    float fy = centerY - eyeY;
    float fz = centerZ - eyeZ;

    // Normalize f
    float rlf = 1.0f / sqrtf(fx*fx + fy*fy + fz*fz);
    fx *= rlf;
    fy *= rlf;
    fz *= rlf;

    // Normalize up
    float rlup = 1.0f / sqrtf(upX*upX + upY*upY + upZ*upZ);
    upX *= rlup;
    upY *= rlup;
    upZ *= rlup;

    // compute s = f x up (x means "cross product")

    float sx = fy * upZ - fz * upY;
    float sy = fz * upX - fx * upZ;
    float sz = fx * upY - fy * upX;

    // compute u = s x f
    float ux = sy * fz - sz * fy;
    float uy = sz * fx - sx * fz;
    float uz = sx * fy - sy * fx;

    float m[16] ;
    m[0] = sx;
    m[1] = ux;
    m[2] = -fx;
    m[3] = 0.0f;

    m[4] = sy;
    m[5] = uy;
    m[6] = -fy;
    m[7] = 0.0f;

    m[8] = sz;
    m[9] = uz;
    m[10] = -fz;
    m[11] = 0.0f;

    m[12] = 0.0f;
    m[13] = 0.0f;
    m[14] = 0.0f;
    m[15] = 1.0f;

    glMultMatrixf(m);
    glTranslatef(-eyeX, -eyeY, -eyeZ);
}

int main(int argc, char **argv)
{
    int q;
    int start, end;

    printf("Initializing EGL...\n");

    if(!init_gl_surface())
    {
        printf("GL initialisation failed - exiting\n");
        return 0;
    }

    init_scene();

    create_texture();

    printf("Start test...\n");

    render(argc==2 ? atoi(argv[1]) : ITERATIONS);

    free_gl_surface();

    return 0;
}

int init_gl_surface(void)
{
    EGLint numConfigs = 1;
    EGLConfig myConfig = {0};
    EGLint attrib[] =
    {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_DEPTH_SIZE,     16,
            EGL_NONE
    };

    if ( (eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY)) == EGL_NO_DISPLAY )
    {
        printf("eglGetDisplay failed\n");
        return 0;
    }

    if ( eglInitialize(eglDisplay, NULL, NULL) != EGL_TRUE )
    {
        printf("eglInitialize failed\n");
        return 0;
    }

    EGLNativeWindowType window = android_createDisplaySurface();
    EGLUtils::selectConfigForNativeWindow(eglDisplay, attrib, window, &myConfig);

    if ( (eglSurface = eglCreateWindowSurface(eglDisplay, myConfig,
            window, 0)) == EGL_NO_SURFACE )
    {
        printf("eglCreateWindowSurface failed\n");
        return 0;
    }

    if ( (eglContext = eglCreateContext(eglDisplay, myConfig, 0, 0)) == EGL_NO_CONTEXT )
    {
        printf("eglCreateContext failed\n");
        return 0;
    }

    if ( eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) != EGL_TRUE )
    {
        printf("eglMakeCurrent failed\n");
        return 0;
    }

    return 1;
}

void free_gl_surface(void)
{
    if (eglDisplay != EGL_NO_DISPLAY)
    {
        eglMakeCurrent( EGL_NO_DISPLAY, EGL_NO_SURFACE,
                EGL_NO_SURFACE, EGL_NO_CONTEXT );
        eglDestroyContext( eglDisplay, eglContext );
        eglDestroySurface( eglDisplay, eglSurface );
        eglTerminate( eglDisplay );
        eglDisplay = EGL_NO_DISPLAY;
    }
}

void init_scene(void)
{
    glDisable(GL_DITHER);
    glEnable(GL_CULL_FACE);

    float ratio = 320.0f / 480.0f;
    glViewport(0, 0, 320, 480);

    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glFrustumf(-ratio, ratio, -1, 1, 1, 10);

    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    gluLookAt(
            0, 0, 3,  // eye
            0, 0, 0,  // center
            0, 1, 0); // up

    glEnable(GL_TEXTURE_2D);
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
}

void create_texture(void)
{
    const unsigned int on = 0xff0000ff;
    const unsigned int off = 0xffffffff;
    const unsigned int pixels[] =
    {
            on, off, on, off, on, off, on, off,
            off, on, off, on, off, on, off, on,
            on, off, on, off, on, off, on, off,
            off, on, off, on, off, on, off, on,
            on, off, on, off, on, off, on, off,
            off, on, off, on, off, on, off, on,
            on, off, on, off, on, off, on, off,
            off, on, off, on, off, on, off, on,
    };
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
}

void render(int quads)
{
    int i, j;

    const GLfloat vertices[] = {
            -1,  -1,  0,
             1,  -1,  0,
             1,   1,  0,
            -1,   1,  0
    };

    const GLfixed texCoords[] = {
            0,            0,
            FIXED_ONE,    0,
            FIXED_ONE,    FIXED_ONE,
            0,            FIXED_ONE
    };

    const GLushort quadIndices[] = { 0, 1, 2,  0, 2, 3 };


    GLushort* indices = (GLushort*)malloc(quads*sizeof(quadIndices));
    for (i=0 ; i<quads ; i++)
        memcpy(indices+(sizeof(quadIndices)/sizeof(indices[0]))*i, quadIndices, sizeof(quadIndices));

    glVertexPointer(3, GL_FLOAT, 0, vertices);
    glTexCoordPointer(2, GL_FIXED, 0, texCoords);

    // make sure to do a couple eglSwapBuffers to make sure there are
    // no problems with the very first ones (who knows)
    glClearColor(0.4, 0.4, 0.4, 0.4);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay, eglSurface);
    glClearColor(0.6, 0.6, 0.6, 0.6);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay, eglSurface);
    glClearColor(1.0, 1.0, 1.0, 1.0);

    for (j=0 ; j<10 ; j++) {
        printf("loop %d / 10 (%d quads / loop)\n", j, quads);

        int nelem = sizeof(quadIndices)/sizeof(quadIndices[0]);
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        glDrawElements(GL_TRIANGLES, nelem*quads, GL_UNSIGNED_SHORT, indices);
        eglSwapBuffers(eglDisplay, eglSurface);
    }

    free(indices);
}

