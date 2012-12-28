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
static int4 gDims;
static int4 gFracMask;
static int4 gFracBits;

void setCube(rs_allocation c) {
    gCube = c;
    gDims.x = rsAllocationGetDimX(gCube);
    gDims.y = rsAllocationGetDimY(gCube);
    gDims.z = rsAllocationGetDimZ(gCube);
    gDims.w = 0;

    gFracMask = gDims - 1;
    gFracBits = (int4)32 - clz(gFracMask);

    rsDebug("dims", gDims);
    rsDebug("gFracMask", gFracMask);
    rsDebug("gFracBits", gFracBits);
}

void root(const uchar4 *in, uchar4 *out) {
    //rsDebug("root", in);

    int4 coord1 = convert_int4(*in);
    int4 coord2 = min(coord1 + 1, gDims);

    uchar4 v1 = rsGetElementAt_uchar4(gCube, coord1.x >> 3, coord1.y >> 3, coord1.z >> 4);

    //rsDebug("coord1", coord1);
    //rsDebug("coord2", coord2);

    *out = v1;
}

