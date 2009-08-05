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

#ifndef ANDROID_RS_PROGRAM_H
#define ANDROID_RS_PROGRAM_H

#include "rsObjectBase.h"
#include "rsElement.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {



class Program : public ObjectBase
{
public:
    Program(Element *in, Element *out);
    virtual ~Program();


    void bindAllocation(Allocation *);

    virtual void setupGL();

    void checkUpdatedAllocation(const Allocation *);

protected:
    // Components not listed in "in" will be passed though
    // unless overwritten by components in out.
    ObjectBaseRef<Element> mElementIn;
    ObjectBaseRef<Element> mElementOut;

    ObjectBaseRef<Allocation> mConstants;

    mutable bool mDirty;
};



}
}
#endif



