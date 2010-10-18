/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_COMPARE_H
#define ANDROID_UI_COMPARE_H

#include <cmath>

#define EPSILON 0.00001f

#define ALMOST_EQUAL(u, v) (fabs((u) - (v)) < EPSILON)

/**
 * Compare floats.
 */
#define FLOAT_COMPARE(a) \
    if (a < rhs.a) return true; \
    if (ALMOST_EQUAL(a, rhs.a))

/**
 * Compare integers.
 */
#define INT_COMPARE(a) \
    if (a < rhs.a) return true; \
    if (a == rhs.a)

#endif // ANDROID_UI_COMPARE_H
