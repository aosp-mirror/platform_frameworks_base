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

#pragma rs java_package_name(com.example.android.rs.miscsamples)

typedef struct VertexShaderConstants_s {
    rs_matrix4x4 model;
    rs_matrix4x4 proj;
    float4 light0_Posision;
    float light0_Diffuse;
    float light0_Specular;
    float light0_CosinePower;

    float4 light1_Posision;
    float light1_Diffuse;
    float light1_Specular;
    float light1_CosinePower;
} VertexShaderConstants;

typedef struct VertexShaderConstants2_s {
    rs_matrix4x4 model[2];
    rs_matrix4x4 proj;
    float4 light_Posision[2];
    float light_Diffuse[2];
    float light_Specular[2];
    float light_CosinePower[2];
} VertexShaderConstants2;

typedef struct VertexShaderConstants3_s {
    rs_matrix4x4 model;
    rs_matrix4x4 proj;
    float time;
} VertexShaderConstants3;


typedef struct FragentShaderConstants_s {
    float4 light0_DiffuseColor;
    float4 light0_SpecularColor;

    float4 light1_DiffuseColor;
    float4 light1_SpecularColor;
} FragentShaderConstants;

typedef struct FragentShaderConstants2_s {
    float4 light_DiffuseColor[2];
    float4 light_SpecularColor[2];
} FragentShaderConstants2;

typedef struct FragentShaderConstants3_s {
    float4 light0_DiffuseColor;
    float4 light0_SpecularColor;
    float4 light0_Posision;
    float light0_Diffuse;
    float light0_Specular;
    float light0_CosinePower;

    float4 light1_DiffuseColor;
    float4 light1_SpecularColor;
    float4 light1_Posision;
    float light1_Diffuse;
    float light1_Specular;
    float light1_CosinePower;
} FragentShaderConstants3;

typedef struct VertexShaderInputs_s {
    float4 position;
    float3 normal;
    float2 texture0;
} VertexShaderInputs;

