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

//#define DEBUG_LIGHT
#include "transform_def.rsh"

void root(const rs_allocation *v_in, rs_allocation *v_out) {

    SgLight *light = (SgLight *)rsGetElementAt(*v_in, 0);
    const SgTransform *lTransform = (const SgTransform *)rsGetElementAt(light->transformMatrix, 0);

    float4 zero = {0.0f, 0.0f, 0.0f, 1.0f};
    light->position = rsMatrixMultiply(&lTransform->globalMat, zero);

#ifdef DEBUG_LIGHT
    printLightInfo(light);
#endif //DEBUG_CAMERA
}
