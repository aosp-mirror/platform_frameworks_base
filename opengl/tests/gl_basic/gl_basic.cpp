// Simple OpenGL ES 1.x application showing how to initialize and draw something.

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
void render();
void create_texture(void);
int readTimer(void);

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    fprintf(stderr, "GL %s = %s\n", name, v);
}

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

void printEGLConfiguration(EGLDisplay dpy, EGLConfig config) {

#define X(VAL) {VAL, #VAL}
    struct {EGLint attribute; const char* name;} names[] = {
    X(EGL_BUFFER_SIZE),
    X(EGL_ALPHA_SIZE),
    X(EGL_BLUE_SIZE),
    X(EGL_GREEN_SIZE),
    X(EGL_RED_SIZE),
    X(EGL_DEPTH_SIZE),
    X(EGL_STENCIL_SIZE),
    X(EGL_CONFIG_CAVEAT),
    X(EGL_CONFIG_ID),
    X(EGL_LEVEL),
    X(EGL_MAX_PBUFFER_HEIGHT),
    X(EGL_MAX_PBUFFER_PIXELS),
    X(EGL_MAX_PBUFFER_WIDTH),
    X(EGL_NATIVE_RENDERABLE),
    X(EGL_NATIVE_VISUAL_ID),
    X(EGL_NATIVE_VISUAL_TYPE),
    X(EGL_SAMPLES),
    X(EGL_SAMPLE_BUFFERS),
    X(EGL_SURFACE_TYPE),
    X(EGL_TRANSPARENT_TYPE),
    X(EGL_TRANSPARENT_RED_VALUE),
    X(EGL_TRANSPARENT_GREEN_VALUE),
    X(EGL_TRANSPARENT_BLUE_VALUE),
    X(EGL_BIND_TO_TEXTURE_RGB),
    X(EGL_BIND_TO_TEXTURE_RGBA),
    X(EGL_MIN_SWAP_INTERVAL),
    X(EGL_MAX_SWAP_INTERVAL),
    X(EGL_LUMINANCE_SIZE),
    X(EGL_ALPHA_MASK_SIZE),
    X(EGL_COLOR_BUFFER_TYPE),
    X(EGL_RENDERABLE_TYPE),
    X(EGL_CONFORMANT),
   };
#undef X

    for (size_t j = 0; j < sizeof(names) / sizeof(names[0]); j++) {
        EGLint value = -1;
        EGLint returnVal = eglGetConfigAttrib(dpy, config, names[j].attribute, &value);
        EGLint error = eglGetError();
        if (returnVal && error == EGL_SUCCESS) {
            printf(" %s: ", names[j].name);
            printf("%d (0x%x)", value, value);
        }
    }
    printf("\n");
}

static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE) {
    if (returnVal != EGL_TRUE) {
        fprintf(stderr, "%s() returned %d\n", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        fprintf(stderr, "after %s() eglError %s (0x%x)\n", op, EGLUtils::strerror(error),
                error);
    }
}

int printEGLConfigurations(EGLDisplay dpy) {
    EGLint numConfig = 0;
    EGLint returnVal = eglGetConfigs(dpy, NULL, 0, &numConfig);
    checkEglError("eglGetConfigs", returnVal);
    if (!returnVal) {
        return false;
    }

    printf("Number of EGL configurations: %d\n", numConfig);

    EGLConfig* configs = (EGLConfig*) malloc(sizeof(EGLConfig) * numConfig);
    if (! configs) {
        printf("Could not allocate configs.\n");
        return false;
    }

    returnVal = eglGetConfigs(dpy, configs, numConfig, &numConfig);
    checkEglError("eglGetConfigs", returnVal);
    if (!returnVal) {
        free(configs);
        return false;
    }

    for(int i = 0; i < numConfig; i++) {
        printf("Configuration %d\n", i);
        printEGLConfiguration(dpy, configs[i]);
    }

    free(configs);
    return true;
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
    printf("Running...\n");
    while(true) {
        render();
    }
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

    if (! printEGLConfigurations(eglDisplay)) {
        printf("printEGLConfigurations failed.\n");
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
    
    int w, h;

    eglQuerySurface(eglDisplay, eglSurface, EGL_WIDTH, &w);
    checkEglError("eglQuerySurface");
    eglQuerySurface(eglDisplay, eglSurface, EGL_HEIGHT, &h);
    checkEglError("eglQuerySurface");
    GLint dim = w < h ? w : h;
    
    fprintf(stderr, "Window dimensions: %d x %d\n", w, h);

    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);

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

void render()
{
    int i, j;
    int quads = 1;

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

    const GLushort indices[] = { 0, 1, 2,  0, 2, 3 };

    glVertexPointer(3, GL_FLOAT, 0, vertices);
    glTexCoordPointer(2, GL_FIXED, 0, texCoords);
    glClearColor(1.0, 1.0, 1.0, 1.0);
    int nelem = sizeof(indices)/sizeof(indices[0]);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    glDrawElements(GL_TRIANGLES, nelem, GL_UNSIGNED_SHORT, indices);
    eglSwapBuffers(eglDisplay, eglSurface);
}
