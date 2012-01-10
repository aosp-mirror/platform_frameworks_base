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

//#define DEBUG_CAMERA
#include "transform_def.rsh"

void root(const rs_allocation *v_in, rs_allocation *v_out, const float *usrData) {

    SgCamera *cam = (SgCamera *)rsGetElementAt(*v_in, 0);
    float aspect = *usrData;
    cam->aspect = aspect;
    const SgTransform *camTransform = (const SgTransform *)rsGetElementAt(cam->transformMatrix, 0);

    rsMatrixLoadPerspective(&cam->proj, cam->horizontalFOV, cam->aspect, cam->near, cam->far);

    rs_matrix4x4 camPosMatrix;
    rsMatrixLoad(&camPosMatrix, &camTransform->globalMat);
    float4 zero = {0.0f, 0.0f, 0.0f, 1.0f};
    cam->position = rsMatrixMultiply(&camPosMatrix, zero);

    rsMatrixInverse(&camPosMatrix);
    rsMatrixLoad(&cam->view, &camPosMatrix);

    rsMatrixLoad(&cam->viewProj, &cam->proj);
    rsMatrixMultiply(&cam->viewProj, &cam->view);

    rsExtractFrustumPlanes(&cam->viewProj,
                           &cam->frustumPlanes[0], &cam->frustumPlanes[1],
                           &cam->frustumPlanes[2], &cam->frustumPlanes[3],
                           &cam->frustumPlanes[3], &cam->frustumPlanes[4]);
#ifdef DEBUG_CAMERA
    printCameraInfo(cam);
#endif //DEBUG_CAMERA
}
