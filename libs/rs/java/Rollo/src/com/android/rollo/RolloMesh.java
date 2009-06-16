 /*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.rollo;

import java.io.Writer;
import java.lang.Math;

import android.renderscript.RenderScript;


class RolloMesh {

    static RenderScript.TriangleMesh createCard(RenderScript rs) {
        RenderScript.Element vtx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.ST_XYZ_F32);
        RenderScript.Element idx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);

        rs.triangleMeshBegin(vtx, idx);
        rs.triangleMeshAddVertex_XYZ_ST(0, 0, 0,  0, 0);
        rs.triangleMeshAddVertex_XYZ_ST(0, 1, 0,  0, 1);
        rs.triangleMeshAddVertex_XYZ_ST(1, 1, 0,  1, 1);
        rs.triangleMeshAddVertex_XYZ_ST(1, 0, 0,  1, 0);

        rs.triangleMeshAddTriangle(0,1,2);
        rs.triangleMeshAddTriangle(0,2,3);
        return rs.triangleMeshCreate();
    }

    static RenderScript.TriangleMesh createTab(RenderScript rs) {
        RenderScript.Element vtx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.ST_XYZ_F32);
        RenderScript.Element idx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);

        rs.triangleMeshBegin(vtx, idx);
        rs.triangleMeshAddVertex_XYZ_ST(0.0f, 0, 0,  -1.0f, 0);
        rs.triangleMeshAddVertex_XYZ_ST(0.2f, 1, 0,  -0.8f, 1);
        rs.triangleMeshAddVertex_XYZ_ST(1.8f, 1, 0,   0.8f, 1);
        rs.triangleMeshAddVertex_XYZ_ST(2.0f, 0, 0,   1.0f, 0);
        rs.triangleMeshAddTriangle(0,1,2);
        rs.triangleMeshAddTriangle(0,2,3);
        return rs.triangleMeshCreate();
    }



}


