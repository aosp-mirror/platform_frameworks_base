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

float bright = 0.f;

static unsigned char contrastClamp(int c)
{
    int N = 255;
    c &= ~(c >> 31);
    c -= N;
    c &= (c >> 31);
    c += N;
    return  (unsigned char) c;
}

void exposure(const uchar4 *in, uchar4 *out)
{
    int m = 255 - bright;
    out->r = contrastClamp((255 * in->r)/m);
    out->g = contrastClamp((255 * in->g)/m);
    out->b = contrastClamp((255 * in->b)/m);
}
