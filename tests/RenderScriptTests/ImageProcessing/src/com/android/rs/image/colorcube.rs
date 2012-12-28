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

#pragma version(1)
#pragma rs java_package_name(com.android.rs.image)
#pragma rs_fp_relaxed


static rs_allocation gCube;
static short4 gDims;
static short4 gFracMask;
static short4 gFracBits;
static short4 gFracShift;
static int4 gFinalShift;
static int4 gFinalAdd;

void setCube(rs_allocation c) {
    gCube = c;
    gDims.x = rsAllocationGetDimX(gCube) - 1;
    gDims.y = rsAllocationGetDimY(gCube) - 1;
    gDims.z = rsAllocationGetDimZ(gCube) - 1;
    gDims.w = 0;

    gFracMask = gDims;
    gFracBits = (short4)32 - clz(gFracMask);
    gFracShift = (short4)8 - gFracBits;

    rsDebug("dims", gDims);
    rsDebug("gFracMask", gFracMask);
    rsDebug("gFracBits", gFracBits);

    gFinalShift = gFracShift.x + gFracShift.y + gFracShift.z;
    gFinalAdd = (((int4)1 << gFinalShift) - (int4)1) >> (int4)1;

    rsDebug("gFinalShift", gFinalShift);
    rsDebug("gFinalAdd", gFinalAdd);

}

void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    //rsDebug("root", in);

    short4 baseCoord = convert_short4(*in);
    short4 coord1 = baseCoord >> gFracShift;
    short4 coord2 = min(coord1 + (short4)1, gDims);

    short4 weight2 = baseCoord - (coord1 << gFracShift);
    short4 weight1 = ((short4)1 << gFracShift) - weight2;

    ushort4 v000 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord1.x, coord1.y, coord1.z));
    ushort4 v100 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord2.x, coord1.y, coord1.z));
    ushort4 v010 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord1.x, coord2.y, coord1.z));
    ushort4 v110 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord2.x, coord2.y, coord1.z));
    ushort4 v001 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord1.x, coord1.y, coord2.z));
    ushort4 v101 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord2.x, coord1.y, coord2.z));
    ushort4 v011 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord1.x, coord2.y, coord2.z));
    ushort4 v111 = convert_ushort4(rsGetElementAt_uchar4(gCube, coord2.x, coord2.y, coord2.z));

    uint4 yz00 = convert_uint4((v000 * weight1.x) + (v100 * weight2.x));
    uint4 yz10 = convert_uint4((v010 * weight1.x) + (v110 * weight2.x));
    uint4 yz01 = convert_uint4((v001 * weight1.x) + (v101 * weight2.x));
    uint4 yz11 = convert_uint4((v011 * weight1.x) + (v111 * weight2.x));

    uint4 z0 = (yz00 * weight1.y) + (yz10 * weight2.y);
    uint4 z1 = (yz01 * weight1.y) + (yz11 * weight2.y);

    uint4 v = (z0 * weight1.z) + (z1 * weight2.z);

    #if 0
    if (x + y < 100) {
        rsDebug("coord1", coord1);
        rsDebug("coord2", coord2);
        rsDebug("weight1", weight1);
        rsDebug("weight2", weight2);
        rsDebug("yz00", yz00);
        rsDebug("z0", z0);
        rsDebug("v", v);
    }
    #endif

    *out = convert_uchar4((v + gFinalAdd) >> gFinalShift);
    out->a = 0xff;
}

