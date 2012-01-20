// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

#pragma rs java_package_name(com.android.scenegraph)

#include "transform_def.rsh"

//#define DEBUG_PARAMS

#include "params.rsh"

void root(rs_allocation *v_out, const void *usrData) {

    SgRenderable *drawable = (SgRenderable *)rsGetElementAt(*v_out, 0);
    // Visibility flag was set earlier in the cull stage
    if (!drawable->isVisible) {
        return;
    }

    const SgCamera *camera = (const SgCamera*)usrData;
    // Data we are updating
    if (rsIsObject(drawable->pf_const)) {
        uint8_t *constantBuffer = (uint8_t*)rsGetElementAt(drawable->pf_const, 0);

        int numParams = 0;
        if (rsIsObject(drawable->pf_constParams)) {
            numParams = rsAllocationGetDimX(drawable->pf_constParams);
        }
        for (int i = 0; i < numParams; i ++) {
            SgShaderParam *current = (SgShaderParam*)rsGetElementAt(drawable->pf_constParams, i);
            processParam(current, constantBuffer, camera);
        }
        //rsgAllocationSyncAll(drawable->pf_const);
    }

    if (rsIsObject(drawable->pv_const)) {
        uint8_t *constantBuffer = (uint8_t*)rsGetElementAt(drawable->pv_const, 0);

        int numParams = 0;
        if (rsIsObject(drawable->pv_constParams)) {
            numParams = rsAllocationGetDimX(drawable->pv_constParams);
        }
        for (int i = 0; i < numParams; i ++) {
            SgShaderParam *current = (SgShaderParam*)rsGetElementAt(drawable->pv_constParams, i);
            processParam(current, constantBuffer, camera);
        }
        //rsgAllocationSyncAll(drawable->pv_const);
    }
}
