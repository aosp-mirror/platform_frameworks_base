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

#define LOG_TAG "BootAnimation"

#include <stdint.h>
#include <sys/types.h>
#include <math.h>
#include <fcntl.h>
#include <utils/misc.h>

#include <utils/threads.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/AssetManager.h>

#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <ui/DisplayInfo.h>
#include <ui/ISurfaceComposer.h>
#include <ui/ISurfaceFlingerClient.h>
#include <ui/EGLNativeWindowSurface.h>

#include <core/SkBitmap.h>
#include <images/SkImageDecoder.h>

#include <GLES/egl.h>

#include "BootAnimation.h"

namespace android {

// ---------------------------------------------------------------------------

BootAnimation::BootAnimation(const sp<ISurfaceComposer>& composer)
:   Thread(false)
{
    mSession = SurfaceComposerClient::clientForConnection(
            composer->createConnection()->asBinder());
}

BootAnimation::~BootAnimation()
{
}

void BootAnimation::onFirstRef()
{
    run("BootAnimation", PRIORITY_DISPLAY);
}

const sp<SurfaceComposerClient>& BootAnimation::session() const 
{
    return mSession;
}

status_t BootAnimation::initTexture(
        Texture* texture, AssetManager& assets, const char* name)
{
    Asset* asset = assets.open(name, Asset::ACCESS_BUFFER);
    if (!asset) return NO_INIT;
    SkBitmap bitmap;
    SkImageDecoder::DecodeMemory(asset->getBuffer(false), asset->getLength(),
            &bitmap, SkBitmap::kNo_Config, SkImageDecoder::kDecodePixels_Mode);
    asset->close();
    delete asset;

    // ensure we can call getPixels(). No need to call unlock, since the
    // bitmap will go out of scope when we return from this method.
    bitmap.lockPixels();

    const int w = bitmap.width();
    const int h = bitmap.height();    
    const void* p = bitmap.getPixels();
    
    GLint crop[4] = { 0, h, w, -h };
    texture->w = w;
    texture->h = h;

    glGenTextures(1, &texture->name);
    glBindTexture(GL_TEXTURE_2D, texture->name);
    
    switch(bitmap.getConfig()) {
        case SkBitmap::kA8_Config:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, w, h, 0,
                    GL_ALPHA, GL_UNSIGNED_BYTE, p);
            break;
        case SkBitmap::kARGB_4444_Config:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                    GL_RGBA, GL_UNSIGNED_SHORT_4_4_4_4, p);
            break;
        case SkBitmap::kARGB_8888_Config:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, p);
            break;
        case SkBitmap::kRGB_565_Config:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0,
                    GL_RGB, GL_UNSIGNED_SHORT_5_6_5, p);
            break;
        default:
            break;
    }

    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return NO_ERROR;
}

status_t BootAnimation::readyToRun()
{
    mAssets.addDefaultAssets();

    DisplayInfo dinfo;
    status_t status = session()->getDisplayInfo(0, &dinfo);
    if (status)
        return -1;

    // create the native surface
    sp<Surface> s = session()->createSurface(getpid(), 0, 
            dinfo.w, dinfo.h, PIXEL_FORMAT_RGB_565);
    session()->openTransaction();
    s->setLayer(0x40000000);
    session()->closeTransaction();

    // initialize opengl and egl
    const EGLint attribs[] = {
            EGL_RED_SIZE,       5,
            EGL_GREEN_SIZE,     6,
            EGL_BLUE_SIZE,      5,
            EGL_DEPTH_SIZE,     0,
            EGL_NONE
    };
    EGLint w, h, dummy;
    EGLint numConfigs;
    EGLConfig config;
    EGLSurface surface;
    EGLContext context;
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, NULL, NULL);
    eglChooseConfig(display, attribs, &config, 1, &numConfigs);

    surface = eglCreateWindowSurface(
            display, config, new EGLNativeWindowSurface(s), NULL);
    
    context = eglCreateContext(display, config, NULL, NULL);
    eglQuerySurface(display, surface, EGL_WIDTH, &w);
    eglQuerySurface(display, surface, EGL_HEIGHT, &h);
    eglMakeCurrent(display, surface, surface, context);
    mDisplay = display;
    mContext = context;
    mSurface = surface;
    mWidth = w;
    mHeight= h;
    mFlingerSurface = s;

    // initialize GL
    glShadeModel(GL_FLAT);
    glEnable(GL_DITHER);
    glEnable(GL_TEXTURE_2D);
    glEnable(GL_SCISSOR_TEST);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    return NO_ERROR;
}

void BootAnimation::requestExit()
{
    mBarrier.open();
    Thread::requestExit();
}

bool BootAnimation::threadLoop()
{
    bool r = android();
    eglMakeCurrent(mDisplay, 0, 0, 0);
    eglDestroyContext(mDisplay, mContext);    
    eglDestroySurface(mDisplay, mSurface);
    eglTerminate(mDisplay);
    return r;
}


bool BootAnimation::android()
{
    initTexture(&mAndroid[0], mAssets, "images/android_320x480.png");
    initTexture(&mAndroid[1], mAssets, "images/boot_robot.png");
    initTexture(&mAndroid[2], mAssets, "images/boot_robot_glow.png");

    // erase screen
    glDisable(GL_SCISSOR_TEST);
    glBindTexture(GL_TEXTURE_2D, mAndroid[0].name);

    // clear screen
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(mDisplay, mSurface);

    // wait ~1s
    usleep(800000);

    // fade in
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
    const int steps = 8;
    for (int i=1 ; i<steps ; i++) {
        float fade = i / float(steps);
        glColor4f(1, 1, 1, fade*fade);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawTexiOES(0, 0, 0, mAndroid[0].w, mAndroid[0].h);
        eglSwapBuffers(mDisplay, mSurface);
    }

    // draw last frame
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glDisable(GL_BLEND);
    glDrawTexiOES(0, 0, 0, mAndroid[0].w, mAndroid[0].h);
    eglSwapBuffers(mDisplay, mSurface);
    
    
    // update rect for the robot
    const int x = mWidth - mAndroid[1].w - 33;
    const int y = (mHeight - mAndroid[1].h)/2 - 1;
    const Rect updateRect(x, y, x+mAndroid[1].w, y+mAndroid[1].h);

    // draw and update only what we need
    eglSwapRectangleANDROID(mDisplay, mSurface,
            updateRect.left, updateRect.top, 
            updateRect.width(), updateRect.height());

    glEnable(GL_SCISSOR_TEST);
    glScissor(updateRect.left, mHeight-updateRect.bottom,
            updateRect.width(), updateRect.height()); 

    const nsecs_t startTime = systemTime();
    do
    {
        // glow speed and shape
        nsecs_t time = systemTime() - startTime;
        float t = ((4.0f/(360.0f*us2ns(16667))) * time);
        t = t - floorf(t);
        const float fade = 0.5f + 0.5f*sinf(t * 2*M_PI);

        // fade the glow in and out
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[2].name);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);
        glColor4f(fade, fade, fade, fade);
        glDrawTexiOES(updateRect.left, mHeight-updateRect.bottom, 0,
                updateRect.width(), updateRect.height());

        // draw the robot
        glEnable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[1].name);
        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
        glDrawTexiOES(updateRect.left, mHeight-updateRect.bottom, 0,
                updateRect.width(), updateRect.height());

        // make sure sleep a lot to not take too much CPU away from 
        // the boot process. With this "glow" animation there is no
        // visible difference. 
        usleep(16667*4);

        eglSwapBuffers(mDisplay, mSurface);
    } while (!exitPending());
    
    
    glDeleteTextures(1, &mAndroid[0].name);
    glDeleteTextures(1, &mAndroid[1].name);
    glDeleteTextures(1, &mAndroid[2].name);
    return false;
}


bool BootAnimation::cylon()
{
    // initialize the textures...
    initTexture(&mLeftTrail,  mAssets, "images/cylon_left.png");
    initTexture(&mRightTrail, mAssets, "images/cylon_right.png");
    initTexture(&mBrightSpot, mAssets, "images/cylon_dot.png");

    int w = mWidth;
    int h = mHeight;

    const Point c(w/2 , h/2);
    const GLint amplitude = 60;
    const int scx = c.x - amplitude - mBrightSpot.w/2;
    const int scy = c.y - mBrightSpot.h/2;
    const int scw = amplitude*2 + mBrightSpot.w;
    const int sch = mBrightSpot.h;
    const Rect updateRect(scx, h-scy-sch, scx+scw, h-scy);

    // erase screen
    glDisable(GL_SCISSOR_TEST);
    glClear(GL_COLOR_BUFFER_BIT);

    eglSwapBuffers(mDisplay, mSurface);

    glClear(GL_COLOR_BUFFER_BIT);

    eglSwapRectangleANDROID(mDisplay, mSurface,
            updateRect.left, updateRect.top, 
            updateRect.width(), updateRect.height());

    glEnable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);


    // clear the screen to white
    Point p;
    float t = 0;
    float alpha = 1.0f;
    const nsecs_t startTime = systemTime();
    nsecs_t fadeTime = 0;
    
    do
    {
        // Set scissor in interesting area
        glScissor(scx, scy, scw, sch); 

        // erase screen
        glClear(GL_COLOR_BUFFER_BIT);


        // compute wave
        const float a = (t * 2*M_PI) - M_PI/2;
        const float sn = sinf(a);
        const float cs = cosf(a);
        GLint x = GLint(amplitude * sn);
        float derivative = cs;

        glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);

        if (derivative > 0) {
            // vanishing trail...
            p.x = (-amplitude + c.x) - mBrightSpot.w/2;
            p.y = c.y-mLeftTrail.h/2;
            float fade = 2.0f*(0.5f-t);
            //fade *= fade;
            glColor4f(fade, fade, fade, fade);
            glBindTexture(GL_TEXTURE_2D, mLeftTrail.name);
            glDrawTexiOES(p.x, p.y, 0, mLeftTrail.w, mLeftTrail.h);

            // trail...
            p.x = (x + c.x) - (mRightTrail.w + mBrightSpot.w/2) + 16;
            p.y = c.y-mRightTrail.h/2;
            fade = t<0.25f ? t*4.0f : 1.0f;
            fade *= fade;
            glColor4f(fade, fade, fade, fade);
            glBindTexture(GL_TEXTURE_2D, mRightTrail.name);
            glDrawTexiOES(p.x, p.y, 0, mRightTrail.w, mRightTrail.h);
        } else { 
            // vanishing trail..
            p.x = (amplitude + c.x) - (mRightTrail.w + mBrightSpot.w/2) + 16;
            p.y = c.y-mRightTrail.h/2;
            float fade = 2.0f*(0.5f-(t-0.5f));
            //fade *= fade;
            glColor4f(fade, fade, fade, fade);
            glBindTexture(GL_TEXTURE_2D, mRightTrail.name);
            glDrawTexiOES(p.x, p.y, 0, mRightTrail.w, mRightTrail.h);

            // trail...
            p.x = (x + c.x) - mBrightSpot.w/2;
            p.y = c.y-mLeftTrail.h/2;
            fade = t<0.5f+0.25f ? (t-0.5f)*4.0f : 1.0f;
            fade *= fade;
            glColor4f(fade, fade, fade, fade);
            glBindTexture(GL_TEXTURE_2D, mLeftTrail.name);
            glDrawTexiOES(p.x, p.y, 0, mLeftTrail.w, mLeftTrail.h);
        }

        const Point p( x + c.x-mBrightSpot.w/2, c.y-mBrightSpot.h/2 );
        glBindTexture(GL_TEXTURE_2D, mBrightSpot.name);
        glColor4f(1,0.5,0.5,1);
        glDrawTexiOES(p.x, p.y, 0, mBrightSpot.w, mBrightSpot.h);

        // update animation
        nsecs_t time = systemTime() - startTime;
        t = ((4.0f/(360.0f*us2ns(16667))) * time);
        t = t - floorf(t);

        eglSwapBuffers(mDisplay, mSurface);

        if (exitPending()) {
            if (fadeTime == 0) {
                fadeTime = time;
            }
            time -= fadeTime;
            alpha = 1.0f - ((float(time) * 6.0f) / float(s2ns(1)));

            session()->openTransaction();
            mFlingerSurface->setAlpha(alpha*alpha);
            session()->closeTransaction();
        }
    } while (alpha > 0);

    // cleanup
    glFinish();
    glDeleteTextures(1, &mLeftTrail.name);
    glDeleteTextures(1, &mRightTrail.name);
    glDeleteTextures(1, &mBrightSpot.name);
    return false;
}

// ---------------------------------------------------------------------------

}; // namespace android
