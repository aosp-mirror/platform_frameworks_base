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

#include "scenegraph_objects.rsh"

//#define DEBUG_PARAMS
static void debugParam(SgShaderParam *p, SgShaderParamData *pData) {
    rsDebug("____________ Param ____________", p);
    printName(pData->paramName);
    rsDebug("bufferOffset", p->bufferOffset);
    rsDebug("type ", pData->type);
    rsDebug("data timestamp ", pData->timestamp);
    rsDebug("param timestamp", p->dataTimestamp);

    const SgTransform *pTransform = NULL;
    if (rsIsObject(pData->transform)) {
        pTransform = (const SgTransform *)rsGetElementAt(pData->transform, 0);

        rsDebug("transform", pTransform);
        printName(pTransform->name);
        rsDebug("timestamp", pTransform->timestamp);
        rsDebug("param timestamp", p->transformTimestamp);
    }

    const SgLight *pLight = NULL;
    if (rsIsObject(pData->light)) {
        pLight = (const SgLight *)rsGetElementAt(pData->light, 0);
        printLightInfo(pLight);
    }
}


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

static bool processParam(SgShaderParam *p, SgShaderParamData *pData,
                         uint8_t *constantBuffer,
                         const SgCamera *currentCam,
                         SgFragmentShader *shader) {
    bool isDataOnly = (pData->type > SHADER_PARAM_DATA_ONLY);
    const SgTransform *pTransform = NULL;
    if (rsIsObject(pData->transform)) {
        pTransform = (const SgTransform *)rsGetElementAt(pData->transform, 0);
    }

    if (isDataOnly) {
        // If we are a transform param and our transform is unchanged, nothing to do
        if (pTransform) {
            if (p->transformTimestamp == pTransform->timestamp) {
                return false;
            }
            p->transformTimestamp = pTransform->timestamp;
        } else {
            if (p->dataTimestamp == pData->timestamp) {
                return false;
            }
            p->dataTimestamp = pData->timestamp;
        }
    }

    const SgLight *pLight = NULL;
    if (rsIsObject(pData->light)) {
        pLight = (const SgLight *)rsGetElementAt(pData->light, 0);
    }

    uint8_t *dataPtr = NULL;
    const SgTexture *tex = NULL;
    if (pData->type == SHADER_PARAM_TEXTURE) {
        tex = rsGetElementAt(pData->texture, 0);
    } else {
        dataPtr = constantBuffer + p->bufferOffset;
    }

    switch (pData->type) {
    case SHADER_PARAM_TEXTURE:
        rsgBindTexture(shader->program, p->bufferOffset, tex->texture);
        break;
    case SHADER_PARAM_FLOAT4_DATA:
        writeFloatData((float*)dataPtr, &pData->float_value, p->float_vecSize);
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
    return true;
}

static void processAllParams(rs_allocation shaderConst,
                             rs_allocation allParams,
                             const SgCamera *camera) {
    if (rsIsObject(shaderConst)) {
        uint8_t *constantBuffer = (uint8_t*)rsGetElementAt(shaderConst, 0);

        int numParams = 0;
        if (rsIsObject(allParams)) {
            numParams = rsAllocationGetDimX(allParams);
        }
        bool updated = false;
        for (int i = 0; i < numParams; i ++) {
            SgShaderParam *current = (SgShaderParam*)rsGetElementAt(allParams, i);
            SgShaderParamData *currentData = (SgShaderParamData*)rsGetElementAt(current->data, 0);
#ifdef DEBUG_PARAMS
            debugParam(current, currentData);
#endif // DEBUG_PARAMS
            updated = processParam(current, currentData, constantBuffer, camera, NULL) || updated;
        }
    }
}

static void processTextureParams(SgFragmentShader *shader) {
    int numParams = 0;
    if (rsIsObject(shader->shaderTextureParams)) {
        numParams = rsAllocationGetDimX(shader->shaderTextureParams);
    }
    for (int i = 0; i < numParams; i ++) {
        SgShaderParam *current = (SgShaderParam*)rsGetElementAt(shader->shaderTextureParams, i);
        SgShaderParamData *currentData = (SgShaderParamData*)rsGetElementAt(current->data, 0);
#ifdef DEBUG_PARAMS
        debugParam(current, currentData);
#endif // DEBUG_PARAMS
        processParam(current, currentData, NULL, NULL, shader);
    }
}
