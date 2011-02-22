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

typedef struct __attribute__((packed, aligned(4))) SgTransform {
    rs_matrix4x4 globalMat;
    rs_matrix4x4 localMat;

    float4 transforms[16];
    int transformTypes[16];

    int isDirty;

    rs_allocation children;

} SgTransform;
