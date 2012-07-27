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

rs_allocation in_alloc;
rs_sampler sampler;

static float2 center, dimensions;
static float2 scale;
static float alpha;
static float radius2;
static float factor;

void init_filter(uint32_t dim_x, uint32_t dim_y, float focus_x, float focus_y, float k) {
    center.x = focus_x;
    center.y = focus_y;
    dimensions.x = (float)dim_x;
    dimensions.y = (float)dim_y;

    alpha = k * 2.0 + 0.75;
    float bound2 = 0.25;
    if (dim_x > dim_y) {
        scale.x = 1.0;
        scale.y = dimensions.y / dimensions.x;
        bound2 *= (scale.y*scale.y + 1);
    } else {
        scale.x = dimensions.x / dimensions.y;
        scale.y = 1.0;
    }
    const float bound = sqrt(bound2);
    const float radius = 1.15 * bound;
    radius2 = radius*radius;
    const float max_radian = 0.5f * M_PI - atan(alpha / bound * sqrt(radius2 - bound2));
    factor = bound / max_radian;
}

void root(uchar4 *out, uint32_t x, uint32_t y) {
    // Convert x and y to floating point coordinates with center as origin
    float2 coord;
    coord.x = (float)x / dimensions.x;
    coord.y = (float)y / dimensions.y;
    coord -= center;
    const float dist = length(scale * coord);
    const float radian = M_PI_2 - atan((alpha * sqrt(radius2 - dist * dist)) / dist);
    const float scalar = radian * factor / dist;
    const float2 new_coord = coord * scalar + center;
    const float4 fout = rsSample(in_alloc, sampler, new_coord);
    *out = rsPackColorTo8888(fout);
}

