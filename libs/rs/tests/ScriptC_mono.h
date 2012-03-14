/*
 * Copyright (C) 2012 The Android Open Source Project
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


/*
 * This file is auto-generated. DO NOT MODIFY!
 * The source Renderscript file: mono.rs
 */


#include "ScriptC.h"

class ScriptC_mono : protected ScriptC {
private:
    int32_t __gInt;
    bool __gBool;
public:
    ScriptC_mono(RenderScript *rs, const char *cacheDir, size_t cacheDirLength);
    virtual ~ScriptC_mono();
    
    void set_gInt(int32_t v) {
        setVar(0, v);
        __gInt = v;
    }
    int32_t get_gInt() const {
        return __gInt;
    }
    
    float get_cFloat() const {
        return 1.2f;
    }
    
    void set_gBool(bool v) {
        setVar(2, v);
        __gBool = v;
    }
    bool get_gBool() const {
        return __gBool;
    }
    
    void forEach_root(const Allocation *ain, const Allocation *aout) const;
};
