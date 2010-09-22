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

#pragma rs java_package_name(com.android.modelviewer)

#define TRANSFORM_NONE 0
#define TRANSFORM_TRANSLATE 1
#define TRANSFORM_ROTATE 2
#define TRANSFORM_SCALE 3

typedef struct {
    rs_matrix4x4 globalMat;
    rs_matrix4x4 localMat;

    float4 transforms0;
    float4 transforms1;
    float4 transforms2;
    float4 transforms3;
    float4 transforms4;
    float4 transforms5;
    float4 transforms6;
    float4 transforms7;
    float4 transforms8;
    float4 transforms9;
    float4 transforms10;
    float4 transforms11;
    float4 transforms12;
    float4 transforms13;
    float4 transforms14;
    float4 transforms15;

    int transformType0;
    int transformType1;
    int transformType2;
    int transformType3;
    int transformType4;
    int transformType5;
    int transformType6;
    int transformType7;
    int transformType8;
    int transformType9;
    int transformType10;
    int transformType11;
    int transformType12;
    int transformType13;
    int transformType14;
    int transformType15;

    int isDirty;

    rs_allocation children;

} SgTransform;
