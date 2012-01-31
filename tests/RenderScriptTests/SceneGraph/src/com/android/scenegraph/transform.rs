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

#include "scenegraph_objects.rsh"

rs_script gTransformScript;

typedef struct {
    int changed;
    rs_matrix4x4 *mat;
} ParentData;

//#define DEBUG_TRANSFORMS
static void debugTransform(SgTransform *data, const ParentData *parent) {
    rsDebug("****** <Transform> ******", (int)data);
    printName(data->name);
    rsDebug("isDirty", data->isDirty);
    rsDebug("parent", (int)parent);
    rsDebug("child ", rsIsObject(data->children));

    // Refresh matrices if dirty
    if (data->isDirty && rsIsObject(data->components)) {
        uint32_t numComponenets = rsAllocationGetDimX(data->components);
        for (int i = 0; i < numComponenets; i ++) {
            const SgTransformComponent *comp = NULL;
            comp = (const SgTransformComponent *)rsGetElementAt(data->components, i);

            if (rsIsObject(comp->name)) {
                rsDebug((const char*)rsGetElementAt(comp->name, 0), comp->value);
                rsDebug("Type", comp->type);
            } else {
                rsDebug("no name", comp->value);
                rsDebug("Type", comp->type);
            }
        }
    }

    rsDebug("timestamp", data->timestamp);
    rsDebug("****** </Transform> ******", (int)data);
}

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
    debugTransform(data, parent);
#endif //DEBUG_TRANSFORMS

    rs_matrix4x4 *localMat = &data->localMat;
    rs_matrix4x4 *globalMat = &data->globalMat;

    // Refresh matrices if dirty
    if (data->isDirty && rsIsObject(data->components)) {
        bool resetLocal = false;
        uint32_t numComponenets = rsAllocationGetDimX(data->components);
        for (int i = 0; i < numComponenets; i ++) {
            if (!resetLocal) {
                // Reset our local matrix only for component transforms
                rsMatrixLoadIdentity(localMat);
                resetLocal = true;
            }
            const SgTransformComponent *comp = NULL;
            comp = (const SgTransformComponent *)rsGetElementAt(data->components, i);
            appendTransformation(comp->type, comp->value, localMat);
        }
    }

    if (parent) {
        data->isDirty = (parent->changed || data->isDirty) ? 1 : 0;
        if (data->isDirty) {
            rsMatrixLoad(globalMat, parent->mat);
            rsMatrixMultiply(globalMat, localMat);
        }
    } else if (data->isDirty) {
        rsMatrixLoad(globalMat, localMat);
    }

    ParentData toChild;
    toChild.changed = 0;
    toChild.mat = globalMat;

    if (data->isDirty) {
        toChild.changed = 1;
        data->timestamp ++;
    }

    if (rsIsObject(data->children)) {
        rs_allocation nullAlloc;
        rsForEach(gTransformScript, data->children, nullAlloc, &toChild, sizeof(toChild));
    }

    data->isDirty = 0;
}
