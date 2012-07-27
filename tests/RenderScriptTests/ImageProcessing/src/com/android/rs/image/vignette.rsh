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

static float2 scale;
static float2 center, dimensions;
static float range, inv_max_dist, shade, slope;

void init_vignette(uint32_t dim_x, uint32_t dim_y, float center_x, float center_y,
        float desired_scale, float desired_shade, float desired_slope) {

    center.x = center_x;
    center.y = center_y;
    dimensions.x = (float)dim_x;
    dimensions.y = (float)dim_y;

    float max_dist = 0.5;
    if (dim_x > dim_y) {
        scale.x = 1.0;
        scale.y = dimensions.y / dimensions.x;
        max_dist *= sqrt(scale.y*scale.y + 1);
    } else {
        scale.x = dimensions.x / dimensions.y;
        scale.y = 1.0;
        max_dist *= sqrt(scale.x*scale.x + 1);
    }
    inv_max_dist = 1.0/max_dist;
    // Range needs to be between 1.3 to 0.6. When scale is zero then range is
    // 1.3 which means no vignette at all because the luminousity difference is
    // less than 1/256.  Expect input scale to be between 0.0 and 1.0.
    range = 1.3 - 0.7*sqrt(desired_scale);
    shade = desired_shade;
    slope = desired_slope;
}

void root(const uchar4 *in, uchar4 *out, uint32_t x, uint32_t y) {
    // Convert x and y to floating point coordinates with center as origin
    const float4 fin = rsUnpackColor8888(*in);
    float2 coord;
    coord.x = (float)x / dimensions.x;
    coord.y = (float)y / dimensions.y;
    coord -= center;
    const float dist = length(scale * coord);
    const float lumen = shade / (1.0 + exp((dist * inv_max_dist - range) * slope)) + (1.0 - shade);
    float4 fout;
    fout.rgb = fin.rgb * lumen;
    fout.w = fin.w;
    *out = rsPackColorTo8888(fout);
}

