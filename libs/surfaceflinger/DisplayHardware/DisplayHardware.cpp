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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>

#include <cutils/properties.h>

#include <utils/Log.h>

#include <ui/EGLDisplaySurface.h>

#include <GLES/gl.h>
#include <EGL/eglext.h>


#include "DisplayHardware/DisplayHardware.h"

#include <hardware/copybit.h>
#include <hardware/overlay.h>

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
    // initialize EGL
    const EGLint attribs[] = {
            EGL_RED_SIZE,       5,
            EGL_GREEN_SIZE,     6,
            EGL_BLUE_SIZE,      5,
            EGL_DEPTH_SIZE,     0,
            EGL_NONE
    };
    EGLint w, h, dummy;
    EGLint numConfigs, n;
    EGLConfig config;
    EGLSurface surface;
    EGLContext context;
    mFlags = 0;

    // TODO: all the extensions below should be queried through
    // eglGetProcAddress().

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, NULL, NULL);
    eglGetConfigs(display, NULL, 0, &numConfigs);
    eglChooseConfig(display, attribs, &config, 1, &n);

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

    // TODO: get this from the devfb driver (probably should be HAL module)
    mFlags |= SWAP_RECTANGLE_EXTENSION;
    
    // TODO: get the real "update_on_demand" behavior (probably should be HAL module)
    mFlags |= UPDATE_ON_DEMAND;

    if (eglGetConfigAttrib(display, config, EGL_CONFIG_CAVEAT, &dummy) == EGL_TRUE) {
        if (dummy == EGL_SLOW_CONFIG)
            mFlags |= SLOW_CONFIG;
    }

    /*
     * Create our main surface
     */

    mDisplaySurface = new EGLDisplaySurface();

    surface = eglCreateWindowSurface(display, config, mDisplaySurface.get(), NULL);
    //checkEGLErrors("eglCreateDisplaySurfaceANDROID");

    if (eglQuerySurface(display, surface, EGL_SWAP_BEHAVIOR, &dummy) == EGL_TRUE) {
        if (dummy == EGL_BUFFER_PRESERVED) {
            mFlags |= BUFFER_PRESERVED;
        }
    }
    
    GLint value = EGL_UNKNOWN;
    eglQuerySurface(display, surface, EGL_HORIZONTAL_RESOLUTION, &value);
    if (value == EGL_UNKNOWN) {
        mDpiX = 160.0f;
    } else {
        mDpiX = 25.4f * float(value)/EGL_DISPLAY_SCALING;
    }
    value = EGL_UNKNOWN;
    eglQuerySurface(display, surface, EGL_VERTICAL_RESOLUTION, &value);
    if (value == EGL_UNKNOWN) {
        mDpiY = 160.0f;
    } else {
        mDpiY = 25.4f * float(value)/EGL_DISPLAY_SCALING;
    }
    mRefreshRate = 60.f;    // TODO: get the real refresh rate 
    
    
    char property[PROPERTY_VALUE_MAX];
    /* Read density from build-specific ro.sf.lcd_density property
     * except if it is overriden by qemu.sf.lcd_density.
     */
    if (property_get("qemu.sf.lcd_density", property, NULL) <= 0) {
        if (property_get("ro.sf.lcd_density", property, NULL) <= 0) {
            LOGW("ro.sf.lcd_density not defined, using 160 dpi by default.");
            strcpy(property, "160");
        }
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
    if (strstr(gl_extensions, "GL_ANDROID_direct_texture")) {
        mFlags |= DIRECT_TEXTURE;
    }

    // Unbind the context from this thread
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    mDisplay = display;
    mConfig  = config;
    mSurface = surface;
    mContext = context;
    mFormat  = GGL_PIXEL_FORMAT_RGB_565;
    
    hw_module_t const* module;

    mBlitEngine = NULL;
    if (hw_get_module(COPYBIT_HARDWARE_MODULE_ID, &module) == 0) {
        copybit_open(module, &mBlitEngine);
    }

    mOverlayEngine = NULL;
    if (hw_get_module(OVERLAY_HARDWARE_MODULE_ID, &module) == 0) {
        overlay_control_open(module, &mOverlayEngine);
    }
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
    copybit_close(mBlitEngine);
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

void DisplayHardware::getDisplaySurface(copybit_image_t* img) const
{
    img->w      = mDisplaySurface->stride;
    img->h      = mDisplaySurface->height;
    img->format = mDisplaySurface->format;
    img->offset = mDisplaySurface->offset;
    img->base   = (void*)mDisplaySurface->base;
    img->fd     = mDisplaySurface->fd;
}

void DisplayHardware::getDisplaySurface(GGLSurface* fb) const
{
    fb->version= sizeof(GGLSurface);
    fb->width  = mDisplaySurface->width;
    fb->height = mDisplaySurface->height;
    fb->stride = mDisplaySurface->stride;
    fb->format = mDisplaySurface->format;
    fb->data   = (GGLubyte*)mDisplaySurface->base + mDisplaySurface->offset;
}

uint32_t DisplayHardware::getPageFlipCount() const {
    return mDisplaySurface->getPageFlipCount();
}

/*
 * "Flip" the front and back buffers.
 */

void DisplayHardware::flip(const Region& dirty) const
{
    checkGLErrors();

    EGLDisplay dpy = mDisplay;
    EGLSurface surface = mSurface;

    Region newDirty(dirty);
    newDirty.andSelf(Rect(mWidth, mHeight));

    if (mFlags & BUFFER_PRESERVED) {
        const Region copyback(mDirty.subtract(newDirty));
        mDirty = newDirty;
        mDisplaySurface->copyFrontToBack(copyback);
    } 

    if (mFlags & SWAP_RECTANGLE_EXTENSION) {
        const Rect& b(newDirty.bounds());
        mDisplaySurface->setSwapRectangle(
                b.left, b.top, b.width(), b.height());
    }

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

void DisplayHardware::copyFrontToImage(const copybit_image_t& front) const {
    mDisplaySurface->copyFrontToImage(front);
}

void DisplayHardware::copyBackToImage(const copybit_image_t& front) const {
    mDisplaySurface->copyBackToImage(front);
}
