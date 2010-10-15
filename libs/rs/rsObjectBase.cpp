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

#include "rsObjectBase.h"

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include "rsContext.h"
#else
#include "rsContextHostStub.h"
#endif

using namespace android;
using namespace android::renderscript;

pthread_mutex_t ObjectBase::gObjectInitMutex = PTHREAD_MUTEX_INITIALIZER;

ObjectBase::ObjectBase(Context *rsc)
{
    mUserRefCount = 0;
    mSysRefCount = 0;
    mRSC = rsc;
    mNext = NULL;
    mPrev = NULL;
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;

    rsAssert(rsc);
    add();
}

ObjectBase::~ObjectBase()
{
    //LOGV("~ObjectBase %p  ref %i,%i", this, mUserRefCount, mSysRefCount);
    rsAssert(!mUserRefCount);
    rsAssert(!mSysRefCount);
    remove();
}

void ObjectBase::dumpLOGV(const char *op) const
{
    if (mName.size()) {
        LOGV("%s RSobj %p, name %s, refs %i,%i  from %s,%i links %p,%p,%p",
             op, this, mName.string(), mUserRefCount, mSysRefCount, mAllocFile, mAllocLine, mNext, mPrev, mRSC);
    } else {
        LOGV("%s RSobj %p, no-name, refs %i,%i  from %s,%i links %p,%p,%p",
             op, this, mUserRefCount, mSysRefCount, mAllocFile, mAllocLine, mNext, mPrev, mRSC);
    }
}

void ObjectBase::incUserRef() const
{
    lockUserRef();
    mUserRefCount++;
    unlockUserRef();
    //LOGV("ObjectBase %p inc ref %i", this, mUserRefCount);
}

void ObjectBase::prelockedIncUserRef() const
{
    mUserRefCount++;
}

void ObjectBase::incSysRef() const
{
    mSysRefCount ++;
    //LOGV("ObjectBase %p inc ref %i", this, mSysRefCount);
}

bool ObjectBase::checkDelete() const
{
    if (!(mSysRefCount | mUserRefCount)) {
        lockUserRef();

        // Recheck the user ref count since it can be incremented from other threads.
        if (mUserRefCount) {
            unlockUserRef();
            return false;
        }

        if (mRSC && mRSC->props.mLogObjects) {
            dumpLOGV("checkDelete");
        }
        delete this;

        unlockUserRef();
        return true;
    }
    return false;
}

bool ObjectBase::decUserRef() const
{
    lockUserRef();
    rsAssert(mUserRefCount > 0);
    //dumpLOGV("decUserRef");
    mUserRefCount--;
    unlockUserRef();
    bool ret = checkDelete();
    return ret;
}

bool ObjectBase::zeroUserRef() const
{
    lockUserRef();
    // This can only happen during cleanup and is therefore
    // thread safe.
    mUserRefCount = 0;
    //dumpLOGV("zeroUserRef");
    unlockUserRef();
    bool ret = checkDelete();
    return ret;
}

bool ObjectBase::decSysRef() const
{
    rsAssert(mSysRefCount > 0);
    mSysRefCount --;
    //dumpLOGV("decSysRef");
    return checkDelete();
}

void ObjectBase::setName(const char *name)
{
    mName.setTo(name);
}

void ObjectBase::setName(const char *name, uint32_t len)
{
    mName.setTo(name, len);
}

void ObjectBase::lockUserRef()
{
    pthread_mutex_lock(&gObjectInitMutex);
}

void ObjectBase::unlockUserRef()
{
    pthread_mutex_unlock(&gObjectInitMutex);
}

void ObjectBase::add() const
{
    pthread_mutex_lock(&gObjectInitMutex);

    rsAssert(!mNext);
    rsAssert(!mPrev);
    //LOGV("calling add  rsc %p", mRSC);
    mNext = mRSC->mObjHead;
    if (mRSC->mObjHead) {
        mRSC->mObjHead->mPrev = this;
    }
    mRSC->mObjHead = this;

    pthread_mutex_unlock(&gObjectInitMutex);
}

void ObjectBase::remove() const
{
    // Should be within gObjectInitMutex lock
    // lock will be from checkDelete a few levels up in the stack.

    //LOGV("calling remove  rsc %p", mRSC);
    if (!mRSC) {
        rsAssert(!mPrev);
        rsAssert(!mNext);
        return;
    }

    if (mRSC->mObjHead == this) {
        mRSC->mObjHead = mNext;
    }
    if (mPrev) {
        mPrev->mNext = mNext;
    }
    if (mNext) {
        mNext->mPrev = mPrev;
    }
    mPrev = NULL;
    mNext = NULL;
}

void ObjectBase::zeroAllUserRef(Context *rsc)
{
    lockUserRef();

    if (rsc->props.mLogObjects) {
        LOGV("Forcing release of all outstanding user refs.");
    }

    // This operation can be slow, only to be called during context cleanup.
    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        //LOGE("o %p", o);
        if (o->zeroUserRef()) {
            // deleted the object and possibly others, restart from head.
            o = rsc->mObjHead;
            //LOGE("o head %p", o);
        } else {
            o = o->mNext;
            //LOGE("o next %p", o);
        }
    }

    if (rsc->props.mLogObjects) {
        LOGV("Objects remaining.");
        dumpAll(rsc);
    }

    unlockUserRef();
}

void ObjectBase::dumpAll(Context *rsc)
{
    lockUserRef();

    LOGV("Dumping all objects");
    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        LOGV(" Object %p", o);
        o->dumpLOGV("  ");
        o = o->mNext;
    }

    unlockUserRef();
}

bool ObjectBase::isValid(const Context *rsc, const ObjectBase *obj)
{
    lockUserRef();

    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        if (o == obj) {
            unlockUserRef();
            return true;
        }
        o = o->mNext;
    }
    unlockUserRef();
    return false;
}

