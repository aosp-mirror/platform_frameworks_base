/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_NOISE_H
#define ANDROID_RS_NOISE_H

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

void SC_normalizef2(float v[]);
void SC_normalizef3(float v[]);
float SC_noisef(float x);
float SC_noisef2(float x, float y);
float SC_noisef3(float x, float y, float z);
float SC_turbulencef2(float x, float y, float octaves);
float SC_turbulencef3(float x, float y, float z, float octaves);

}
}

#endif
