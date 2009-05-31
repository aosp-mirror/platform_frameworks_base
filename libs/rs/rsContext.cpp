/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsDevice.h"
#include "rsContext.h"
#include "rsThreadIO.h"


using namespace android;
using namespace android::renderscript;

Context * Context::gCon = NULL;

void Context::initEGL()
{
    mNumConfigs = -1;

    EGLint s_configAttribs[] = {
         EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
         EGL_RED_SIZE,       5,
         EGL_GREEN_SIZE,     6,
         EGL_BLUE_SIZE,      5,
         EGL_DEPTH_SIZE,     16,
         EGL_NONE
     };

     LOGE("EGL 1");
     mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     LOGE("EGL 2  %p", mDisplay);
     eglInitialize(mDisplay, &mMajorVersion, &mMinorVersion);
     LOGE("EGL 3  %i  %i", mMajorVersion, mMinorVersion);
     eglChooseConfig(mDisplay, s_configAttribs, &mConfig, 1, &mNumConfigs);
     LOGE("EGL 4  %p", mConfig);

     if (mWndSurface) {
         mSurface = eglCreateWindowSurface(mDisplay, mConfig,
                 new EGLNativeWindowSurface(mWndSurface),
                 NULL);
     } else {
         mSurface = eglCreateWindowSurface(mDisplay, mConfig,
                 android_createDisplaySurface(),
                 NULL);
     }

     LOGE("EGL 5");
     mContext = eglCreateContext(mDisplay, mConfig, NULL, NULL);
     eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);   
     eglQuerySurface(mDisplay, mSurface, EGL_WIDTH, &mWidth);
     eglQuerySurface(mDisplay, mSurface, EGL_HEIGHT, &mHeight);
     LOGE("EGL 9");

}

void Context::runRootScript()
{
    rsAssert(mRootScript->mIsRoot);

    glViewport(0, 0, 320, 480);
    float aspectH = 480.f / 320.f;

    if(mRootScript->mIsOrtho) {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrthof(0, 320,  480, 0,  0, 1);
        glMatrixMode(GL_MODELVIEW);
    } else {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glFrustumf(-1, 1,  -aspectH, aspectH,  1, 100);
        glRotatef(-90, 0,0,1);
        glTranslatef(0,  0,  -3);
        glMatrixMode(GL_MODELVIEW);
    }

    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();

    glDepthMask(GL_TRUE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glClearColor(mRootScript->mClearColor[0], 
                 mRootScript->mClearColor[1],
                 mRootScript->mClearColor[2],
                 mRootScript->mClearColor[3]);
    glClearDepthf(mRootScript->mClearDepth);
    glClear(GL_COLOR_BUFFER_BIT);
    glClear(GL_DEPTH_BUFFER_BIT);

    mRootScript->run(this, 0);

}

void Context::setupCheck()
{
    if (mFragmentStore.get()) {
        mFragmentStore->setupGL();
    }
    if (mFragment.get()) {
        mFragment->setupGL();
    }
    if (mVertex.get()) {
        mVertex->setupGL();
    }

}


void * Context::threadProc(void *vrsc)
{
     Context *rsc = static_cast<Context *>(vrsc);

     LOGE("TP 1");
     gIO = new ThreadIO();

     rsc->mServerCommands.init(128);
     rsc->mServerReturns.init(128);

     rsc->initEGL();

     LOGE("TP 2");

     rsc->mRunning = true;
     while (!rsc->mExit) {
         gIO->playCoreCommands(rsc);

         if (!rsc->mRootScript.get()) {
             continue;
         }


         glColor4f(1,1,1,1);
         glEnable(GL_LIGHT0);

         if (rsc->mRootScript.get()) {
             rsc->runRootScript();
         }

         eglSwapBuffers(rsc->mDisplay, rsc->mSurface);

         usleep(10000);
     }

     LOGE("TP 6");
     glClearColor(0,0,0,0);
     glClear(GL_COLOR_BUFFER_BIT);
     eglSwapBuffers(rsc->mDisplay, rsc->mSurface);
     eglTerminate(rsc->mDisplay);

     LOGE("TP 7");

     return NULL;
}

Context::Context(Device *dev, Surface *sur)
{
    LOGE("CC 1");
    dev->addContext(this);
    mDev = dev;
    mRunning = false;
    mExit = false;

    mServerCommands.init(256);
    mServerReturns.init(256);

    // see comment in header
    gCon = this;

    LOGE("CC 2");
    int status = pthread_create(&mThreadId, NULL, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
    }

    LOGE("CC 3");
    mWndSurface = sur;
    while(!mRunning) {
        sleep(1);
    }
    LOGE("CC 4");



}

Context::~Context()
{
    mExit = true;
    void *res;

    LOGE("DES 1");
    int status = pthread_join(mThreadId, &res);
    LOGE("DES 2");

    if (mDev) {
        mDev->removeContext(this);
    }
    LOGE("DES 3");
}

void Context::swapBuffers()
{
    eglSwapBuffers(mDisplay, mSurface);
}

void rsContextSwap(RsContext vrsc)
{
    Context *rsc = static_cast<Context *>(vrsc);
    rsc->swapBuffers();
}

void Context::setRootScript(Script *s)
{
    mRootScript.set(s);
}

void Context::setFragmentStore(ProgramFragmentStore *pfs)
{
    mFragmentStore.set(pfs);
    pfs->setupGL();
}

void Context::setFragment(ProgramFragment *pf)
{
    mFragment.set(pf);
    pf->setupGL();
}

void Context::setVertex(ProgramVertex *pv)
{
    mVertex.set(pv);
    pv->setupGL();
}

///////////////////////////////////////////////////////////////////////////////////////////
// 

namespace android {
namespace renderscript {


void rsi_ContextBindRootScript(Context *rsc, RsScript vs)
{
    Script *s = static_cast<Script *>(vs);
    rsc->setRootScript(s);
}

void rsi_ContextBindSampler(Context *rsc, uint32_t slot, RsSampler vs)
{
    Sampler *s = static_cast<Sampler *>(vs);

    if (slot > RS_MAX_SAMPLER_SLOT) {
        LOGE("Invalid sampler slot");
        return;
    }

    s->bindToContext(&rsc->mStateSampler, slot);
}

void rsi_ContextBindProgramFragmentStore(Context *rsc, RsProgramFragmentStore vpfs)
{
    ProgramFragmentStore *pfs = static_cast<ProgramFragmentStore *>(vpfs);
    rsc->setFragmentStore(pfs);
}

void rsi_ContextBindProgramFragment(Context *rsc, RsProgramFragment vpf)
{
    ProgramFragment *pf = static_cast<ProgramFragment *>(vpf);
    rsc->setFragment(pf);
}

void rsi_ContextBindProgramVertex(Context *rsc, RsProgramVertex vpv)
{
    ProgramVertex *pv = static_cast<ProgramVertex *>(vpv);
    rsc->setVertex(pv);
}



}
}


RsContext rsContextCreate(RsDevice vdev, void *sur, uint32_t version)
{
    Device * dev = static_cast<Device *>(vdev);
    Context *rsc = new Context(dev, (Surface *)sur);
    return rsc;
}

void rsContextDestroy(RsContext vrsc)
{
    Context * rsc = static_cast<Context *>(vrsc);
    delete rsc;
}

