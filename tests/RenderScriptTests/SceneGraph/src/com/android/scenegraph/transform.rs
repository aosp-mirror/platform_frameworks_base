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

#pragma rs java_package_name(com.android.modelviewer)

//#define DEBUG_TRANSFORMS

#include "transform_def.rsh"

rs_script gTransformScript;

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

void root(const rs_allocation *v_in, rs_allocation *v_out, const void *usrData) {

    SgTransform *data = (SgTransform *)rsGetElementAt(*v_in, 0);
    const ParentData *parent = (const ParentData *)usrData;

#ifdef DEBUG_TRANSFORMS
    rsDebug("**** Transform data", (int)data);
    rsDebug("Transform is dirty", data->isDirty);
    rsDebug("Transform parent", (int)parent);
    rsDebug("Transform child ", (int)data->children.p);
    printName(data->name);
#endif //DEBUG_TRANSFORMS

    rs_matrix4x4 *localMat = &data->localMat;
    rs_matrix4x4 *globalMat = &data->globalMat;

    ParentData toChild;
    toChild.changed = 0;
    toChild.mat = globalMat;

    // Refresh matrices if dirty
    if (data->isDirty) {
        toChild.changed = 1;

        bool resetLocal = false;
        for (int i = 0; i < 16; i ++) {
            if (data->transformTypes[i] == TRANSFORM_NONE) {
                break;
            }
            if (!resetLocal) {
                // Reset our local matrix only for component transforms
                rsMatrixLoadIdentity(localMat);
                resetLocal = true;
            }
#ifdef DEBUG_TRANSFORMS
            if (rsIsObject(data->transformNames[i])) {
                rsDebug((const char*)rsGetElementAt(data->transformNames[i], 0),
                        data->transforms[i]);
            } else {
                rsDebug("Transform adding transformation type", data->transformTypes[i]);
                rsDebug("Transform adding transformation", data->transforms[i]);
            }
#endif //DEBUG_TRANSFORMS
            appendTransformation(data->transformTypes[i], data->transforms[i], localMat);
        }
    }

    if (parent) {
        if (parent->changed || data->isDirty) {
            toChild.changed = 1;

            rsMatrixLoad(globalMat, parent->mat);
            rsMatrixMultiply(globalMat, localMat);
        }
    } else if (data->isDirty) {
        rsMatrixLoad(globalMat, localMat);
    }

    if (rsIsObject(data->children)) {
        rs_allocation nullAlloc;
        rsForEach(gTransformScript, data->children, nullAlloc, &toChild, sizeof(toChild));
    }

    data->isDirty = 0;
}
