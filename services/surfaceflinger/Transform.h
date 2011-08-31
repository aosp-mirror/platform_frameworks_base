/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_TRANSFORM_H
#define ANDROID_TRANSFORM_H

#include <stdint.h>
#include <sys/types.h>

#include <ui/Point.h>
#include <ui/Rect.h>

#include <hardware/hardware.h>

namespace android {

class Region;

// ---------------------------------------------------------------------------

class Transform
{
public:
                    Transform();
                    Transform(const Transform&  other);
           explicit Transform(uint32_t orientation);
                    ~Transform();

            enum orientation_flags {
                ROT_0   = 0x00000000,
                FLIP_H  = HAL_TRANSFORM_FLIP_H,
                FLIP_V  = HAL_TRANSFORM_FLIP_V,
                ROT_90  = HAL_TRANSFORM_ROT_90,
                ROT_180 = FLIP_H|FLIP_V,
                ROT_270 = ROT_180|ROT_90,
                ROT_INVALID = 0x80
            };

            enum type_mask {
                IDENTITY            = 0,
                TRANSLATE           = 0x1,
                ROTATE              = 0x2,
                SCALE               = 0x4,
                UNKNOWN             = 0x8
            };

            // query the transform
            bool        transformed() const;
            bool        preserveRects() const;
            uint32_t    getType() const;
            uint32_t    getOrientation() const;

            float const* operator [] (int i) const;  // returns column i
            float   tx() const;
            float   ty() const;

            // modify the transform
            void        reset();
            void        set(float tx, float ty);
            void        set(float a, float b, float c, float d);
            status_t    set(uint32_t flags, float w, float h);

            // transform data
            Rect    makeBounds(int w, int h) const;
            void    transform(float* point, int x, int y) const;
            Region  transform(const Region& reg) const;
            Transform operator * (const Transform& rhs) const;

            // for debugging
            void dump(const char* name) const;

private:
    struct vec3 {
        float v[3];
        inline vec3() { }
        inline vec3(float a, float b, float c) {
            v[0] = a; v[1] = b; v[2] = c;
        }
        inline float operator [] (int i) const { return v[i]; }
        inline float& operator [] (int i) { return v[i]; }
    };
    struct vec2 {
        float v[2];
        inline vec2() { }
        inline vec2(float a, float b) {
            v[0] = a; v[1] = b;
        }
        inline float operator [] (int i) const { return v[i]; }
        inline float& operator [] (int i) { return v[i]; }
    };
    struct mat33 {
        vec3 v[3];
        inline const vec3& operator [] (int i) const { return v[i]; }
        inline vec3& operator [] (int i) { return v[i]; }
    };

    enum { UNKNOWN_TYPE = 0x80000000 };

    // assumes the last row is < 0 , 0 , 1 >
    vec2 transform(const vec2& v) const;
    vec3 transform(const vec3& v) const;
    Rect transform(const Rect& bounds) const;
    uint32_t type() const;
    static bool absIsOne(float f);
    static bool isZero(float f);

    mat33               mMatrix;
    mutable uint32_t    mType;
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif /* ANDROID_TRANSFORM_H */
