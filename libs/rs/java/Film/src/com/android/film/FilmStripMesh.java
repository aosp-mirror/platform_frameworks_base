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


package com.android.film;

import java.io.Writer;
import java.lang.Math;
import android.util.Log;

import android.renderscript.RenderScript;
import android.renderscript.SimpleMesh;


class FilmStripMesh {

    class Vertex {
        float nx;
        float ny;
        float nz;
        float s;
        float t;
        float x;
        float y;
        float z;

        Vertex() {
            nx = 0;
            ny = 0;
            nz = 0;
            s = 0;
            t = 0;
            x = 0;
            y = 0;
            z = 0;
        }

        void xyz(float _x, float _y, float _z) {
            x = _x;
            y = _y;
            z = _z;
        }

        void nxyz(float _x, float _y, float _z) {
            nx = _x;
            ny = _y;
            nz = _z;
        }

        void st(float _s, float _t) {
            s = _s;
            t = _t;
        }

        void computeNorm(Vertex v1, Vertex v2) {
            float dx = v1.x - v2.x;
            float dy = v1.y - v2.y;
            float dz = v1.z - v2.z;
            float len = (float)java.lang.Math.sqrt(dx*dx + dy*dy + dz*dz);
            dx /= len;
            dy /= len;
            dz /= len;

            nx = dx * dz;
            ny = dy * dz;
            nz = (float)java.lang.Math.sqrt(dx*dx + dy*dy);

            len = (float)java.lang.Math.sqrt(nx*nx + ny*ny + nz*nz);
            nx /= len;
            ny /= len;
            nz /= len;
        }
    }

    int[] mTriangleOffsets;
    float[] mTriangleOffsetsTex;
    int mTriangleOffsetsCount;

    SimpleMesh init(RenderScript rs)
    {
        float vtx[] = new float[] {
            60.431003f, 124.482050f,
            60.862074f, 120.872604f,
            61.705303f, 117.336662f,
            62.949505f, 113.921127f,
            64.578177f, 110.671304f,
            66.569716f, 107.630302f,
            68.897703f, 104.838457f,
            71.531259f, 102.332803f,
            74.435452f, 100.146577f,
            77.571757f, 98.308777f,
            80.898574f, 96.843781f,
            84.371773f, 95.771023f,
            87.945283f, 95.104731f,
            98.958994f, 95.267098f,
            109.489523f, 98.497596f,
            118.699582f, 104.539366f,
            125.856872f, 112.912022f,
            130.392311f, 122.949849f,
            131.945283f, 133.854731f,
            130.392311f, 144.759613f,
            125.856872f, 154.797439f,
            118.699582f, 163.170096f,
            109.489523f, 169.211866f,
            98.958994f, 172.442364f,
            87.945283f, 172.604731f,
            72.507313f, 172.672927f,
            57.678920f, 168.377071f,
            44.668135f, 160.067134f,
            34.534908f, 148.420104f,
            28.104767f, 134.384831f,
            25.901557f, 119.104731f,
            28.104767f, 103.824631f,
            34.534908f, 89.789358f,
            44.668135f, 78.142327f,
            57.678920f, 69.832390f,
            72.507313f, 65.536534f,
            87.945283f, 65.604731f,
            106.918117f, 65.688542f,
            125.141795f, 60.409056f,
            141.131686f, 50.196376f,
            153.585137f, 35.882502f,
            161.487600f, 18.633545f,
            164.195283f, -0.145269f,
            161.487600f, -18.924084f,
            153.585137f, -36.173040f,
            141.131686f, -50.486914f,
            125.141795f, -60.699594f,
            106.918117f, -65.979081f,
            87.945283f, -65.895269f,
            80f, -65.895269f,
            60f, -65.895269f,
            40f, -65.895269f,
            20f, -65.895269f,
            0f, -65.895269f,
            -20f, -65.895269f,
            -40f, -65.895269f,
            -60f, -65.895269f,
            -80f, -65.895269f,
            -87.945283f, -65.895269f,
            -106.918117f, -65.979081f,
            -125.141795f, -60.699594f,
            -141.131686f, -50.486914f,
            -153.585137f, -36.173040f,
            -161.487600f, -18.924084f,
            -164.195283f, -0.145269f,
            -161.487600f, 18.633545f,
             -153.585137f, 35.882502f,
             -141.131686f, 50.196376f,
             -125.141795f, 60.409056f,
             -106.918117f, 65.688542f,
             -87.945283f, 65.604731f,
             -72.507313f, 65.536534f,
             -57.678920f, 69.832390f,
             -44.668135f, 78.142327f,
             -34.534908f, 89.789358f,
             -28.104767f, 103.824631f,
             -25.901557f, 119.104731f,
             -28.104767f, 134.384831f,
             -34.534908f, 148.420104f,
             -44.668135f, 160.067134f,
             -57.678920f, 168.377071f,
             -72.507313f, 172.672927f,
             -87.945283f, 172.604731f,
             -98.958994f, 172.442364f,
             -109.489523f, 169.211866f,
             -118.699582f, 163.170096f,
             -125.856872f, 154.797439f,
             -130.392311f, 144.759613f,
             -131.945283f, 133.854731f,
             -130.392311f, 122.949849f,
             -125.856872f, 112.912022f,
             -118.699582f, 104.539366f,
             -109.489523f, 98.497596f,
             -98.958994f, 95.267098f,
             -87.945283f, 95.104731f,
             -84.371773f, 95.771023f,
             -80.898574f, 96.843781f,
             -77.571757f, 98.308777f,
             -74.435452f, 100.146577f,
             -71.531259f, 102.332803f,
             -68.897703f, 104.838457f,
             -66.569716f, 107.630302f,
             -64.578177f, 110.671304f,
             -62.949505f, 113.921127f,
             -61.705303f, 117.336662f,
             -60.862074f, 120.872604f,
             -60.431003f, 124.482050f
        };


        mTriangleOffsets = new int[64];
        mTriangleOffsetsTex = new float[64];

        mTriangleOffsets[0] = 0;
        mTriangleOffsetsCount = 1;

        Vertex t = new Vertex();
        t.nxyz(1, 0, 0);
        int count = vtx.length / 2;

        SimpleMesh.TriangleMeshBuilder tm = new SimpleMesh.TriangleMeshBuilder(
            rs, 3,
            SimpleMesh.TriangleMeshBuilder.NORMAL | SimpleMesh.TriangleMeshBuilder.TEXTURE_0);

        float runningS = 0;
        for (int ct=0; ct < (count-1); ct++) {
            t.x = -vtx[ct*2] / 100.f;
            t.z = vtx[ct*2+1] / 100.f;
            t.s = runningS;
            t.nx =  (vtx[ct*2+3] - vtx[ct*2 +1]);
            t.ny =  (vtx[ct*2+2] - vtx[ct*2   ]);
            float len = (float)java.lang.Math.sqrt(t.nx * t.nx + t.ny * t.ny);
            runningS += len / 100;
            t.nx /= len;
            t.ny /= len;
            t.y = -0.5f;
            t.t = 0;
            tm.setNormal(t.nx, t.ny, t.nz);
            tm.setTexture(t.s, t.t);
            tm.addVertex(t.x, t.y, t.z);
            //android.util.Log.e("rs", "vtx x="+t.x+" y="+t.y+" z="+t.z+" s="+t.s+" t="+t.t);
            t.y = .5f;
            t.t = 1;
            tm.setTexture(t.s, t.t);
            tm.addVertex(t.x, t.y, t.z);
            //android.util.Log.e("rs", "vtx x="+t.x+" y="+t.y+" z="+t.z+" s="+t.s+" t="+t.t);

            if((runningS*2) > mTriangleOffsetsCount) {
                mTriangleOffsets[mTriangleOffsetsCount] = ct*2 * 3;
                mTriangleOffsetsTex[mTriangleOffsetsCount] = t.s;
                mTriangleOffsetsCount ++;
            }
        }

        count = (count * 2 - 2);
        for (int ct=0; ct < (count-2); ct+= 2) {
            tm.addTriangle(ct, ct+1, ct+2);
            tm.addTriangle(ct+1, ct+3, ct+2);
        }
        return tm.create();
    }


}

