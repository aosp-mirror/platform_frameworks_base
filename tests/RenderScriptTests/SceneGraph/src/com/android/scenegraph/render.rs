// Copyright (C) 2011-2012 The Android Open Source Project
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
rs_script gObjectParamsScript;
rs_script gFragmentParamsScript;
rs_script gVertexParamsScript;
rs_script gCullScript;

SgTransform *gRootNode;
rs_allocation gCameras;
rs_allocation gLights;
rs_allocation gFragmentShaders;
rs_allocation gVertexShaders;
rs_allocation gRenderableObjects;

rs_allocation gRenderPasses;

// Temporary shaders
rs_allocation gTGrid;
rs_program_store gPFSBackground;

uint32_t *gFrontToBack;
static uint32_t gFrontToBackCount = 0;
uint32_t *gBackToFront;
static uint32_t gBackToFrontCount = 0;

static SgCamera *gActiveCamera = NULL;

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

    const SgVertexShader *pv = (const SgVertexShader *)rsGetElementAt(renderState->pv, 0);
    const SgFragmentShader *pf = (const SgFragmentShader *)rsGetElementAt(renderState->pf, 0);

    if (pv->objectConstIndex != -1) {
        rsgBindConstant(pv->program, pv->objectConstIndex, obj->pv_const);
    }
    if (pf->objectConstIndex != -1) {
        rsgBindConstant(pf->program, pf->objectConstIndex, obj->pf_const);
    }

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

    rsgBindProgramVertex(pv->program);
    rsgBindProgramFragment(pf->program);

    for (uint32_t i = 0; i < obj->pf_num_textures; i ++) {
        if (rsIsObject(obj->pf_textures[i])) {
            rsgBindTexture(pf->program, i, obj->pf_textures[i]);
        } else {
            rsgBindTexture(pf->program, i, gTGrid);
        }
    }

    rsgDrawMesh(obj->mesh, obj->meshIndex);
}

static void sortToBucket(SgRenderable *obj) {
    const SgRenderState *renderState = (const SgRenderState *)rsGetElementAt(obj->render_state, 0);
    if (rsIsObject(renderState->ps)) {
        bool isOpaque = false;
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

    rsForEach(gVertexParamsScript, nullAlloc, gVertexShaders,
              gActiveCamera, sizeof(gActiveCamera));
    rsForEach(gFragmentParamsScript, nullAlloc, gFragmentShaders,
              gActiveCamera, sizeof(gActiveCamera));

    // Run the params and cull script
    rsForEach(gCullScript, nullAlloc, allObj, gActiveCamera, sizeof(gActiveCamera));
    rsForEach(gObjectParamsScript, nullAlloc, allObj, gActiveCamera, sizeof(gActiveCamera));

    int numRenderables = rsAllocationGetDimX(allObj);
    for (int i = 0; i < numRenderables; i ++) {
        rs_allocation *drawAlloc = (rs_allocation*)rsGetElementAt(allObj, i);
        SgRenderable *current = (SgRenderable*)rsGetElementAt(*drawAlloc, 0);
        if (current->isVisible) {
            sortToBucket(current);
        }
    }
    drawSorted();
}

void root(const void *v_in, void *v_out) {
#ifdef DEBUG_RENDERABLES
    rsDebug("=============================================================================", 0);
#endif // DEBUG_RENDERABLES
    // first step is to update the transform hierachy
    rsForEach(gTransformScript, gRootNode->children, nullAlloc, 0, 0);

    prepareCameras();
    prepareLights();

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
            current->cullType = CULL_ALWAYS;
        }
    }

    for (int i = 0; i < gBackToFrontCount; i ++) {
        SgRenderable *current = (SgRenderable*)gBackToFront[i];
        bool isPicked = intersect(current, pnt, vec);
        if (isPicked) {
            current->cullType = CULL_ALWAYS;
        }
    }
}
