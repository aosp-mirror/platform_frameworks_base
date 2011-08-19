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

#ifndef ANDROID_RS_OBJECT_BASE_H
#define ANDROID_RS_OBJECT_BASE_H

#include "rsUtils.h"

#define RS_OBJECT_DEBUG 0

#include <utils/CallStack.h>

namespace android {
namespace renderscript {

class Context;
class OStream;

// An element is a group of Components that occupies one cell in a structure.
class ObjectBase {
public:
    ObjectBase(Context *rsc);

    void incSysRef() const;
    bool decSysRef() const;

    void incUserRef() const;
    bool decUserRef() const;
    bool zeroUserRef() const;

    static bool checkDelete(const ObjectBase *);

    const char * getName() const {
        return mName.string();
    }
    void setName(const char *);
    void setName(const char *, uint32_t len);

    Context * getContext() const {return mRSC;}
    virtual bool freeChildren();

    static void zeroAllUserRef(Context *rsc);
    static void freeAllChildren(Context *rsc);
    static void dumpAll(Context *rsc);

    virtual void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const = 0;
    virtual RsA3DClassID getClassId() const = 0;

    static bool isValid(const Context *rsc, const ObjectBase *obj);

    // The async lock is taken during object creation in non-rs threads
    // and object deletion in the rs thread.
    static void asyncLock();
    static void asyncUnlock();

protected:
    // Called inside the async lock for any object list management that is
    // necessary in derived classes.
    virtual void preDestroy() const;

    Context *mRSC;
    virtual ~ObjectBase();

private:
    static pthread_mutex_t gObjectInitMutex;

    void add() const;
    void remove() const;

    String8 mName;
    mutable int32_t mSysRefCount;
    mutable int32_t mUserRefCount;

    mutable const ObjectBase * mPrev;
    mutable const ObjectBase * mNext;

#if RS_OBJECT_DEBUG
    CallStack mStack;
#endif

};

template<class T>
class ObjectBaseRef {
public:
    ObjectBaseRef() {
        mRef = NULL;
    }

    ObjectBaseRef(const ObjectBaseRef &ref) {
        mRef = ref.get();
        if (mRef) {
            mRef->incSysRef();
        }
    }

    ObjectBaseRef(T *ref) {
        mRef = ref;
        if (mRef) {
            ref->incSysRef();
        }
    }

    ObjectBaseRef & operator= (const ObjectBaseRef &ref) {
        if (&ref != this) {
            set(ref);
        }
        return *this;
    }

    ~ObjectBaseRef() {
        clear();
    }

    void set(T *ref) {
        if (mRef != ref) {
            clear();
            mRef = ref;
            if (mRef) {
                ref->incSysRef();
            }
        }
    }

    void set(const ObjectBaseRef &ref) {
        set(ref.mRef);
    }

    void clear() {
        if (mRef) {
            mRef->decSysRef();
        }
        mRef = NULL;
    }

    inline T * get() const {
        return mRef;
    }

    inline T * operator-> () const {
        return mRef;
    }

protected:
    T * mRef;
};

}
}

#endif //ANDROID_RS_OBJECT_BASE_H

