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
    static public final float mCardHeight = 1.2f;
    static public final float mCardWidth = 1.8f;
    static public final float mTabHeight = 0.2f;
    static public final float mTabs = 3;
    static public final float mTabGap = 0.1f;

    static RenderScript.TriangleMesh createCard(RenderScript rs) {
        RenderScript.Element vtx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.ST_XYZ_F32);
        RenderScript.Element idx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);

        float w = mCardWidth / 2;
        float h = mCardHeight;
        float z = 0;

        rs.triangleMeshBegin(vtx, idx);
        rs.triangleMeshAddVertex_XYZ_ST(-w, 0, z,  0, 0);
        rs.triangleMeshAddVertex_XYZ_ST(-w, h, z,  0, 1);
        rs.triangleMeshAddVertex_XYZ_ST( w, h, z,  1, 1);
        rs.triangleMeshAddVertex_XYZ_ST( w, 0, z,  1, 0);
        rs.triangleMeshAddTriangle(0,1,2);
        rs.triangleMeshAddTriangle(0,2,3);
        return rs.triangleMeshCreate();
    }

    static RenderScript.TriangleMesh createTab(RenderScript rs) {
        RenderScript.Element vtx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.ST_XYZ_F32);
        RenderScript.Element idx = rs.elementGetPredefined(
            RenderScript.ElementPredefined.INDEX_16);


        float tabSlope = 0.1f;
        float num = 0;

        float w = (mCardWidth - ((mTabs - 1) * mTabGap)) / mTabs;
        float w1 = -(mCardWidth / 2) + ((w + mTabGap) * num);
        float w2 = w1 + (w * tabSlope);
        float w3 = w1 + w - (w * tabSlope);
        float w4 = w1 + w;
        float h1 = mCardHeight;
        float h2 = h1 + mTabHeight;
        float z = 0;

        float stScale = w / mTabHeight / 2;
        float stScale2 = stScale * (tabSlope / w);


        rs.triangleMeshBegin(vtx, idx);
        rs.triangleMeshAddVertex_XYZ_ST(w1, h1, z,  -stScale, 0);
        rs.triangleMeshAddVertex_XYZ_ST(w2, h2, z,  -stScale2, 1);
        rs.triangleMeshAddVertex_XYZ_ST(w3, h2, z,   stScale2, 1);
        rs.triangleMeshAddVertex_XYZ_ST(w4, h1, z,   stScale, 0);
        rs.triangleMeshAddTriangle(0,1,2);
        rs.triangleMeshAddTriangle(0,2,3);
        return rs.triangleMeshCreate();
    }



}


