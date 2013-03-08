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

static float brightM = 0.f;
static float brightC = 0.f;

void setBright(float v) {
    brightM = pow(2.f, v / 100.f);
    brightC = 127.f - brightM * 127.f;
}

void contrast(const uchar4 *in, uchar4 *out)
{
#if 0
    out->r = rsClamp((int)(brightM * in->r + brightC), 0, 255);
    out->g = rsClamp((int)(brightM * in->g + brightC), 0, 255);
    out->b = rsClamp((int)(brightM * in->b + brightC), 0, 255);
#else
    float3 v = convert_float3(in->rgb) * brightM + brightC;
    out->rgb = convert_uchar3(clamp(v, 0.f, 255.f));
#endif
}
