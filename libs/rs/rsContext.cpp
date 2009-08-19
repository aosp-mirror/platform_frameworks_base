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
#include <ui/FramebufferNativeWindow.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

using namespace android;
using namespace android::renderscript;

Context * Context::gCon = NULL;
pthread_key_t Context::gThreadTLSKey = 0;

void Context::initEGL()
{
    mNumConfigs = -1;

    EGLint s_configAttribs[] = {
         EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
#if 1
         EGL_RED_SIZE,       8,
         EGL_GREEN_SIZE,     8,
         EGL_BLUE_SIZE,      8,
         EGL_ALPHA_SIZE,     8,
#else
         EGL_RED_SIZE,       5,
         EGL_GREEN_SIZE,     6,
         EGL_BLUE_SIZE,      5,
#endif
         EGL_DEPTH_SIZE,     16,
         EGL_NONE
     };

     mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
     eglInitialize(mDisplay, &mMajorVersion, &mMinorVersion);
     eglChooseConfig(mDisplay, s_configAttribs, &mConfig, 1, &mNumConfigs);

     if (mWndSurface) {
         mSurface = eglCreateWindowSurface(mDisplay, mConfig, mWndSurface,
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

bool Context::runScript(Script *s, uint32_t launchID)
{
    ObjectBaseRef<ProgramFragment> frag(mFragment);
    ObjectBaseRef<ProgramVertex> vtx(mVertex);
    ObjectBaseRef<ProgramFragmentStore> store(mFragmentStore);

    bool ret = s->run(this, launchID);

    mFragment.set(frag);
    mVertex.set(vtx);
    mFragmentStore.set(store);
    return ret;
}


bool Context::runRootScript()
{
    rsAssert(mRootScript->mEnviroment.mIsRoot);

    //glColor4f(1,1,1,1);
    //glEnable(GL_LIGHT0);
    glViewport(0, 0, mWidth, mHeight);

    glDepthMask(GL_TRUE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    glClearColor(mRootScript->mEnviroment.mClearColor[0],
                 mRootScript->mEnviroment.mClearColor[1],
                 mRootScript->mEnviroment.mClearColor[2],
                 mRootScript->mEnviroment.mClearColor[3]);
    glClearDepthf(mRootScript->mEnviroment.mClearDepth);
    glClear(GL_COLOR_BUFFER_BIT);
    glClear(GL_DEPTH_BUFFER_BIT);

#if RS_LOG_TIMES
    struct timespec startTime;
    clock_gettime(CLOCK_MONOTONIC, &startTime);
#endif
    bool ret = runScript(mRootScript.get(), 0);

#if RS_LOG_TIMES
    struct timespec endTime;
    clock_gettime(CLOCK_MONOTONIC, &endTime);

    uint64_t t1 = endTime.tv_nsec + ((uint64_t)endTime.tv_sec * 1000 * 1000 * 1000);
    uint64_t t2 = startTime.tv_nsec + ((uint64_t)startTime.tv_sec * 1000 * 1000 * 1000);
    int t3 = (int)((t1 - t2) / 1000 / 1000);
    LOGE("times  %i", t3);
#endif

    return ret;
}

void Context::setupCheck()
{
    if (mFragmentStore.get()) {
        mFragmentStore->setupGL(&mStateFragmentStore);
    }
    if (mFragment.get()) {
        mFragment->setupGL(&mStateFragment);
    }
    if (mVertex.get()) {
        mVertex->setupGL(&mStateVertex);
    }

}


void * Context::threadProc(void *vrsc)
{
     Context *rsc = static_cast<Context *>(vrsc);

     rsc->initEGL();

     ScriptTLSStruct *tlsStruct = new ScriptTLSStruct;
     if (!tlsStruct) {
         LOGE("Error allocating tls storage");
         return NULL;
     }
     tlsStruct->mContext = rsc;
     tlsStruct->mScript = NULL;
     int status = pthread_setspecific(rsc->gThreadTLSKey, tlsStruct);
     if (status) {
         LOGE("pthread_setspecific %i", status);
     }

     rsc->mStateVertex.init(rsc, rsc->mWidth, rsc->mHeight);
     rsc->setVertex(NULL);
     rsc->mStateFragment.init(rsc, rsc->mWidth, rsc->mHeight);
     rsc->setFragment(NULL);
     rsc->mStateFragmentStore.init(rsc, rsc->mWidth, rsc->mHeight);
     rsc->setFragmentStore(NULL);

     rsc->mRunning = true;
     bool mDraw = true;
     while (!rsc->mExit) {
         mDraw |= rsc->mIO.playCoreCommands(rsc, !mDraw);
         mDraw &= (rsc->mRootScript.get() != NULL);

         if (mDraw) {
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

    // see comment in header
    gCon = this;

    int status;
    pthread_attr_t threadAttr;

    status = pthread_key_create(&gThreadTLSKey, NULL);
    if (status) {
        LOGE("Failed to init thread tls key.");
        return;
    }

    status = pthread_attr_init(&threadAttr);
    if (status) {
        LOGE("Failed to init thread attribute.");
        return;
    }

    sched_param sparam;
    sparam.sched_priority = ANDROID_PRIORITY_DISPLAY;
    pthread_attr_setschedparam(&threadAttr, &sparam);

    mWndSurface = sur;

    LOGV("RS Launching thread");
    status = pthread_create(&mThreadId, &threadAttr, threadProc, this);
    if (status) {
        LOGE("Failed to start rs context thread.");
    }

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
        pthread_key_delete(gThreadTLSKey);
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
    if (pfs == NULL) {
        mFragmentStore.set(mStateFragmentStore.mDefault);
    } else {
        mFragmentStore.set(pfs);
    }
}

void Context::setFragment(ProgramFragment *pf)
{
    if (pf == NULL) {
        mFragment.set(mStateFragment.mDefault);
    } else {
        mFragment.set(pf);
    }
}

void Context::allocationCheck(const Allocation *a)
{
    mVertex->checkUpdatedAllocation(a);
    mFragment->checkUpdatedAllocation(a);
    mFragmentStore->checkUpdatedAllocation(a);
}

void Context::setVertex(ProgramVertex *pv)
{
    if (pv == NULL) {
        mVertex.set(mStateVertex.mDefault);
    } else {
        mVertex.set(pv);
    }
}

void Context::assignName(ObjectBase *obj, const char *name, uint32_t len)
{
    rsAssert(!obj->getName());
    obj->setName(name, len);
    mNames.add(obj);
}

void Context::removeName(ObjectBase *obj)
{
    for(size_t ct=0; ct < mNames.size(); ct++) {
        if (obj == mNames[ct]) {
            mNames.removeAt(ct);
            return;
        }
    }
}

ObjectBase * Context::lookupName(const char *name) const
{
    for(size_t ct=0; ct < mNames.size(); ct++) {
        if (!strcmp(name, mNames[ct]->getName())) {
            return mNames[ct];
        }
    }
    return NULL;
}

void Context::appendNameDefines(String8 *str) const
{
    char buf[256];
    for (size_t ct=0; ct < mNames.size(); ct++) {
        str->append("#define NAMED_");
        str->append(mNames[ct]->getName());
        str->append(" ");
        sprintf(buf, "%i\n", (int)mNames[ct]);
        str->append(buf);
    }
}

void Context::appendVarDefines(String8 *str) const
{
    char buf[256];
    for (size_t ct=0; ct < mInt32Defines.size(); ct++) {
        str->append("#define ");
        str->append(mInt32Defines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%i\n", (int)mInt32Defines.valueAt(ct));
        str->append(buf);

    }
    for (size_t ct=0; ct < mFloatDefines.size(); ct++) {
        str->append("#define ");
        str->append(mFloatDefines.keyAt(ct));
        str->append(" ");
        sprintf(buf, "%ff\n", mFloatDefines.valueAt(ct));
        str->append(buf);
    }
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

void rsi_AssignName(Context *rsc, void * obj, const char *name, uint32_t len)
{
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    rsc->assignName(ob, name, len);
}

void rsi_ObjDestroy(Context *rsc, void *obj)
{
    ObjectBase *ob = static_cast<ObjectBase *>(obj);
    rsc->removeName(ob);
    ob->decRef();
}

void rsi_ContextSetDefineF(Context *rsc, const char* name, float value)
{
    rsc->addInt32Define(name, value);
}

void rsi_ContextSetDefineI32(Context *rsc, const char* name, int32_t value)
{
    rsc->addFloatDefine(name, value);
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

