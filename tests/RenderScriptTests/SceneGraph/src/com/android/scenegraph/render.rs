// Copyright (C) 2011 The Android Open Source Project
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

#include "rs_graphics.rsh"
#include "transform_def.rsh"

rs_script gTransformScript;
rs_script gCameraScript;
rs_script gLightScript;

SgTransform *gRootNode;
rs_allocation gCameras;
rs_allocation gLights;
rs_allocation gRenderableObjects;

rs_allocation gRenderPasses;

// Temporary shaders
rs_allocation gTGrid;
rs_program_store gPFSBackground;

VShaderParams *vConst;
FShaderParams *fConst;

uint32_t *gFrontToBack;
static uint32_t gFrontToBackCount = 0;
uint32_t *gBackToFront;
static uint32_t gBackToFrontCount = 0;

static SgCamera *gActiveCamera = NULL;
static float4 gFrustumPlanes[6];

static rs_allocation nullAlloc;

//#define DEBUG_RENDERABLES
static void draw(SgRenderable *obj) {

    const SgRenderState *renderState = (const SgRenderState *)rsGetElementAt(obj->render_state, 0);
    const SgTransform *objTransform = (const SgTransform *)rsGetElementAt(obj->transformMatrix, 0);
#ifdef DEBUG_RENDERABLES
    rsDebug("**** Drawing object with transform", obj);
    printName(objTransform->name);
    rsDebug("Model matrix: ", &objTransform->globalMat);
    printName(obj->name);
#endif //DEBUG_RENDERABLES

    SgCamera *cam = gActiveCamera;

    rsMatrixLoad(&vConst->model, &objTransform->globalMat);
    rsMatrixLoad(&vConst->viewProj, &cam->viewProj);
    rsgAllocationSyncAll(rsGetAllocation(vConst));
    fConst->cameraPos = cam->position;
    rsgAllocationSyncAll(rsGetAllocation(fConst));

    if (rsIsObject(renderState->ps)) {
        rsgBindProgramStore(renderState->ps);
    } else {
        rsgBindProgramStore(gPFSBackground);
    }

    if (rsIsObject(renderState->pr)) {
        rsgBindProgramRaster(renderState->pr);
    } else {
        rs_program_raster pr;
        rsgBindProgramRaster(pr);
    }

    rsgBindProgramFragment(renderState->pf);
    rsgBindProgramVertex(renderState->pv);

    if (rsIsObject(obj->pf_textures[0])) {
        rsgBindTexture(renderState->pf, 0, obj->pf_textures[0]);
    } else {
        rsgBindTexture(renderState->pf, 0, gTGrid);
    }

    rsgDrawMesh(obj->mesh, obj->meshIndex);
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

static bool frustumCulled(SgRenderable *obj) {
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
                                &gFrustumPlanes[0], &gFrustumPlanes[1],
                                &gFrustumPlanes[2], &gFrustumPlanes[3],
                                &gFrustumPlanes[3], &gFrustumPlanes[4]);
}

static void sortToBucket(SgRenderable *obj) {
    // Not loaded yet
    if (!rsIsObject(obj->mesh) || obj->cullType == 2) {
        return;
    }

    // check to see if we are culling this object and if it's
    // outside the frustum
    if (obj->cullType == 0 && frustumCulled(obj)) {
#ifdef DEBUG_RENDERABLES
        rsDebug("Culled", obj);
        printName(obj->name);
#endif //DEBUG_RENDERABLES
        return;
    }
    const SgRenderState *renderState = (const SgRenderState *)rsGetElementAt(obj->render_state, 0);
    if (rsIsObject(renderState->ps)) {
#define MR1_API
#ifndef MR1_API
        bool isOpaque = (rsgProgramStoreGetBlendSrcFunc(renderState->ps) == RS_BLEND_SRC_ONE) &&
                        (rsgProgramStoreGetBlendDstFunc(renderState->ps) == RS_BLEND_DST_ZERO);
#else
        bool isOpaque = false;
#endif
        if (isOpaque) {
            gFrontToBack[gFrontToBackCount++] = (uint32_t)obj;
        } else {
            gBackToFront[gBackToFrontCount++] = (uint32_t)obj;
        }
    } else {
        gFrontToBack[gFrontToBackCount++] = (uint32_t)obj;
    }
}

static void updateActiveCamera(rs_allocation cam) {
    gActiveCamera = (SgCamera *)rsGetElementAt(cam, 0);

    rsExtractFrustumPlanes(&gActiveCamera->viewProj,
                           &gFrustumPlanes[0], &gFrustumPlanes[1],
                           &gFrustumPlanes[2], &gFrustumPlanes[3],
                           &gFrustumPlanes[3], &gFrustumPlanes[4]);
}

static void prepareCameras() {
    // now compute all the camera matrices
    float aspect = (float)rsgGetWidth() / (float)rsgGetHeight();
    rsForEach(gCameraScript, gCameras, nullAlloc, &aspect, sizeof(aspect));
}

static void prepareLights() {
    if (rsIsObject(gLights)) {
        rsForEach(gLightScript, gLights, nullAlloc);
    }
}

static void drawSorted() {
    for (int i = 0; i < gFrontToBackCount; i ++) {
        SgRenderable *current = (SgRenderable*)gFrontToBack[i];
        draw(current);
    }

    for (int i = 0; i < gBackToFrontCount; i ++) {
        SgRenderable *current = (SgRenderable*)gBackToFront[i];
        draw(current);
    }
}

static void drawAllObjects(rs_allocation allObj) {
    if (!rsIsObject(allObj)) {
        return;
    }
    int numRenderables = rsAllocationGetDimX(allObj);
    for (int i = 0; i < numRenderables; i ++) {
        rs_allocation *drawAlloc = (rs_allocation*)rsGetElementAt(allObj, i);
        SgRenderable *current = (SgRenderable*)rsGetElementAt(*drawAlloc, 0);
        sortToBucket(current);
    }
    drawSorted();
}

void root(const void *v_in, void *v_out) {

    //rsDebug("=============================================================================", 0);
    // first step is to update the transform hierachy
    rsForEach(gTransformScript, gRootNode->children, nullAlloc, 0, 0);

    prepareCameras();

    rsgClearDepth(1.0f);

    int numRenderables = rsAllocationGetDimX(gRenderableObjects);
    if (rsIsObject(gRenderPasses)) {
        int numPasses = rsAllocationGetDimX(gRenderPasses);
        for (uint i = 0; i < numPasses; i ++) {
            gFrontToBackCount = 0;
            gBackToFrontCount = 0;
            SgRenderPass *pass = (SgRenderPass*)rsGetElementAt(gRenderPasses, i);
            if (rsIsObject(pass->color_target)) {
                rsgBindColorTarget(pass->color_target, 0);
            }
            if (rsIsObject(pass->depth_target)) {
                rsgBindDepthTarget(pass->depth_target);
            }
            if (!rsIsObject(pass->color_target) &&
                !rsIsObject(pass->depth_target)) {
                rsgClearAllRenderTargets();
            }
            updateActiveCamera(pass->camera);
            if (pass->should_clear_color) {
                rsgClearColor(pass->clear_color.x, pass->clear_color.y,
                              pass->clear_color.z, pass->clear_color.w);
            }
            if (pass->should_clear_depth) {
                rsgClearDepth(pass->clear_depth);
            }
            drawAllObjects(pass->objects);
        }
    } else {
        gFrontToBackCount = 0;
        gBackToFrontCount = 0;
        rsgClearColor(1.0f, 1.0f, 1.0f, 1.0f);
        rsgClearDepth(1.0f);
        rs_allocation *camAlloc = (rs_allocation*)rsGetElementAt(gCameras, 1);
        updateActiveCamera(*camAlloc);
        drawAllObjects(gRenderableObjects);
    }
}

static bool intersect(const SgRenderable *obj, float3 pnt, float3 vec) {
    // Solving for t^2 + Bt + C = 0
    float3 originMinusCenter = pnt - obj->worldBoundingSphere.xyz;
    float B = dot(originMinusCenter, vec) * 2.0f;
    float C = dot(originMinusCenter, originMinusCenter) -
              obj->worldBoundingSphere.w * obj->worldBoundingSphere.w;

    float discriminant = B * B - 4.0f * C;
    if (discriminant < 0.0f) {
        return false;
    }
    discriminant = sqrt(discriminant);

    float t0 = (-B - discriminant) * 0.5f;
    float t1 = (-B + discriminant) * 0.5f;

    if (t0 > t1) {
        float temp = t0;
        t0 = t1;
        t1 = temp;
    }

    // The sphere is behind us
    if (t1 < 0.0f) {
        return false;
    }
    return true;
}

// Search through sorted and culled objects
void pick(int screenX, int screenY) {
    float3 pnt, vec;
    getCameraRay(gActiveCamera, screenX, screenY, &pnt, &vec);

    for (int i = 0; i < gFrontToBackCount; i ++) {
        SgRenderable *current = (SgRenderable*)gFrontToBack[i];
        bool isPicked = intersect(current, pnt, vec);
        if (isPicked) {
            current->cullType = 2;
        }
    }

    for (int i = 0; i < gBackToFrontCount; i ++) {
        SgRenderable *current = (SgRenderable*)gBackToFront[i];
        bool isPicked = intersect(current, pnt, vec);
        if (isPicked) {
            current->cullType = 2;
        }
    }
}
