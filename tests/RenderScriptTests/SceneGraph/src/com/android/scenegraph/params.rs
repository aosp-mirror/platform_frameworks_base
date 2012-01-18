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

static void writeFloatData(float *ptr, const float4 *input, uint32_t vecSize) {
#ifdef DEBUG_PARAMS
    rsDebug("Writing value ", *input);
    rsDebug("Writing vec size ", vecSize);
#endif // DEBUG_PARAMS

    switch (vecSize) {
    case 1:
        *ptr = input->x;
        break;
    case 2:
        *((float2*)ptr) = (*input).xy;
        break;
    case 3:
        *((float3*)ptr) = (*input).xyz;
        break;
    case 4:
        *((float4*)ptr) = *input;
        break;
    }
}

static void processParam(SgShaderParam *p, uint8_t *constantBuffer, const SgCamera *currentCam) {
#ifdef DEBUG_PARAMS
    rsDebug("____________ Param bufferOffset", p->bufferOffset);
    rsDebug("Param Type ", p->type);
#endif // DEBUG_PARAMS

    uint8_t *dataPtr = constantBuffer + p->bufferOffset;
    const SgTransform *pTransform = NULL;
    if (rsIsObject(p->transform)) {
        pTransform = (const SgTransform *)rsGetElementAt(p->transform, 0);

#ifdef DEBUG_PARAMS
        rsDebug("Param transform", pTransform);
        printName(pTransform->name);
#endif // DEBUG_PARAMS
    }

    const SgLight *pLight = NULL;
    if (rsIsObject(p->light)) {
        pLight = (const SgLight *)rsGetElementAt(p->light, 0);
#ifdef DEBUG_PARAMS
        printLightInfo(pLight);
#endif // DEBUG_PARAMS
    }

    switch(p->type) {
    case SHADER_PARAM_FLOAT4_DATA:
        writeFloatData((float*)dataPtr, &p->float_value, p->float_vecSize);
        break;
    case SHADER_PARAM_FLOAT4_CAMERA_POS:
        writeFloatData((float*)dataPtr, &currentCam->position, p->float_vecSize);
        break;
    case SHADER_PARAM_FLOAT4_CAMERA_DIR: break;
    case SHADER_PARAM_FLOAT4_LIGHT_COLOR:
        writeFloatData((float*)dataPtr, &pLight->color, p->float_vecSize);
        break;
    case SHADER_PARAM_FLOAT4_LIGHT_POS:
        writeFloatData((float*)dataPtr, &pLight->position, p->float_vecSize);
        break;
    case SHADER_PARAM_FLOAT4_LIGHT_DIR: break;

    case SHADER_PARAM_TRANSFORM_DATA:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &pTransform->globalMat);
        break;
    case SHADER_PARAM_TRANSFORM_VIEW:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &currentCam->view);
        break;
    case SHADER_PARAM_TRANSFORM_PROJ:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &currentCam->proj);
        break;
    case SHADER_PARAM_TRANSFORM_VIEW_PROJ:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &currentCam->viewProj);
        break;
    case SHADER_PARAM_TRANSFORM_MODEL:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &pTransform->globalMat);
        break;
    case SHADER_PARAM_TRANSFORM_MODEL_VIEW:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &currentCam->view);
        rsMatrixLoadMultiply((rs_matrix4x4*)dataPtr,
                             (rs_matrix4x4*)dataPtr,
                             &pTransform->globalMat);
        break;
    case SHADER_PARAM_TRANSFORM_MODEL_VIEW_PROJ:
        rsMatrixLoad((rs_matrix4x4*)dataPtr, &currentCam->viewProj);
        rsMatrixLoadMultiply((rs_matrix4x4*)dataPtr,
                             (rs_matrix4x4*)dataPtr,
                             &pTransform->globalMat);
        break;
    }
}

void root(const rs_allocation *v_in, rs_allocation *v_out, const void *usrData) {

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
