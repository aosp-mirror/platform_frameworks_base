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

#ifndef ANDROID_RS_SCRIPT_H
#define ANDROID_RS_SCRIPT_H

#include "rsAllocation.h"


// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramVertex;
class ProgramFragment;
class ProgramRaster;
class ProgramStore;

class Script : public ObjectBase {
public:
    struct Hal {
        void * drv;

        struct DriverInfo {
            int mVersionMajor;
            int mVersionMinor;

            size_t exportedVariableCount;
            size_t exportedFunctionCount;
            size_t exportedPragmaCount;
            char const **exportedPragmaKeyList;
            char const **exportedPragmaValueList;

            int (* root)();
            bool isThreadable;
        };
        DriverInfo info;
    };
    Hal mHal;

    typedef void (* InvokeFunc_t)(void);

    Script(Context *);
    virtual ~Script();

    struct Enviroment_t {
        int64_t mStartTimeMillis;
        int64_t mLastDtTime;

        ObjectBaseRef<ProgramVertex> mVertex;
        ObjectBaseRef<ProgramFragment> mFragment;
        ObjectBaseRef<ProgramRaster> mRaster;
        ObjectBaseRef<ProgramStore> mFragmentStore;
    };
    Enviroment_t mEnviroment;

    void setSlot(uint32_t slot, Allocation *a);
    void setVar(uint32_t slot, const void *val, size_t len);
    void setVarObj(uint32_t slot, ObjectBase *val);

    virtual bool freeChildren();

    virtual void runForEach(Context *rsc,
                            const Allocation * ain,
                            Allocation * aout,
                            const void * usr,
                            size_t usrBytes,
                            const RsScriptCall *sc = NULL) = 0;

    virtual void Invoke(Context *rsc, uint32_t slot, const void *data, size_t len) = 0;
    virtual void setupScript(Context *rsc) = 0;
    virtual uint32_t run(Context *) = 0;
protected:
    bool mInitialized;
    ObjectBaseRef<Allocation> *mSlots;
    ObjectBaseRef<const Type> *mTypes;

};


}
}
#endif

