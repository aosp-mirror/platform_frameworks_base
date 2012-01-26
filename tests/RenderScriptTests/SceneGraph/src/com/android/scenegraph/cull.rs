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


void root(rs_allocation *v_out, const void *usrData) {

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
}
