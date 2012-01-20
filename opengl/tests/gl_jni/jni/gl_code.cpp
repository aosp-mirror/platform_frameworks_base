// OpenGL ES 1.0 code

#include <nativehelper/jni.h>
#define LOG_TAG "GLJNI gl_code.cpp"
#include <utils/Log.h>

#include <GLES/gl.h>

#include <stdio.h>

#include <stdlib.h>
#include <math.h>

GLuint texture;
GLfloat background;

#define FIXED_ONE 0x10000

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    ALOGI("GL %s = %s\n", name, v);
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

void init_scene(int width, int height)
{
    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);

    glDisable(GL_DITHER);
    glEnable(GL_CULL_FACE);

    float ratio = width / height;
    glViewport(0, 0, width, height);

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

void create_texture()
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

extern "C" {
    JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_init(JNIEnv * env, jobject obj,  jint width, jint height);
    JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_step(JNIEnv * env, jobject obj);
    JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_changeBackground(JNIEnv * env, jobject obj);
};

JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_init(JNIEnv * env, jobject obj,  jint width, jint height)
{
    init_scene(width, height);
    create_texture();
}

JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_step(JNIEnv * env, jobject obj)
{
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
    glVertexPointer(3, GL_FLOAT, 0, vertices);
    glTexCoordPointer(2, GL_FIXED, 0, texCoords);

    int nelem = sizeof(quadIndices)/sizeof(quadIndices[0]);
    static float grey;
    grey += 0.01f;
    if (grey > 1.0f) {
        grey = 0.0f;
    }
    glClearColor(background, grey, grey, 1.0f);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    glDrawElements(GL_TRIANGLES, nelem, GL_UNSIGNED_SHORT, quadIndices);
}

JNIEXPORT void JNICALL Java_com_android_gljni_GLJNILib_changeBackground(JNIEnv * env, jobject obj)
{
    background = 1.0f - background;
}
