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

     mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     eglInitialize(mDisplay, &mMajorVersion, &mMinorVersion);
     eglChooseConfig(mDisplay, s_configAttribs, &mConfig, 1, &mNumConfigs);

     if (mWndSurface) {
         mSurface = eglCreateWindowSurface(mDisplay, mConfig,
                 new EGLNativeWindowSurface(mWndSurface),
                 NULL);
     } else {
         mSurface = eglCreateWindowSurface(mDisplay, mConfig,
                 android_createDisplaySurface(),
                 NULL);
     }

     mContext = eglCreateContext(mDisplay, mConfig, NULL, NULL);
     eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
     eglQuerySurface(mDisplay, mSurface, EGL_WIDTH, &mWidth);
     eglQuerySurface(mDisplay, mSurface, EGL_HEIGHT, &mHeight);
}

bool Context::runRootScript()
{
    rsAssert(mRootScript->mIsRoot);

    glColor4f(1,1,1,1);
    glEnable(GL_LIGHT0);
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

    return mRootScript->run(this, 0);
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

     gIO = new ThreadIO();
     rsc->mServerCommands.init(128);
     rsc->mServerReturns.init(128);

     rsc->initEGL();
     rsc->mRunning = true;
     bool mDraw = true;
     while (!rsc->mExit) {
         mDraw |= gIO->playCoreCommands(rsc);

         if (!mDraw || !rsc->mRootScript.get()) {
             usleep(10000);
             continue;
         }

         if (rsc->mRootScript.get()) {
             mDraw = rsc->runRootScript();
             eglSwapBuffers(rsc->mDisplay, rsc->mSurface);
         }
     }

     glClearColor(0,0,0,0);
     glClear(GL_COLOR_BUFFER_BIT);
     eglSwapBuffers(rsc->mDisplay, rsc->mSurface);
     eglTerminate(rsc->mDisplay);
     return NULL;
}

Context::Context(Device *dev, Surface *sur)
{
    dev->addContext(this);
    mDev = dev;
    mRunning = false;
    mExit = false;

    mServerCommands.init(256);
    mServerReturns.init(256);

    // see comment in header
    gCon = this;

    int status;
    pthread_attr_t threadAttr;

    status = pthread_attr_init(&threadAttr);
    if (status) {
        LOGE("Failed to init thread attribute.");
        return;
    }

    sched_param sparam;
    sparam.sched_priority = ANDROID_PRIORITY_DISPLAY;
    pthread_attr_setschedparam(&threadAttr, &sparam);

    LOGE("RS Launching thread");
    status = pthread_create(&mThreadId, &threadAttr, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
    }

    mWndSurface = sur;
    while(!mRunning) {
        sleep(1);
    }

    pthread_attr_destroy(&threadAttr);
}

Context::~Context()
{
    mExit = true;
    void *res;

    int status = pthread_join(mThreadId, &res);

    if (mDev) {
        mDev->removeContext(this);
    }
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

