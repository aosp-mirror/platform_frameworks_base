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
#pragma rs java_package_name(com.android.rs.image)

uint32_t gMaxIteration = 500;
uint32_t gDimX = 1024;
uint32_t gDimY = 1024;

float lowerBoundX = -2.f;
float lowerBoundY = -2.f;
float scaleFactor = 4.f;

uchar4 __attribute__((kernel)) root(uint32_t x, uint32_t y) {
  float2 p;
  p.x = lowerBoundX + ((float)x / gDimX) * scaleFactor;
  p.y = lowerBoundY + ((float)y / gDimY) * scaleFactor;

  float2 t = 0;
  float2 t2 = t * t;
  int iter = 0;
  while((t2.x + t2.y < 4.f) && (iter < gMaxIteration)) {
    float xtemp = t2.x - t2.y + p.x;
    t.y = 2 * t.x * t.y + p.y;
    t.x = xtemp;
    iter++;
    t2 = t * t;
  }

  if(iter >= gMaxIteration) {
    // write a non-transparent black pixel
    return (uchar4){0, 0, 0, 0xff};
  } else {
    float mi3 = gMaxIteration / 3.f;
    if (iter <= (gMaxIteration / 3))
      return (uchar4){0xff * (iter / mi3), 0, 0, 0xff};
    else if (iter <= (((gMaxIteration / 3) * 2)))
      return (uchar4){0xff - (0xff * ((iter - mi3) / mi3)),
                      (0xff * ((iter - mi3) / mi3)), 0, 0xff};
    else
      return (uchar4){0, 0xff - (0xff * ((iter - (mi3 * 2)) / mi3)),
                      (0xff * ((iter - (mi3 * 2)) / mi3)), 0xff};
  }
}
