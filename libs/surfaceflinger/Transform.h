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

#include <GLES/gl.h>

#include <core/SkMatrix.h>

namespace android {

class Region;

// ---------------------------------------------------------------------------

class Transform
{
public:
                    Transform();
                    Transform(const Transform&  other);
                    ~Transform();

            enum orientation_flags {
                ROT_0   = 0x00000000,
                FLIP_H  = 0x00000001,
                FLIP_V  = 0x00000002,
                ROT_90  = 0x00000004,
                ROT_180 = FLIP_H|FLIP_V,
                ROT_270 = ROT_180|ROT_90,
                ROT_INVALID = 0x80000000
            };

            bool    transformed() const;
            int32_t getOrientation() const;
            bool    preserveRects() const;
            
            int     tx() const;
            int     ty() const;
        
            void    reset();
            void    set(float xx, float xy, float yx, float yy);
            void    set(int tx, int ty);

            Rect    makeBounds(int w, int h) const;
            void    transform(GLfixed* point, int x, int y) const;
            Region  transform(const Region& reg) const;
            Rect    transform(const Rect& bounds) const;

            Transform operator * (const Transform& rhs) const;
            float operator [] (int i) const;

    inline uint32_t getType() const { return type(); }
            
    inline Transform(bool) : mType(0xFF) { };

private:
    uint8_t     type() const;

private:
            SkMatrix    mTransform;
    mutable uint32_t    mType;      
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif /* ANDROID_TRANSFORM_H */
