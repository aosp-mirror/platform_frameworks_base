/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_RS_PROGRAM_BASE_H
#define ANDROID_RS_PROGRAM_BASE_H

#include "rsObjectBase.h"
#include "rsElement.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ProgramBase : public ObjectBase {
public:
    ProgramBase(Context *rsc) : ObjectBase(rsc) {
        mDirty = true;
    }

    void forceDirty() const {mDirty = true;}

protected:
    mutable bool mDirty;
};

}
}
#endif // ANDROID_RS_PROGRAM_BASE_H



