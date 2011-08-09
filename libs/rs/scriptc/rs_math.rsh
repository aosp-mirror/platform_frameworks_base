/*
 * Copyright (C) 2011 The Android Open Source Project
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

/** @file rs_math.rsh
 *  \brief todo-jsams
 *
 *  todo-jsams
 *
 */

#ifndef __RS_MATH_RSH__
#define __RS_MATH_RSH__


/**
 * Return a random value between 0 (or min_value) and max_malue.
 */
extern int __attribute__((overloadable))
    rsRand(int max_value);
/**
 * \overload
 */
extern int __attribute__((overloadable))
    rsRand(int min_value, int max_value);
/**
 * \overload
 */
extern float __attribute__((overloadable))
    rsRand(float max_value);
/**
 * \overload
 */
extern float __attribute__((overloadable))
    rsRand(float min_value, float max_value);

/**
 * Returns the fractional part of a float
 */
extern float __attribute__((overloadable))
    rsFrac(float);


/////////////////////////////////////////////////////
// int ops
/////////////////////////////////////////////////////

/**
 * Clamp the value amount between low and high.
 *
 * @param amount  The value to clamp
 * @param low
 * @param high
 */
_RS_RUNTIME uint __attribute__((overloadable, always_inline)) rsClamp(uint amount, uint low, uint high);

/**
 * \overload
 */
_RS_RUNTIME int __attribute__((overloadable, always_inline)) rsClamp(int amount, int low, int high);
/**
 * \overload
 */
_RS_RUNTIME ushort __attribute__((overloadable, always_inline)) rsClamp(ushort amount, ushort low, ushort high);
/**
 * \overload
 */
_RS_RUNTIME short __attribute__((overloadable, always_inline)) rsClamp(short amount, short low, short high);
/**
 * \overload
 */
_RS_RUNTIME uchar __attribute__((overloadable, always_inline)) rsClamp(uchar amount, uchar low, uchar high);
/**
 * \overload
 */
_RS_RUNTIME char __attribute__((overloadable, always_inline)) rsClamp(char amount, char low, char high);


/**
 * Computes 6 frustum planes from the view projection matrix
 * @param viewProj matrix to extract planes from
 * @param left plane
 * @param right plane
 * @param top plane
 * @param bottom plane
 * @param near plane
 * @param far plane
 */
__inline__ static void __attribute__((overloadable, always_inline))
rsExtractFrustumPlanes(const rs_matrix4x4 *viewProj,
                         float4 *left, float4 *right,
                         float4 *top, float4 *bottom,
                         float4 *near, float4 *far) {
    // x y z w = a b c d in the plane equation
    left->x = viewProj->m[3] + viewProj->m[0];
    left->y = viewProj->m[7] + viewProj->m[4];
    left->z = viewProj->m[11] + viewProj->m[8];
    left->w = viewProj->m[15] + viewProj->m[12];

    right->x = viewProj->m[3] - viewProj->m[0];
    right->y = viewProj->m[7] - viewProj->m[4];
    right->z = viewProj->m[11] - viewProj->m[8];
    right->w = viewProj->m[15] - viewProj->m[12];

    top->x = viewProj->m[3] - viewProj->m[1];
    top->y = viewProj->m[7] - viewProj->m[5];
    top->z = viewProj->m[11] - viewProj->m[9];
    top->w = viewProj->m[15] - viewProj->m[13];

    bottom->x = viewProj->m[3] + viewProj->m[1];
    bottom->y = viewProj->m[7] + viewProj->m[5];
    bottom->z = viewProj->m[11] + viewProj->m[9];
    bottom->w = viewProj->m[15] + viewProj->m[13];

    near->x = viewProj->m[3] + viewProj->m[2];
    near->y = viewProj->m[7] + viewProj->m[6];
    near->z = viewProj->m[11] + viewProj->m[10];
    near->w = viewProj->m[15] + viewProj->m[14];

    far->x = viewProj->m[3] - viewProj->m[2];
    far->y = viewProj->m[7] - viewProj->m[6];
    far->z = viewProj->m[11] - viewProj->m[10];
    far->w = viewProj->m[15] - viewProj->m[14];

    float len = length(left->xyz);
    *left /= len;
    len = length(right->xyz);
    *right /= len;
    len = length(top->xyz);
    *top /= len;
    len = length(bottom->xyz);
    *bottom /= len;
    len = length(near->xyz);
    *near /= len;
    len = length(far->xyz);
    *far /= len;
}

/**
 * Checks if a sphere is withing the 6 frustum planes
 * @param sphere float4 representing the sphere
 * @param left plane
 * @param right plane
 * @param top plane
 * @param bottom plane
 * @param near plane
 * @param far plane
 */
__inline__ static bool __attribute__((overloadable, always_inline))
rsIsSphereInFrustum(float4 *sphere,
                      float4 *left, float4 *right,
                      float4 *top, float4 *bottom,
                      float4 *near, float4 *far) {

    float distToCenter = dot(left->xyz, sphere->xyz) + left->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(right->xyz, sphere->xyz) + right->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(top->xyz, sphere->xyz) + top->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(bottom->xyz, sphere->xyz) + bottom->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(near->xyz, sphere->xyz) + near->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(far->xyz, sphere->xyz) + far->w;
    if (distToCenter < -sphere->w) {
        return false;
    }
    return true;
}


/**
 * Pack floating point (0-1) RGB values into a uchar4.  The alpha component is
 * set to 255 (1.0).
 *
 * @param r
 * @param g
 * @param b
 *
 * @return uchar4
 */
_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b);

/**
 * Pack floating point (0-1) RGBA values into a uchar4.
 *
 * @param r
 * @param g
 * @param b
 * @param a
 *
 * @return uchar4
 */
_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b, float a);

/**
 * Pack floating point (0-1) RGB values into a uchar4.  The alpha component is
 * set to 255 (1.0).
 *
 * @param color
 *
 * @return uchar4
 */
_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float3 color);

/**
 * Pack floating point (0-1) RGBA values into a uchar4.
 *
 * @param color
 *
 * @return uchar4
 */
_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float4 color);

/**
 * Unpack a uchar4 color to float4.  The resulting float range will be (0-1).
 *
 * @param c
 *
 * @return float4
 */
_RS_RUNTIME float4 rsUnpackColor8888(uchar4 c);


#endif
