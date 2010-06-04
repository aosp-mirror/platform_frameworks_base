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


namespace android {
namespace renderscript {

class Context;
class OStream;

// An element is a group of Components that occupies one cell in a structure.
class ObjectBase
{
public:
    ObjectBase(Context *rsc);
    virtual ~ObjectBase();

    void incSysRef() const;
    bool decSysRef() const;

    void incUserRef() const;
    bool decUserRef() const;
    bool zeroUserRef() const;

    const char * getName() const {
        return mName.string();
    }
    void setName(const char *);
    void setName(const char *, uint32_t len);

    Context * getContext() const {return mRSC;}
    void setContext(Context *);

    static void zeroAllUserRef(Context *rsc);
    static void dumpAll(Context *rsc);

    virtual void dumpLOGV(const char *prefix) const;
    virtual void serialize(OStream *stream) const = 0;
    virtual RsA3DClassID getClassId() const = 0;

protected:
    const char *mAllocFile;
    uint32_t mAllocLine;
    Context *mRSC;

private:
    void add() const;
    void remove() const;

    bool checkDelete() const;

    String8 mName;
    mutable int32_t mSysRefCount;
    mutable int32_t mUserRefCount;

    mutable const ObjectBase * mPrev;
    mutable const ObjectBase * mNext;
};

template<class T>
class ObjectBaseRef
{
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

