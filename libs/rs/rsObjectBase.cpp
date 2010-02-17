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
#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

ObjectBase::ObjectBase(Context *rsc)
{
    mUserRefCount = 0;
    mSysRefCount = 0;
    mName = NULL;
    mRSC = NULL;
    mNext = NULL;
    mPrev = NULL;
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    setContext(rsc);
}

ObjectBase::~ObjectBase()
{
    //LOGV("~ObjectBase %p  ref %i,%i", this, mUserRefCount, mSysRefCount);
    rsAssert(!mUserRefCount);
    rsAssert(!mSysRefCount);
    remove();
    delete[] mName;
}

void ObjectBase::dumpLOGV(const char *op) const
{
    if (mName) {
        LOGV("%s RSobj %p, name %s, refs %i,%i  from %s,%i links %p,%p,%p",
             op, this, mName, mUserRefCount, mSysRefCount, mAllocFile, mAllocLine, mNext, mPrev, mRSC);
    } else {
        LOGV("%s RSobj %p, no-name, refs %i,%i  from %s,%i links %p,%p,%p",
             op, this, mUserRefCount, mSysRefCount, mAllocFile, mAllocLine, mNext, mPrev, mRSC);
    }
}

void ObjectBase::setContext(Context *rsc)
{
    if (mRSC) {
        remove();
    }
    mRSC = rsc;
    if (rsc) {
        add();
    }
}

void ObjectBase::incUserRef() const
{
    mUserRefCount ++;
    //LOGV("ObjectBase %p inc ref %i", this, mRefCount);
}

void ObjectBase::incSysRef() const
{
    mSysRefCount ++;
    //LOGV("ObjectBase %p inc ref %i", this, mRefCount);
}

bool ObjectBase::checkDelete() const
{
    if (!(mSysRefCount | mUserRefCount)) {
        if (mRSC && mRSC->props.mLogObjects) {
            dumpLOGV("checkDelete");
        }
        delete this;
        return true;
    }
    return false;
}

bool ObjectBase::decUserRef() const
{
    rsAssert(mUserRefCount > 0);
    mUserRefCount --;
    //dumpObj("decUserRef");
    return checkDelete();
}

bool ObjectBase::zeroUserRef() const
{
    mUserRefCount = 0;
    //dumpObj("zeroUserRef");
    return checkDelete();
}

bool ObjectBase::decSysRef() const
{
    rsAssert(mSysRefCount > 0);
    mSysRefCount --;
    //dumpObj("decSysRef");
    return checkDelete();
}

void ObjectBase::setName(const char *name)
{
    setName(name, strlen(name));
}

void ObjectBase::setName(const char *name, uint32_t len)
{
    delete mName;
    mName = NULL;
    if (name) {
        mName = new char[len + 1];
        memcpy(mName, name, len);
        mName[len] = 0;
    }
}

void ObjectBase::add() const
{
    rsAssert(!mNext);
    rsAssert(!mPrev);
    //LOGV("calling add  rsc %p", mRSC);
    mNext = mRSC->mObjHead;
    if (mRSC->mObjHead) {
        mRSC->mObjHead->mPrev = this;
    }
    mRSC->mObjHead = this;
}

void ObjectBase::remove() const
{
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
}

void ObjectBase::dumpAll(Context *rsc)
{
    LOGV("Dumping all objects");
    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        LOGV(" Object %p", o);
        o->dumpLOGV("  ");
        o = o->mNext;
    }
}

