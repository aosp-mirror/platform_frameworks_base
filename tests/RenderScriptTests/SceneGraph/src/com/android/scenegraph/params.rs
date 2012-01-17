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
        *ptr++ = input->x;
        *ptr = input->y;
        break;
    case 3:
        *ptr++ = input->x;
        *ptr++ = input->y;
        *ptr = input->z;
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

static void getTransformedSphere(SgRenderable *obj) {
    obj->worldBoundingSphere = obj->boundingSphere;
    obj->worldBoundingSphere.w = 1.0f;
    const SgTransform *objTransform = (const SgTransform *)rsGetElementAt(obj->transformMatrix, 0);
    obj->worldBoundingSphere = rsMatrixMultiply(&objTransform->globalMat, obj->worldBoundingSphere);

    const float4 unitVec = {0.57735f, 0.57735f, 0.57735f, 0.0f};
    float4 scaledVec = rsMatrixMultiply(&objTransform->globalMat, unitVec);
    scaledVec.w = 0.0f;
    obj->worldBoundingSphere.w = obj->boundingSphere.w * length(scaledVec);
}

static bool frustumCulled(SgRenderable *obj, SgCamera *cam) {
    if (!obj->bVolInitialized) {
        float minX, minY, minZ, maxX, maxY, maxZ;
        rsgMeshComputeBoundingBox(obj->mesh,
                                  &minX, &minY, &minZ,
                                  &maxX, &maxY, &maxZ);
        //rsDebug("min", minX, minY, minZ);
        //rsDebug("max", maxX, maxY, maxZ);
        float4 sphere;
        sphere.x = (maxX + minX) * 0.5f;
        sphere.y = (maxY + minY) * 0.5f;
        sphere.z = (maxZ + minZ) * 0.5f;
        float3 radius;
        radius.x = (maxX - sphere.x);
        radius.y = (maxY - sphere.y);
        radius.z = (maxZ - sphere.z);

        sphere.w = length(radius);
        obj->boundingSphere = sphere;
        obj->bVolInitialized = 1;
        //rsDebug("Sphere", sphere);
    }

    getTransformedSphere(obj);

    return !rsIsSphereInFrustum(&obj->worldBoundingSphere,
                                &cam->frustumPlanes[0], &cam->frustumPlanes[1],
                                &cam->frustumPlanes[2], &cam->frustumPlanes[3],
                                &cam->frustumPlanes[4], &cam->frustumPlanes[5]);
}


void root(const rs_allocation *v_in, rs_allocation *v_out, const void *usrData) {

    SgRenderable *drawable = (SgRenderable *)rsGetElementAt(*v_out, 0);
    const SgCamera *camera = (const SgCamera*)usrData;

    drawable->isVisible = 0;
    // Not loaded yet
    if (!rsIsObject(drawable->mesh) || drawable->cullType == CULL_ALWAYS) {
        return;
    }

    // check to see if we are culling this object and if it's
    // outside the frustum
    if (drawable->cullType == CULL_FRUSTUM && frustumCulled(drawable, (SgCamera*)camera)) {
#ifdef DEBUG_RENDERABLES
        rsDebug("Culled", drawable);
        printName(drawable->name);
#endif // DEBUG_RENDERABLES
        return;
    }
    drawable->isVisible = 1;

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
