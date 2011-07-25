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

#include "transform_def.rsh"

rs_script transformScript;

typedef struct {
    int changed;
    rs_matrix4x4 *mat;
} ParentData;

static void appendTransformation(int type, float4 data, rs_matrix4x4 *mat) {
    rs_matrix4x4 temp;

    switch (type) {
    case TRANSFORM_TRANSLATE:
        rsMatrixLoadTranslate(&temp, data.x, data.y, data.z);
        break;
    case TRANSFORM_ROTATE:
        rsMatrixLoadRotate(&temp, data.w, data.x, data.y, data.z);
        break;
    case TRANSFORM_SCALE:
        rsMatrixLoadScale(&temp, data.x, data.y, data.z);
        break;
    }
    rsMatrixMultiply(mat, &temp);
}

void root(const void *v_in, void *v_out, const void *usrData, uint32_t x, uint32_t y) {

    SgTransform *data = (SgTransform *)v_out;
    const ParentData *parent = (const ParentData *)usrData;

    //rsDebug("Transform data", (int)data);
    //rsDebug("Entering parent", (int)parent);

    rs_matrix4x4 *localMat = &data->localMat;
    rs_matrix4x4 *globalMat = &data->globalMat;

    ParentData toChild;
    toChild.changed = 0;
    toChild.mat = globalMat;

    //rsDebug("Transform is dirty", data->isDirty);

    // Refresh matrices if dirty
    if (data->isDirty) {
        data->isDirty = 0;
        toChild.changed = 1;

        // Reset our local matrix
        rsMatrixLoadIdentity(localMat);

        for (int i = 0; i < 16; i ++) {
            if (data->transformTypes[i] == TRANSFORM_NONE) {
                break;
            }
            //rsDebug("Transform adding transformation", transformTypes[i]);
            appendTransformation(data->transformTypes[i], data->transforms[i], localMat);
        }
    }

    //rsDebug("Transform checking parent", (int)0);

    if (parent) {
        if (parent->changed) {
            toChild.changed = 1;

            rsMatrixLoad(globalMat, parent->mat);
            rsMatrixMultiply(globalMat, localMat);
        }
    } else {
        rsMatrixLoad(globalMat, localMat);
    }

    //rsDebug("Transform calling self with child ", (int)data->children.p);
    if (data->children.p) {
        rsForEach(transformScript, data->children, data->children, (void*)&toChild, sizeof(toChild));
    }
}
