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

// An element is a group of Components that occupies one cell in a structure.
class ObjectBase
{
public:
    ObjectBase();
    virtual ~ObjectBase();

    void incRef() const;
    void decRef() const;

    const char * getName() const {
        return mName;
    }
    void setName(const char *);

private:
    char * mName;
    mutable int32_t mRefCount;


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
            mRef->incRef();
        }
    }

    ObjectBaseRef(T *ref) {
        mRef = ref;
        if (mRef) {
            ref->incRef();
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
                ref->incRef();
            }
        }
    }

    void set(const ObjectBaseRef &ref) {
        set(ref.mRef);
    }

    void clear() {
        if (mRef) {
            mRef->decRef();
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

