/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "ip.rsh"
#pragma rs_fp_relaxed


static rs_allocation gCube;
static int4 gDims;
static int4 gCoordMul;


void setCube(rs_allocation c) {
    gCube = c;
    gDims.x = rsAllocationGetDimX(gCube);
    gDims.y = rsAllocationGetDimY(gCube);
    gDims.z = rsAllocationGetDimZ(gCube);
    gDims.w = 0;

    float4 m = (float4)(1.f / 255.f) * convert_float4(gDims - 1);
    gCoordMul = convert_int4(m * (float4)0x10000);

    rsDebug("dims", gDims);
    rsDebug("gCoordMul", gCoordMul);
}

void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    //rsDebug("root", in);

    int4 baseCoord = convert_int4(*in) * gCoordMul;
    int4 coord1 = baseCoord >> (int4)16;
    int4 coord2 = min(coord1 + 1, gDims - 1);

    int4 weight2 = baseCoord & 0xffff;
    int4 weight1 = (int4)0x10000 - weight2;

    uint4 v000 = convert_uint4(rsGetElementAt_uchar4(gCube, coord1.x, coord1.y, coord1.z));
    uint4 v100 = convert_uint4(rsGetElementAt_uchar4(gCube, coord2.x, coord1.y, coord1.z));
    uint4 v010 = convert_uint4(rsGetElementAt_uchar4(gCube, coord1.x, coord2.y, coord1.z));
    uint4 v110 = convert_uint4(rsGetElementAt_uchar4(gCube, coord2.x, coord2.y, coord1.z));
    uint4 v001 = convert_uint4(rsGetElementAt_uchar4(gCube, coord1.x, coord1.y, coord2.z));
    uint4 v101 = convert_uint4(rsGetElementAt_uchar4(gCube, coord2.x, coord1.y, coord2.z));
    uint4 v011 = convert_uint4(rsGetElementAt_uchar4(gCube, coord1.x, coord2.y, coord2.z));
    uint4 v111 = convert_uint4(rsGetElementAt_uchar4(gCube, coord2.x, coord2.y, coord2.z));

    uint4 yz00 = ((v000 * weight1.x) + (v100 * weight2.x)) >> (int4)8;
    uint4 yz10 = ((v010 * weight1.x) + (v110 * weight2.x)) >> (int4)8;
    uint4 yz01 = ((v001 * weight1.x) + (v101 * weight2.x)) >> (int4)8;
    uint4 yz11 = ((v011 * weight1.x) + (v111 * weight2.x)) >> (int4)8;

    uint4 z0 = ((yz00 * weight1.y) + (yz10 * weight2.y)) >> (int4)16;
    uint4 z1 = ((yz01 * weight1.y) + (yz11 * weight2.y)) >> (int4)16;

    uint4 v = ((z0 * weight1.z) + (z1 * weight2.z)) >> (int4)16;
    uint4 v2 = (v + 0x7f) >> (int4)8;

    *out = convert_uchar4(v2);
    out->a = 0xff;

    #if 0
    if (in->r != out->r) {
        rsDebug("dr", in->r - out->r);
        //rsDebug("in", convert_int4(*in));
        //rsDebug("coord1", coord1);
        //rsDebug("coord2", coord2);
        //rsDebug("weight1", weight1);
        //rsDebug("weight2", weight2);
        //rsDebug("yz00", yz00);
        //rsDebug("z0", z0);
        //rsDebug("v", v);
        //rsDebug("v2", v2);
        //rsDebug("out", convert_int4(*out));
    }
    #endif
}

