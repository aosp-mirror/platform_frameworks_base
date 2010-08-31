// Copyright (C) 2009 The Android Open Source Project
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

#pragma rs java_package_name(com.android.samples)

typedef struct VertexShaderConstants_s {
    rs_matrix4x4 model;
    float3 light0_Posision;
    float light0_Diffuse;
    float light0_Specular;
    float light0_CosinePower;

    float3 light1_Posision;
    float light1_Diffuse;
    float light1_Specular;
    float light1_CosinePower;

} VertexShaderConstants;

typedef struct FragentShaderConstants_s {
    float3 light0_DiffuseColor;
    float3 light0_SpecularColor;

    float3 light1_DiffuseColor;
    float3 light1_SpecularColor;

} FragentShaderConstants;

typedef struct VertexShaderInputs_s {
    float4 position;
    float3 normal;
    float4 texture0;
} VertexShaderInputs;

