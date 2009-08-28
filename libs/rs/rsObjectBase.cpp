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

using namespace android;
using namespace android::renderscript;

ObjectBase::ObjectBase()
{
    mUserRefCount = 0;
    mSysRefCount = 0;
    mName = NULL;
}

ObjectBase::~ObjectBase()
{
    //LOGV("~ObjectBase %p  ref %i", this, mRefCount);
    rsAssert(!mUserRefCount);
    rsAssert(!mSysRefCount);
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

void ObjectBase::decUserRef() const
{
    rsAssert(mUserRefCount > 0);
    mUserRefCount --;
    //LOGV("ObjectBase %p dec ref %i", this, mRefCount);
    if (!(mSysRefCount | mUserRefCount)) {
        if (mName) {
            LOGV("Deleting RS object %p, name %s", this, mName);
        } else {
            LOGV("Deleting RS object %p, no name", this);
        }
        delete this;
    }
}

void ObjectBase::decSysRef() const
{
    rsAssert(mSysRefCount > 0);
    mSysRefCount --;
    //LOGV("ObjectBase %p dec ref %i", this, mRefCount);
    if (!(mSysRefCount | mUserRefCount)) {
        if (mName) {
            LOGV("Deleting RS object %p, name %s", this, mName);
        } else {
            LOGV("Deleting RS object %p, no name", this);
        }
        delete this;
    }
}

void ObjectBase::setName(const char *name)
{
    delete mName;
    mName = NULL;
    if (name) {
        mName = new char[strlen(name) +1];
        strcpy(mName, name);
    }
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

