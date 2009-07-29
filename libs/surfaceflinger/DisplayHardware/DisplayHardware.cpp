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
#include <string.h>
#include <math.h>

#include <cutils/properties.h>

#include <utils/RefBase.h>
#include <utils/Log.h>

#include <ui/PixelFormat.h>
#include <ui/FramebufferNativeWindow.h>

#include <GLES/gl.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <pixelflinger/pixelflinger.h>

#include "DisplayHardware/DisplayHardware.h"

#include <hardware/copybit.h>
#include <hardware/overlay.h>
#include <hardware/gralloc.h>

using namespace android;

static __attribute__((noinline))
const char *egl_strerror(EGLint err)
{
    switch (err){
        case EGL_SUCCESS:           return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED:   return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS:        return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC:         return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE:     return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONFIG:        return "EGL_BAD_CONFIG";
        case EGL_BAD_CONTEXT:       return "EGL_BAD_CONTEXT";
        case EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY:       return "EGL_BAD_DISPLAY";
        case EGL_BAD_MATCH:         return "EGL_BAD_MATCH";
        case EGL_BAD_NATIVE_PIXMAP: return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW: return "EGL_BAD_NATIVE_WINDOW";
        case EGL_BAD_PARAMETER:     return "EGL_BAD_PARAMETER";
        case EGL_BAD_SURFACE:       return "EGL_BAD_SURFACE";
        case EGL_CONTEXT_LOST:      return "EGL_CONTEXT_LOST";
        default: return "UNKNOWN";
    }
}

static __attribute__((noinline))
void checkGLErrors()
{
    GLenum error = glGetError();
    if (error != GL_NO_ERROR)
        LOGE("GL error 0x%04x", int(error));
}

static __attribute__((noinline))
void checkEGLErrors(const char* token)
{
    EGLint error = eglGetError();
    // GLESonGL seems to be returning 0 when there is no errors?
    if (error && error != EGL_SUCCESS)
        LOGE("%s error 0x%04x (%s)",
                token, int(error), egl_strerror(error));
}


/*
 * Initialize the display to the specified values.
 *
 */

DisplayHardware::DisplayHardware(
        const sp<SurfaceFlinger>& flinger,
        uint32_t dpy)
    : DisplayHardwareBase(flinger, dpy)
{
    init(dpy);
}

DisplayHardware::~DisplayHardware()
{
    fini();
}

float DisplayHardware::getDpiX() const          { return mDpiX; }
float DisplayHardware::getDpiY() const          { return mDpiY; }
float DisplayHardware::getDensity() const       { return mDensity; }
float DisplayHardware::getRefreshRate() const   { return mRefreshRate; }
int DisplayHardware::getWidth() const           { return mWidth; }
int DisplayHardware::getHeight() const          { return mHeight; }
PixelFormat DisplayHardware::getFormat() const  { return mFormat; }

void DisplayHardware::init(uint32_t dpy)
{
    hw_module_t const* module;

    mNativeWindow = new FramebufferNativeWindow();

    mOverlayEngine = NULL;
    if (hw_get_module(OVERLAY_HARDWARE_MODULE_ID, &module) == 0) {
        overlay_control_open(module, &mOverlayEngine);
    }

    framebuffer_device_t const * fbDev = mNativeWindow->getDevice();

    PixelFormatInfo fbFormatInfo;
    getPixelFormatInfo(PixelFormat(fbDev->format), &fbFormatInfo);

    // initialize EGL
    const EGLint attribs[] = {
            EGL_BUFFER_SIZE,    fbFormatInfo.bitsPerPixel,
            EGL_DEPTH_SIZE,     0,
            EGL_NONE
    };
    EGLint w, h, dummy;
    EGLint numConfigs=0, n=0;
    EGLSurface surface;
    EGLContext context;
    mFlags = 0;

    // TODO: all the extensions below should be queried through
    // eglGetProcAddress().

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, NULL, NULL);
    eglGetConfigs(display, NULL, 0, &numConfigs);

    // Get all the "potential match" configs...
    EGLConfig* const configs = new EGLConfig[numConfigs];
    eglChooseConfig(display, attribs, configs, numConfigs, &n);
    LOGE_IF(n<=0, "no EGLConfig available!");
    EGLConfig config = configs[0];
    if (n > 1) {
        // if there is more than one candidate, go through the list
        // and pick one that matches our framebuffer format
        int fbSzA = fbFormatInfo.getSize(PixelFormatInfo::INDEX_ALPHA);
        int fbSzR = fbFormatInfo.getSize(PixelFormatInfo::INDEX_RED);
        int fbSzG = fbFormatInfo.getSize(PixelFormatInfo::INDEX_GREEN);
        int fbSzB = fbFormatInfo.getSize(PixelFormatInfo::INDEX_BLUE); 
        for (int i=0 ; i<n ; i++) {
            EGLint r,g,b,a;
            eglGetConfigAttrib(display, configs[i], EGL_RED_SIZE,   &r);
            eglGetConfigAttrib(display, configs[i], EGL_GREEN_SIZE, &g);
            eglGetConfigAttrib(display, configs[i], EGL_BLUE_SIZE,  &b);
            eglGetConfigAttrib(display, configs[i], EGL_ALPHA_SIZE, &a);
            if (fbSzA == a && fbSzR == r && fbSzG == g && fbSzB  == b) {
                config = configs[i];
                break;
            }
        }
    }
    delete [] configs;

    /*
     * Gather EGL extensions
     */

    const char* const egl_extensions = eglQueryString(
            display, EGL_EXTENSIONS);
    
    LOGI("EGL informations:");
    LOGI("# of configs : %d", numConfigs);
    LOGI("vendor    : %s", eglQueryString(display, EGL_VENDOR));
    LOGI("version   : %s", eglQueryString(display, EGL_VERSION));
    LOGI("extensions: %s", egl_extensions);
    LOGI("Client API: %s", eglQueryString(display, EGL_CLIENT_APIS)?:"Not Supported");


    if (mNativeWindow->isUpdateOnDemand()) {
        mFlags |= UPDATE_ON_DEMAND;
    }
    
    if (eglGetConfigAttrib(display, config, EGL_CONFIG_CAVEAT, &dummy) == EGL_TRUE) {
        if (dummy == EGL_SLOW_CONFIG)
            mFlags |= SLOW_CONFIG;
    }

    /*
     * Create our main surface
     */

    surface = eglCreateWindowSurface(display, config, mNativeWindow.get(), NULL);
    checkEGLErrors("eglCreateWindowSurface");

    if (eglQuerySurface(display, surface, EGL_SWAP_BEHAVIOR, &dummy) == EGL_TRUE) {
        if (dummy == EGL_BUFFER_PRESERVED) {
            mFlags |= BUFFER_PRESERVED;
        }
    }

#ifdef EGL_ANDROID_swap_rectangle    
    if (strstr(egl_extensions, "EGL_ANDROID_swap_rectangle")) {
        mFlags |= SWAP_RECTANGLE;
    }
    // when we have the choice between UPDATE_ON_DEMAND and SWAP_RECTANGLE
    // choose UPDATE_ON_DEMAND, which is more efficient
    if (mFlags & UPDATE_ON_DEMAND)
        mFlags &= ~SWAP_RECTANGLE;
#endif
    

    mDpiX = mNativeWindow->xdpi;
    mDpiY = mNativeWindow->ydpi;
    mRefreshRate = fbDev->fps; 
    
    char property[PROPERTY_VALUE_MAX];
    /* Read density from build-specific ro.sf.lcd_density property
     * except if it is overriden by qemu.sf.lcd_density.
     */
    if (property_get("qemu.sf.lcd_density", property, NULL) <= 0) {
        if (property_get("ro.sf.lcd_density", property, NULL) <= 0) {
            LOGW("ro.sf.lcd_density not defined, using 160 dpi by default.");
            strcpy(property, "160");
        }
    } else {
        /* for the emulator case, reset the dpi values too */
        mDpiX = mDpiY = atoi(property);
    }
    mDensity = atoi(property) * (1.0f/160.0f);


    /*
     * Create our OpenGL ES context
     */
    
    context = eglCreateContext(display, config, NULL, NULL);
    //checkEGLErrors("eglCreateContext");
    
    eglQuerySurface(display, surface, EGL_WIDTH, &mWidth);
    eglQuerySurface(display, surface, EGL_HEIGHT, &mHeight);
    
    
    /*
     * Gather OpenGL ES extensions
     */

    eglMakeCurrent(display, surface, surface, context);
    const char* const  gl_extensions = (const char*)glGetString(GL_EXTENSIONS);
    LOGI("OpenGL informations:");
    LOGI("vendor    : %s", glGetString(GL_VENDOR));
    LOGI("renderer  : %s", glGetString(GL_RENDERER));
    LOGI("version   : %s", glGetString(GL_VERSION));
    LOGI("extensions: %s", gl_extensions);

    if (strstr(gl_extensions, "GL_ARB_texture_non_power_of_two")) {
        mFlags |= NPOT_EXTENSION;
    }
    if (strstr(gl_extensions, "GL_OES_draw_texture")) {
        mFlags |= DRAW_TEXTURE_EXTENSION;
    }
    if (strstr( gl_extensions, "GL_OES_EGL_image") &&
        (strstr(egl_extensions, "EGL_KHR_image_base") || 
                strstr(egl_extensions, "EGL_KHR_image")) &&
        strstr(egl_extensions, "EGL_ANDROID_image_native_buffer")) {
        mFlags |= DIRECT_TEXTURE;
    }

    // Unbind the context from this thread
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    mDisplay = display;
    mConfig  = config;
    mSurface = surface;
    mContext = context;
    mFormat  = fbDev->format;
    mPageFlipCount = 0;
}

/*
 * Clean up.  Throw out our local state.
 *
 * (It's entirely possible we'll never get here, since this is meant
 * for real hardware, which doesn't restart.)
 */

void DisplayHardware::fini()
{
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglTerminate(mDisplay);
    overlay_control_close(mOverlayEngine);
}

void DisplayHardware::releaseScreen() const
{
    DisplayHardwareBase::releaseScreen();
}

void DisplayHardware::acquireScreen() const
{
    DisplayHardwareBase::acquireScreen();
}

uint32_t DisplayHardware::getPageFlipCount() const {
    return mPageFlipCount;
}

/*
 * "Flip" the front and back buffers.
 */

void DisplayHardware::flip(const Region& dirty) const
{
    checkGLErrors();

    EGLDisplay dpy = mDisplay;
    EGLSurface surface = mSurface;

#ifdef EGL_ANDROID_swap_rectangle    
    if (mFlags & SWAP_RECTANGLE) {
        const Region newDirty(dirty.intersect(bounds()));
        const Rect b(newDirty.getBounds());
        eglSetSwapRectangleANDROID(dpy, surface,
                b.left, b.top, b.width(), b.height());
    } 
#endif
    
    if (mFlags & UPDATE_ON_DEMAND) {
        mNativeWindow->setUpdateRectangle(dirty.getBounds());
    }
    
    mPageFlipCount++;
    eglSwapBuffers(dpy, surface);
    checkEGLErrors("eglSwapBuffers");

    // for debugging
    //glClearColor(1,0,0,0);
    //glClear(GL_COLOR_BUFFER_BIT);
}

uint32_t DisplayHardware::getFlags() const
{
    return mFlags;
}

void DisplayHardware::makeCurrent() const
{
    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
}
