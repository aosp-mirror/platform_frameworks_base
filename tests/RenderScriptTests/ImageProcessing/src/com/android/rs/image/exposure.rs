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

static float bright = 0.f;

void setBright(float v) {
    bright = 255.f / (255.f - v);
}

void exposure(const uchar4 *in, uchar4 *out)
{
    out->r = rsClamp((int)(bright * in->r), 0, 255);
    out->g = rsClamp((int)(bright * in->g), 0, 255);
    out->b = rsClamp((int)(bright * in->b), 0, 255);
}

