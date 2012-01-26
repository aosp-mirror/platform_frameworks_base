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

#include "params.rsh"

void root(rs_allocation *v_out, const void *usrData) {
    SgVertexShader *shader = (SgVertexShader *)rsGetElementAt(*v_out, 0);
    const SgCamera *camera = (const SgCamera*)usrData;
    processAllParams(shader->shaderConst, shader->shaderConstParams, camera);
}
