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

static float2 center, neg_center, inv_dimensions, axis_scale;
static float alpha, radius2, factor;

void init_filter(uint32_t dim_x, uint32_t dim_y, float center_x, float center_y, float k) {
    center.x = center_x;
    center.y = center_y;
    neg_center = -center;
    inv_dimensions.x = 1.f / (float)dim_x;
    inv_dimensions.y = 1.f / (float)dim_y;
    alpha = k * 2.0 + 0.75;

    axis_scale = (float2)1.f;
    if (dim_x > dim_y)
        axis_scale.y = (float)dim_y / (float)dim_x;
    else
        axis_scale.x = (float)dim_x / (float)dim_y;

    const float bound2 = 0.25 * (axis_scale.x*axis_scale.x + axis_scale.y*axis_scale.y);
    const float bound = sqrt(bound2);
    const float radius = 1.15 * bound;
    radius2 = radius*radius;
    const float max_radian = M_PI_2 - atan(alpha / bound * sqrt(radius2 - bound2));
    factor = bound / max_radian;
}

void root(uchar4 *out, uint32_t x, uint32_t y) {
    // Convert x and y to floating point coordinates with center as origin
    const float2 inCoord = {(float)x, (float)y};
    const float2 coord = mad(inCoord, inv_dimensions, neg_center);
    const float2 scaledCoord = axis_scale * coord;
    const float dist2 = scaledCoord.x*scaledCoord.x + scaledCoord.y*scaledCoord.y;
    const float inv_dist = half_rsqrt(dist2);
    const float radian = M_PI_2 - atan((alpha * half_sqrt(radius2 - dist2)) * inv_dist);
    const float scalar = radian * factor * inv_dist;
    const float2 new_coord = mad(coord, scalar, center);
    const float4 fout = rsSample(in_alloc, sampler, new_coord);
    *out = rsPackColorTo8888(fout);
}

