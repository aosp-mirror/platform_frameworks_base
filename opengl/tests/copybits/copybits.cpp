// Test software OpenGL hardware accelleration using copybits.

#define LOG_TAG "copybits_test"

#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <sys/types.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include <ui/PixelFormat.h>

#include <cutils/log.h>
#include <cutils/native_handle.h>

#include <utils/Atomic.h>

#include <private/ui/SharedState.h>

#include <hardware/gralloc.h>
#include <hardware/hardware.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

extern "C" EGLNativeWindowType android_createDisplaySurface(void);

using namespace android;

EGLDisplay eglDisplay;
EGLSurface eglSurface;
EGLContext eglContext;
GLuint texture;

hw_module_t const* gralloc_module;
alloc_device_t  *sAllocDev;

#define FIXED_ONE 0x10000 /* 1.0 in 16.16 fixed point. */

int init_gl_surface();
void free_gl_surface();
void init_scene();

int create_physical_texture();
int readTimer();

// ===========================================================================
// Buffer and implementation of android_native_buffer_t
// ===========================================================================

class NativeBuffer;

class Buffer :  public android_native_buffer_t,
                public LightRefBase<Buffer>
{
public:

    // creates w * h buffer
    Buffer(uint32_t w, uint32_t h, PixelFormat format, int usage);

    // return status
    status_t initCheck() const;


    uint32_t getWidth() const           { return mWidth; }
    uint32_t getHeight() const          { return mHeight; }
    uint32_t getStride() const          { return mStride; }
    uint32_t getUsage() const           { return mUsage; }
    PixelFormat getPixelFormat() const  { return mFormat; }
    buffer_handle_t getHandle() const   { return mBufferHandle; }

    android_native_buffer_t* getNativeBuffer() const;

    void setPixel(int x, int y, int r, int g, int b, int a);

private:
    friend class LightRefBase<Buffer>;
    Buffer(const Buffer& rhs);
    ~Buffer();
    Buffer& operator = (const Buffer& rhs);
    const Buffer& operator = (const Buffer& rhs) const;

    status_t initSize(uint32_t w, uint32_t h);

    static void incRef(android_native_base_t* buffer);
    static void decRef(android_native_base_t* buffer);
    static int getHandlePriv(android_native_buffer_t const * buffer,
            buffer_handle_t* handle);

    buffer_handle_t         mBufferHandle;
    ssize_t                 mInitCheck;

    uint32_t                mWidth;
    uint32_t                mHeight;
    uint32_t                mStride;
    uint32_t                mVStride;
    PixelFormat             mFormat;
    void*                   mData;
    uint32_t                mUsage;
};

Buffer::Buffer(uint32_t w, uint32_t h, PixelFormat format, int usage)
    : mBufferHandle(0), mInitCheck(NO_INIT),
    mWidth(0), mHeight(0), mStride(0), mVStride(0), mFormat(format), mData(0),
    mUsage(usage)
{
    common.magic = ANDROID_NATIVE_BUFFER_MAGIC;
    common.version = sizeof(android_native_buffer_t);
    common.incRef = incRef;
    common.decRef = decRef;
    android_native_buffer_t::getHandle = getHandlePriv;
    if (w>0 && h>0) {
        mInitCheck = initSize(w, h);
    }
}

Buffer::~Buffer()
{
    if (mBufferHandle) {

        gralloc_module_t* mod = (gralloc_module_t*)sAllocDev->common.module;
        mod->unmap(mod, mBufferHandle);

        sAllocDev->free(sAllocDev, mBufferHandle);
    }
}

void Buffer::incRef(android_native_base_t* buffer) {
    Buffer* self = static_cast<Buffer*>(
            reinterpret_cast<android_native_buffer_t *>(buffer));
    self->incStrong(self);
}

void Buffer::decRef(android_native_base_t* buffer) {
    Buffer* self = static_cast<Buffer*>(
            reinterpret_cast<android_native_buffer_t *>(buffer));
    self->decStrong(self);
}

int Buffer::getHandlePriv(android_native_buffer_t const * buffer,
        buffer_handle_t* handle) {
    Buffer const * self = static_cast<Buffer const *>(buffer);
    *handle = self->getHandle();
    return 0;
}

status_t Buffer::initCheck() const {
    return mInitCheck;
}

android_native_buffer_t* Buffer::getNativeBuffer() const
{
    Buffer* that = const_cast<Buffer*>(this);
    that->android_native_buffer_t::width = mWidth;
    that->android_native_buffer_t::height = mHeight;
    that->android_native_buffer_t::stride = mStride;
    that->android_native_buffer_t::format = mFormat;
    that->android_native_buffer_t::usage = mUsage;
    that->android_native_buffer_t::bits = mData;
    return static_cast<android_native_buffer_t*>(that);
}

status_t Buffer::initSize(uint32_t w, uint32_t h)
{
    status_t err = NO_ERROR;

    int32_t stride;
    err = sAllocDev->alloc(sAllocDev, w, h, mFormat, mUsage, &mBufferHandle, &stride);

    if (err == NO_ERROR) {
        void* addr = 0;
        gralloc_module_t* mod = (gralloc_module_t*)sAllocDev->common.module;
        err = mod->map(mod, mBufferHandle, &addr);
        if (err == NO_ERROR) {
            mData = addr;
            mWidth  = w;
            mHeight = h;
            mStride = stride;
            mVStride = 0;
        }
    }

    return err;
}

void Buffer::setPixel(int x, int y, int r, int g, int b, int a) {
    if (x < 0 || (unsigned int) x >= mWidth
            || y < 0 || (unsigned int) y >= mHeight) {
        // clipped
        return;
    }
    int index = mStride * y + x;
    switch (mFormat) {
    case HAL_PIXEL_FORMAT_RGB_565: {
            unsigned short val = (unsigned short) (
                    ((0x1f & (r >> 3)) << 11)
                    | ((0x3f & (g >> 2)) << 5)
                    | (0x1f & (b >> 3)));
            ((unsigned short*) mData)[index]= val;
        }
        break;
    case HAL_PIXEL_FORMAT_RGBA_8888: { // ABGR
        unsigned int val = (unsigned int)
            (((a & 0xff) << 24)
                    | ((b & 0xff) << 16)
                    | ((g & 0xff) << 8)
                    | (r & 0xff));
            ((unsigned int*) mData)[index] = val;
        }
        break;
    default:
        // Unsupported pixel format
        break;
    }
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

int init_gralloc() {
    int err = hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &gralloc_module);
    LOGE_IF(err, "FATAL: can't find the %s module", GRALLOC_HARDWARE_MODULE_ID);

    if (err == 0) {
        gralloc_open(gralloc_module, &sAllocDev);
    }
    return err;
}

int init_gl_surface(void)
{
    EGLint numConfigs = 1;
    EGLConfig myConfig = {0};
    EGLint attrib[] =
    {
            EGL_DEPTH_SIZE,     16,
            EGL_NONE
    };

    printf("init_gl_surface\n");
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

    if ( eglChooseConfig(eglDisplay, attrib, &myConfig, 1, &numConfigs) != EGL_TRUE )
    {
        printf("eglChooseConfig failed\n");
        return 0;
    }

    if ( (eglSurface = eglCreateWindowSurface(eglDisplay, myConfig,
            android_createDisplaySurface(), 0)) == EGL_NO_SURFACE )
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
    float ratio = 320.0f /  480.0f;
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
}

// #define USE_ALPHA_COLOR

#define USE_GL_REPLACE
// #define USE_GL_MODULATE

// #define USE_BLEND

#define USE_565
// #define USE_8888

// #define USE_NEAREST
#define USE_LINEAR

#define USE_SCALE

void setSmoothGradient(Buffer* bufferObject) {
    int pixels = bufferObject->getHeight() * bufferObject->getWidth();
    int step = 0;
    for (unsigned int y = 0; y < bufferObject->getHeight(); y++) {
        for(unsigned int x = 0; x < bufferObject->getWidth() ; x++) {
            int grey = step * 255 / pixels;
            bufferObject->setPixel(x, y, grey, grey, grey, 255);
            ++step;
        }
    }
}

void setSmoothAlphaGradient(Buffer* bufferObject) {
    int pixels = bufferObject->getHeight() * bufferObject->getWidth();
    int step = 0;
    for (unsigned int y = 0; y < bufferObject->getHeight(); y++) {
        for(unsigned int x = 0; x < bufferObject->getWidth() ; x++) {
            int grey = step * 255 / pixels;
            bufferObject->setPixel(x, y, 255, 255, 255, grey);
            ++step;
        }
    }
}

void setOrientedCheckerboard(Buffer* bufferObject) {
    bufferObject->setPixel(0, 0, 0, 0, 0, 255);
    for(unsigned int x = 1; x < bufferObject->getWidth() ; x++) {
        bufferObject->setPixel(x, 0, 0, 255, 0, 255);
    }
    for (unsigned int y = 1; y < bufferObject->getHeight(); y++) {
        for(unsigned int x = 0; x < bufferObject->getWidth() ; x++) {
            if ((x ^ y ) & 1) {
                bufferObject->setPixel(x, y, 255, 255, 255, 255);
            } else {
                bufferObject->setPixel(x, y, 255, 0, 0, 255);
            }
        }
    }
}

int create_physical_texture(unsigned int w, unsigned int h)
{

#ifdef USE_565
    PixelFormat format = HAL_PIXEL_FORMAT_RGB_565;
#else
    PixelFormat format = HAL_PIXEL_FORMAT_RGBA_8888;
#endif
    int usage = GRALLOC_USAGE_SW_READ_OFTEN |
        GRALLOC_USAGE_SW_WRITE_OFTEN |
        GRALLOC_USAGE_HW_TEXTURE |
        GRALLOC_USAGE_HW_2D; /* This is the key to allocating the texture in pmem. */
    int32_t stride;
    buffer_handle_t handle;

    // Allocate the hardware buffer
    Buffer* bufferObject = new Buffer(w, h, format, usage);

    android_native_buffer_t* buffer = bufferObject->getNativeBuffer();

    buffer->common.incRef(&buffer->common);

    // create the new EGLImageKHR
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_NONE };
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLImageKHR image = eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            (EGLClientBuffer)buffer, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        printf("Could not create an image %d\n", eglGetError());
        return -1;
    }

    if (buffer->bits == NULL) {
        printf("No bits allocated for image.\n");
        return -2;
    }

    setOrientedCheckerboard(bufferObject);
    // setSmoothGradient(bufferObject);
    // setSmoothAlphaGradient(bufferObject);

    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, image);
#ifdef USE_LINEAR
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
#elif defined(USE_NEAREST)
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
#endif

#ifdef USE_GL_REPLACE
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
#elif defined(USE_GL_MODULATE)
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
#endif

#ifdef USE_ALPHA_COLOR
    glColor4f(1.0f, 1.0f, 1.0f, 0.4f);
#else
    glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
#endif

#ifdef USE_BLEND
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
#endif
    return 0;
}

static const int SCALE_COUNT = 12;

int scale(int base, int factor) {
    static const float kTable[SCALE_COUNT] = {
            0.1f, 0.25f, 0.5f, 0.75f, 1.0f,
            1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 5.0f
    };
    return base * kTable[factor];
}

class Timer {
    struct timeval  first;
    double elapsedSeconds;

public:
    Timer() {}
    void start() {
        gettimeofday(&first, NULL);
    }

    void stop() {
        struct timeval  second,
                        elapsed;
        gettimeofday(&second, NULL);

        if (first.tv_usec > second.tv_usec) {
           second.tv_usec += 1000000;
           second.tv_sec--;
        }

        elapsedSeconds = (second.tv_sec  - first.tv_sec) +
            (second.tv_usec - first.tv_usec) / 1000000.0;
    }

    double getElapsedSeconds() {
        return elapsedSeconds;
    }

    double getElapsedMs() {
        return elapsedSeconds* 1000.0f;
    }
};

int testTime()
{
    static const int WIDTH = 320;
    static const int HEIGHT = 480;
    static const int SCALE = 8;

    if (create_physical_texture(WIDTH, HEIGHT) != 0) {
        return -1;
    }
    // Need to do a dummy eglSwapBuffers first. Don't know why.
    glClearColor(0.4, 1.0, 0.4, 0.4);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay, eglSurface);

    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);

#if defined(USE_SCALE)
    static const int scaleOffset = 0;
#else
    static const int scaleOffset = 1;
#endif
    printf("ms\n");
    for(int j = 0; j < SCALE; j++) {
        int w = WIDTH >> (j + scaleOffset);
        int h = HEIGHT >> j;
        int cropRect[4] = {0,h,w,-h}; // Left bottom width height. Width and Height can be neg to flip.
        glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, cropRect);
        Timer timer;
        timer.start();

        int copyCount = 1000;
        for (int i = 0; i < copyCount; i++) {
            glDrawTexiOES(0, 0, 0, w, h);
        }

        timer.stop();
        printf("%g\n", timer.getElapsedMs() / copyCount);
    }

    eglSwapBuffers(eglDisplay, eglSurface);
    return 0;
}

int testStretch()
{
    static const int WIDTH = 8;
    static const int HEIGHT = 8;

    if (create_physical_texture(WIDTH, HEIGHT) != 0) {
        return -1;
    }
    // Need to do a dummy eglSwapBuffers first. Don't know why.
    glClearColor(0.4, 1.0, 0.4, 0.4);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay, eglSurface);

    int cropRect[4] = {0,HEIGHT,WIDTH,-HEIGHT}; // Left bottom width height. Width and Height can be neg to flip.
    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, cropRect);

    for(int frame = 0; frame < 2; frame++) {
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        int baseX = 10;
        for (int x = 0; x < SCALE_COUNT; x++) {
            int baseY = 10;
            int width = scale(WIDTH, x);
            for (int y = 0; y < SCALE_COUNT; y++) {
                int height = scale(HEIGHT, y);
                glDrawTexxOES(baseX << 16, baseY << 16, 0, width << 16, height << 16);
                baseY += height + 10;
            }
            baseX += width + 10;
        }

        eglSwapBuffers(eglDisplay, eglSurface);
    }
    return 0;
}

int testRot90()
{
    static const int WIDTH = 8;
    static const int HEIGHT = 8;

    if (create_physical_texture(WIDTH, HEIGHT) != 0) {
        return -1;
    }

    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrthof(0, 320, 480, 0, 0, 1);

    glMatrixMode(GL_MODELVIEW);

    glLoadIdentity();

    // Need to do a dummy eglSwapBuffers first. Don't know why.
    glClearColor(0.4, 0.4, 0.4, 0.4);
    glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay, eglSurface);

    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glColor4x(0x10000, 0x10000, 0x10000, 0x10000);
    glDisable(GL_BLEND);
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_CULL_FACE);

    for(int frame = 0; frame < 2; frame++) {
        glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
        int baseX = 10;
        for (int x = 0; x < SCALE_COUNT; x++) {
            int baseY = 10;
            int width = scale(WIDTH, x);
            for (int y = 0; y < SCALE_COUNT; y++) {
                int height = scale(HEIGHT, y);

                // Code copied from SurfaceFlinger LayerBase.cpp

                const GLfixed texCoords[4][2] = {
                        { 0,        0 },
                        { 0,        0x10000 },
                        { 0x10000,  0x10000 },
                        { 0x10000,  0 }
                };

                GLfixed fx = baseX << 16;
                GLfixed fy = baseY << 16;
                GLfixed fw = width << 16;
                GLfixed fh = height << 16;

                /*
                 * Vertex pattern:
                 *    (2)--(3)
                 *     |\   |
                 *     | \  |
                 *     |  \ |
                 *     |   \|
                 *    (1)--(0)
                 *
                 */

                const GLfixed vertices[4][2] = {
                        {fx + fw, fy},
                        {fx,      fy},
                        {fx,      fy + fh},
                        {fx + fw, fy + fh}
                };

                static const bool rotate90 = true;

                glMatrixMode(GL_TEXTURE);
                glLoadIdentity();

                glEnableClientState(GL_VERTEX_ARRAY);
                glEnableClientState(GL_TEXTURE_COORD_ARRAY);
                glVertexPointer(2, GL_FIXED, 0, vertices);
                glTexCoordPointer(2, GL_FIXED, 0, texCoords);

                LOGW("testRot90 %d, %d %d, %d", baseX, baseY, width, height);
                glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

                baseY += height + 10;
            }
            baseX += width + 10;
        }

        eglSwapBuffers(eglDisplay, eglSurface);
    }
    return 0;
}

int main(int argc, char **argv)
{

    int q;
    int start, end;

    if (init_gralloc()) {
        printf("gralloc initialization failed - exiting\n");
        return 0;
    }

    printf("Initializing EGL...\n");

    if(!init_gl_surface())
    {
        printf("GL initialisation failed - exiting\n");
        return 0;
    }

    init_scene();

    printf("Start test...\n");
    // testTime();
    // testStretch();
    testRot90();
    free_gl_surface();

    return 0;
}
