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

/** @file rs_matrix.rsh
 *  \brief Quaternion routines
 *
 *
 */

#ifndef __RS_QUATERNION_RSH__
#define __RS_QUATERNION_RSH__


/**
 * Set the quaternion components
 * @param w component
 * @param x component
 * @param y component
 * @param z component
 */
static void __attribute__((overloadable))
rsQuaternionSet(rs_quaternion *q, float w, float x, float y, float z) {
    q->w = w;
    q->x = x;
    q->y = y;
    q->z = z;
}

/**
 * Set the quaternion from another quaternion
 * @param q destination quaternion
 * @param rhs source quaternion
 */
static void __attribute__((overloadable))
rsQuaternionSet(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w = rhs->w;
    q->x = rhs->x;
    q->y = rhs->y;
    q->z = rhs->z;
}

/**
 * Multiply quaternion by a scalar
 * @param q quaternion to multiply
 * @param s scalar
 */
static void __attribute__((overloadable))
rsQuaternionMultiply(rs_quaternion *q, float s) {
    q->w *= s;
    q->x *= s;
    q->y *= s;
    q->z *= s;
}

/**
 * Multiply quaternion by another quaternion
 * @param q destination quaternion
 * @param rhs right hand side quaternion to multiply by
 */
static void __attribute__((overloadable))
rsQuaternionMultiply(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w = -q->x*rhs->x - q->y*rhs->y - q->z*rhs->z + q->w*rhs->w;
    q->x =  q->x*rhs->w + q->y*rhs->z - q->z*rhs->y + q->w*rhs->x;
    q->y = -q->x*rhs->z + q->y*rhs->w + q->z*rhs->x + q->w*rhs->y;
    q->z =  q->x*rhs->y - q->y*rhs->x + q->z*rhs->w + q->w*rhs->z;
}

/**
 * Add two quaternions
 * @param q destination quaternion to add to
 * @param rsh right hand side quaternion to add
 */
static void
rsQuaternionAdd(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w *= rhs->w;
    q->x *= rhs->x;
    q->y *= rhs->y;
    q->z *= rhs->z;
}

/**
 * Loads a quaternion that represents a rotation about an arbitrary unit vector
 * @param q quaternion to set
 * @param rot angle to rotate by
 * @param x component of a vector
 * @param y component of a vector
 * @param x component of a vector
 */
static void
rsQuaternionLoadRotateUnit(rs_quaternion *q, float rot, float x, float y, float z) {
    rot *= (float)(M_PI / 180.0f) * 0.5f;
    float c = cos(rot);
    float s = sin(rot);

    q->w = c;
    q->x = x * s;
    q->y = y * s;
    q->z = z * s;
}

/**
 * Loads a quaternion that represents a rotation about an arbitrary vector
 * (doesn't have to be unit)
 * @param q quaternion to set
 * @param rot angle to rotate by
 * @param x component of a vector
 * @param y component of a vector
 * @param x component of a vector
 */
static void
rsQuaternionLoadRotate(rs_quaternion *q, float rot, float x, float y, float z) {
    const float len = x*x + y*y + z*z;
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        x *= recipLen;
        y *= recipLen;
        z *= recipLen;
    }
    rsQuaternionLoadRotateUnit(q, rot, x, y, z);
}

/**
 * Conjugates the quaternion
 * @param q quaternion to conjugate
 */
static void
rsQuaternionConjugate(rs_quaternion *q) {
    q->x = -q->x;
    q->y = -q->y;
    q->z = -q->z;
}

/**
 * Dot product of two quaternions
 * @param q0 first quaternion
 * @param q1 second quaternion
 * @return dot product between q0 and q1
 */
static float
rsQuaternionDot(const rs_quaternion *q0, const rs_quaternion *q1) {
    return q0->w*q1->w + q0->x*q1->x + q0->y*q1->y + q0->z*q1->z;
}

/**
 * Normalizes the quaternion
 * @param q quaternion to normalize
 */
static void
rsQuaternionNormalize(rs_quaternion *q) {
    const float len = rsQuaternionDot(q, q);
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        rsQuaternionMultiply(q, recipLen);
    }
}

/**
 * Performs spherical linear interpolation between two quaternions
 * @param q result quaternion from interpolation
 * @param q0 first param
 * @param q1 second param
 * @param t how much to interpolate by
 */
static void
rsQuaternionSlerp(rs_quaternion *q, const rs_quaternion *q0, const rs_quaternion *q1, float t) {
    if (t <= 0.0f) {
        rsQuaternionSet(q, q0);
        return;
    }
    if (t >= 1.0f) {
        rsQuaternionSet(q, q1);
        return;
    }

    rs_quaternion tempq0, tempq1;
    rsQuaternionSet(&tempq0, q0);
    rsQuaternionSet(&tempq1, q1);

    float angle = rsQuaternionDot(q0, q1);
    if (angle < 0) {
        rsQuaternionMultiply(&tempq0, -1.0f);
        angle *= -1.0f;
    }

    float scale, invScale;
    if (angle + 1.0f > 0.05f) {
        if (1.0f - angle >= 0.05f) {
            float theta = acos(angle);
            float invSinTheta = 1.0f / sin(theta);
            scale = sin(theta * (1.0f - t)) * invSinTheta;
            invScale = sin(theta * t) * invSinTheta;
        } else {
            scale = 1.0f - t;
            invScale = t;
        }
    } else {
        rsQuaternionSet(&tempq1, tempq0.z, -tempq0.y, tempq0.x, -tempq0.w);
        scale = sin(M_PI * (0.5f - t));
        invScale = sin(M_PI * t);
    }

    rsQuaternionSet(q, tempq0.w*scale + tempq1.w*invScale, tempq0.x*scale + tempq1.x*invScale,
                        tempq0.y*scale + tempq1.y*invScale, tempq0.z*scale + tempq1.z*invScale);
}

/**
 * Computes rotation matrix from the normalized quaternion
 * @param m resulting matrix
 * @param p normalized quaternion
 */
static void rsQuaternionGetMatrixUnit(rs_matrix4x4 *m, const rs_quaternion *q) {
    float x2 = 2.0f * q->x * q->x;
    float y2 = 2.0f * q->y * q->y;
    float z2 = 2.0f * q->z * q->z;
    float xy = 2.0f * q->x * q->y;
    float wz = 2.0f * q->w * q->z;
    float xz = 2.0f * q->x * q->z;
    float wy = 2.0f * q->w * q->y;
    float wx = 2.0f * q->w * q->x;
    float yz = 2.0f * q->y * q->z;

    m->m[0] = 1.0f - y2 - z2;
    m->m[1] = xy - wz;
    m->m[2] = xz + wy;
    m->m[3] = 0.0f;

    m->m[4] = xy + wz;
    m->m[5] = 1.0f - x2 - z2;
    m->m[6] = yz - wx;
    m->m[7] = 0.0f;

    m->m[8] = xz - wy;
    m->m[9] = yz - wx;
    m->m[10] = 1.0f - x2 - y2;
    m->m[11] = 0.0f;

    m->m[12] = 0.0f;
    m->m[13] = 0.0f;
    m->m[14] = 0.0f;
    m->m[15] = 1.0f;
}

#endif

