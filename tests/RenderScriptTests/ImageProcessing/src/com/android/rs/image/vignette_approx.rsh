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

static float2 neg_center, axis_scale, inv_dimensions;
static float sloped_neg_range, sloped_inv_max_dist, shade, opp_shade;

void init_vignette(uint32_t dim_x, uint32_t dim_y, float center_x, float center_y,
        float desired_scale, float desired_shade, float desired_slope) {

    neg_center.x = -center_x;
    neg_center.y = -center_y;
    inv_dimensions.x = 1.f / (float)dim_x;
    inv_dimensions.y = 1.f / (float)dim_y;

    axis_scale = (float2)1.f;
    if (dim_x > dim_y)
        axis_scale.y = (float)dim_y / (float)dim_x;
    else
        axis_scale.x = (float)dim_x / (float)dim_y;

    const float max_dist = 0.5f * length(axis_scale);
    sloped_inv_max_dist = desired_slope * 1.f/max_dist;

    // Range needs to be between 1.3 to 0.6. When scale is zero then range is
    // 1.3 which means no vignette at all because the luminousity difference is
    // less than 1/256.  Expect input scale to be between 0.0 and 1.0.
    const float neg_range = 0.7f*sqrt(desired_scale) - 1.3f;
    sloped_neg_range = exp(neg_range * desired_slope);

    shade = desired_shade;
    opp_shade = 1.f - desired_shade;
}

uchar4 __attribute__((kernel)) root(uchar4 in, uint32_t x, uint32_t y) {
    // Convert x and y to floating point coordinates with center as origin
    const float4 fin = convert_float4(in);
    const float2 inCoord = {(float)x, (float)y};
    const float2 coord = mad(inCoord, inv_dimensions, neg_center);
    const float sloped_dist_ratio = fast_length(axis_scale * coord)  * sloped_inv_max_dist;
    // TODO:  add half_exp once implemented
    const float lumen = opp_shade + shade * half_recip(1.f + sloped_neg_range * exp(sloped_dist_ratio));
    float4 fout;
    fout.rgb = fin.rgb * lumen;
    fout.w = fin.w;
    return convert_uchar4(fout);
}

