/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

#ifndef __ANDROID_SCRIPT_H__
#define __ANDROID_SCRIPT_H__

#include <pthread.h>
#include <rs.h>

#include "RenderScript.h"
#include "Allocation.h"

class Type;
class Element;
class Allocation;

class Script : public BaseObj {
protected:
    Script(void *id, RenderScript *rs);
    void forEach(uint32_t slot, const Allocation *in, const Allocation *out, const void *v, size_t) const;
    void bindAllocation(const Allocation *va, uint32_t slot) const;
    void setVar(uint32_t index, const void *, size_t len) const;
    void setVar(uint32_t index, const BaseObj *o) const;
    void invoke(uint32_t slot, const void *v, size_t len) const;


    void invoke(uint32_t slot) const {
        invoke(slot, NULL, 0);
    }
    void setVar(uint32_t index, float v) const {
        setVar(index, &v, sizeof(v));
    }
    void setVar(uint32_t index, double v) const {
        setVar(index, &v, sizeof(v));
    }
    void setVar(uint32_t index, int32_t v) const {
        setVar(index, &v, sizeof(v));
    }
    void setVar(uint32_t index, int64_t v) const {
        setVar(index, &v, sizeof(v));
    }
    void setVar(uint32_t index, bool v) const {
        setVar(index, &v, sizeof(v));
    }

public:
    class FieldBase {
    protected:
        const Element *mElement;
        Allocation *mAllocation;

        void init(RenderScript *rs, uint32_t dimx, uint32_t usages = 0);

    public:
        const Element *getElement() {
            return mElement;
        }

        const Type *getType() {
            return mAllocation->getType();
        }

        const Allocation *getAllocation() {
            return mAllocation;
        }

        //void updateAllocation();
    };
};

#endif
