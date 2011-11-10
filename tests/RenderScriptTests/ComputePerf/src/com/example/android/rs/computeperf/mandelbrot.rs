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
#pragma rs java_package_name(com.example.android.rs.computeperf)

const int gMaxIteration = 500;
const int gDimX = 1024;
const int gDimY = 1024;

void root(uchar4 *v_out, uint32_t x, uint32_t y) {
    float2 p;
    p.x = -2.5f + ((float)x / gDimX) * 3.5f;
    p.y = -1.f + ((float)y / gDimY) * 2.f;

    float2 t = 0;
    float2 t2 = t * t;
    int iteration = 0;
    while((t2.x + t2.y < 4.f) && (iteration < gMaxIteration)) {
        float xtemp = t2.x - t2.y + p.x;
        t.y = 2 * t.x * t.y + p.y;
        t.x = xtemp;
        iteration++;
        t2 = t * t;
    }

    if(iteration >= gMaxIteration) {
        *v_out = 0;
    } else {
        *v_out = (uchar4){iteration & 0xff, (iteration >> 6) & 0xff, 0x8f, 0xff};
    }
}
