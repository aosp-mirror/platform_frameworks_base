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

const static float3 gMonoMult = {0.299f, 0.587f, 0.114f};

uchar4 __attribute__((kernel)) root(uchar4 v_in) {
    float4 f4 = rsUnpackColor8888(v_in);

    float3 mono = dot(f4.rgb, gMonoMult);
    return rsPackColorTo8888(mono);
}

uchar __attribute__((kernel)) toU8(uchar4 v_in) {
    float4 f4 = convert_float4(v_in);
    return (uchar)dot(f4.rgb, gMonoMult);
}

uchar4 __attribute__((kernel)) toU8_4(uchar v_in) {
    return (uchar4)v_in;
}

