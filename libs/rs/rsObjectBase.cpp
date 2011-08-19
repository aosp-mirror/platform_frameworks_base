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

pthread_mutex_t ObjectBase::gObjectInitMutex = PTHREAD_MUTEX_INITIALIZER;

ObjectBase::ObjectBase(Context *rsc) {
    mUserRefCount = 0;
    mSysRefCount = 0;
    mRSC = rsc;
    mNext = NULL;
    mPrev = NULL;

#if RS_OBJECT_DEBUG
    mStack.update(2);
#endif

    rsAssert(rsc);
    add();
    //LOGV("ObjectBase %p con", this);
}

ObjectBase::~ObjectBase() {
    //LOGV("~ObjectBase %p  ref %i,%i", this, mUserRefCount, mSysRefCount);
#if RS_OBJECT_DEBUG
    mStack.dump();
#endif

    if (mPrev || mNext) {
        // While the normal practice is to call remove before we call
        // delete.  Its possible for objects without a re-use list
        // for avoiding duplication to be created on the stack.  In those
        // cases we need to remove ourself here.
        asyncLock();
        remove();
        asyncUnlock();
    }

    rsAssert(!mUserRefCount);
    rsAssert(!mSysRefCount);
}

void ObjectBase::dumpLOGV(const char *op) const {
    if (mName.size()) {
        LOGV("%s RSobj %p, name %s, refs %i,%i  links %p,%p,%p",
             op, this, mName.string(), mUserRefCount, mSysRefCount, mNext, mPrev, mRSC);
    } else {
        LOGV("%s RSobj %p, no-name, refs %i,%i  links %p,%p,%p",
             op, this, mUserRefCount, mSysRefCount, mNext, mPrev, mRSC);
    }
}

void ObjectBase::incUserRef() const {
    android_atomic_inc(&mUserRefCount);
    //LOGV("ObjectBase %p incU ref %i, %i", this, mUserRefCount, mSysRefCount);
}

void ObjectBase::incSysRef() const {
    android_atomic_inc(&mSysRefCount);
    //LOGV("ObjectBase %p incS ref %i, %i", this, mUserRefCount, mSysRefCount);
}

void ObjectBase::preDestroy() const {
}

bool ObjectBase::freeChildren() {
    return false;
}

bool ObjectBase::checkDelete(const ObjectBase *ref) {
    if (!ref) {
        return false;
    }

    asyncLock();
    // This lock protects us against the non-RS threads changing
    // the ref counts.  At this point we should be the only thread
    // working on them.
    if (ref->mUserRefCount || ref->mSysRefCount) {
        asyncUnlock();
        return false;
    }

    ref->remove();
    // At this point we can unlock because there should be no possible way
    // for another thread to reference this object.
    ref->preDestroy();
    asyncUnlock();
    delete ref;
    return true;
}

bool ObjectBase::decUserRef() const {
    rsAssert(mUserRefCount > 0);
#if RS_OBJECT_DEBUG
    LOGV("ObjectBase %p decU ref %i, %i", this, mUserRefCount, mSysRefCount);
    if (mUserRefCount <= 0) {
        mStack.dump();
    }
#endif


    if ((android_atomic_dec(&mUserRefCount) <= 1) &&
        (android_atomic_acquire_load(&mSysRefCount) <= 0)) {
        return checkDelete(this);
    }
    return false;
}

bool ObjectBase::zeroUserRef() const {
    //LOGV("ObjectBase %p zeroU ref %i, %i", this, mUserRefCount, mSysRefCount);
    android_atomic_acquire_store(0, &mUserRefCount);
    if (android_atomic_acquire_load(&mSysRefCount) <= 0) {
        return checkDelete(this);
    }
    return false;
}

bool ObjectBase::decSysRef() const {
    //LOGV("ObjectBase %p decS ref %i, %i", this, mUserRefCount, mSysRefCount);
    rsAssert(mSysRefCount > 0);
    if ((android_atomic_dec(&mSysRefCount) <= 1) &&
        (android_atomic_acquire_load(&mUserRefCount) <= 0)) {
        return checkDelete(this);
    }
    return false;
}

void ObjectBase::setName(const char *name) {
    mName.setTo(name);
}

void ObjectBase::setName(const char *name, uint32_t len) {
    mName.setTo(name, len);
}

void ObjectBase::asyncLock() {
    pthread_mutex_lock(&gObjectInitMutex);
}

void ObjectBase::asyncUnlock() {
    pthread_mutex_unlock(&gObjectInitMutex);
}

void ObjectBase::add() const {
    asyncLock();

    rsAssert(!mNext);
    rsAssert(!mPrev);
    //LOGV("calling add  rsc %p", mRSC);
    mNext = mRSC->mObjHead;
    if (mRSC->mObjHead) {
        mRSC->mObjHead->mPrev = this;
    }
    mRSC->mObjHead = this;

    asyncUnlock();
}

void ObjectBase::remove() const {
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

void ObjectBase::zeroAllUserRef(Context *rsc) {
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

void ObjectBase::freeAllChildren(Context *rsc) {
    if (rsc->props.mLogObjects) {
        LOGV("Forcing release of all child objects.");
    }

    // This operation can be slow, only to be called during context cleanup.
    ObjectBase * o = (ObjectBase *)rsc->mObjHead;
    while (o) {
        if (o->freeChildren()) {
            // deleted ref to self and possibly others, restart from head.
            o = (ObjectBase *)rsc->mObjHead;
        } else {
            o = (ObjectBase *)o->mNext;
        }
    }

    if (rsc->props.mLogObjects) {
        LOGV("Objects remaining.");
        dumpAll(rsc);
    }
}

void ObjectBase::dumpAll(Context *rsc) {
    asyncLock();

    LOGV("Dumping all objects");
    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        LOGV(" Object %p", o);
        o->dumpLOGV("  ");
        o = o->mNext;
    }

    asyncUnlock();
}

bool ObjectBase::isValid(const Context *rsc, const ObjectBase *obj) {
    asyncLock();

    const ObjectBase * o = rsc->mObjHead;
    while (o) {
        if (o == obj) {
            asyncUnlock();
            return true;
        }
        o = o->mNext;
    }
    asyncUnlock();
    return false;
}

