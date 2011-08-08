/** @file rs_core.rsh
 *  \brief todo-jsams
 *
 *  todo-jsams
 *
 */
#ifndef __RS_CORE_RSH__
#define __RS_CORE_RSH__

#define _RS_RUNTIME extern

/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, float);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float, float);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float, float, float);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, double);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix4x4 *);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix3x3 *);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix2x2 *);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, int);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, uint);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, long);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, unsigned long);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, long long);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, unsigned long long);
/**
 * Debug function.  Prints a string and value to the log.
 */
extern void __attribute__((overloadable))
    rsDebug(const char *, const void *);
#define RS_DEBUG(a) rsDebug(#a, a)
#define RS_DEBUG_MARKER rsDebug(__FILE__, __LINE__)


/**
 * Debug function.  Prints a string and value to the log.
 */
_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float2 v);
/**
 * Debug function.  Prints a string and value to the log.
 */
_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float3 v);
/**
 * Debug function.  Prints a string and value to the log.
 */
_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float4 v);


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


/////////////////////////////////////////////////////
// Matrix ops
/////////////////////////////////////////////////////

/**
 * Set one element of a matrix.
 *
 * @param m The matrix to be set
 * @param row
 * @param col
 * @param v
 *
 * @return void
 */
_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix4x4 *m, uint32_t row, uint32_t col, float v);
/**
 * \overload
 */
_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v);
/**
 * \overload
 */
_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix2x2 *m, uint32_t row, uint32_t col, float v);

/**
 * Get one element of a matrix.
 *
 * @param m The matrix to read from
 * @param row
 * @param col
 *
 * @return float
 */
_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix4x4 *m, uint32_t row, uint32_t col);
/**
 * \overload
 */
_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col);
/**
 * \overload
 */
_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col);

/**
 * Set the elements of a matrix to the identity matrix.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix4x4 *m);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix3x3 *m);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix2x2 *m);

/**
 * Set the elements of a matrix from an array of floats.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const float *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const float *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix2x2 *m, const float *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix4x4 *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix3x3 *v);

/**
 * Set the elements of a matrix from another matrix.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix2x2 *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const rs_matrix3x3 *v);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix2x2 *m, const rs_matrix2x2 *v);

/**
 * Load a rotation matrix.
 *
 * @param m
 * @param rot
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixLoadRotate(rs_matrix4x4 *m, float rot, float x, float y, float z);

/**
 * Load a scale matrix.
 *
 * @param m
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixLoadScale(rs_matrix4x4 *m, float x, float y, float z);

/**
 * Load a translation matrix.
 *
 * @param m
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixLoadTranslate(rs_matrix4x4 *m, float x, float y, float z);

/**
 * Multiply two matrix (lhs, rhs) and place the result in m.
 *
 * @param m
 * @param lhs
 * @param rhs
 */
extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs);
/**
 * \overload
 */
extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *lhs, const rs_matrix3x3 *rhs);
/**
 * \overload
 */
extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *lhs, const rs_matrix2x2 *rhs);

/**
 * Multiply the matrix m by rhs and place the result back into m.
 *
 * @param m (lhs)
 * @param rhs
 */
extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *rhs);
/**
 * \overload
 */
extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *rhs);
/**
 * \overload
 */
extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *rhs);

/**
 * Multiple matrix m with a rotation matrix
 *
 * @param m
 * @param rot
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixRotate(rs_matrix4x4 *m, float rot, float x, float y, float z);

/**
 * Multiple matrix m with a scale matrix
 *
 * @param m
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixScale(rs_matrix4x4 *m, float x, float y, float z);

/**
 * Multiple matrix m with a translation matrix
 *
 * @param m
 * @param x
 * @param y
 * @param z
 */
extern void __attribute__((overloadable))
rsMatrixTranslate(rs_matrix4x4 *m, float x, float y, float z);

/**
 * Load an Ortho projection matrix constructed from the 6 planes
 *
 * @param m
 * @param left
 * @param right
 * @param bottom
 * @param top
 * @param near
 * @param far
 */
extern void __attribute__((overloadable))
rsMatrixLoadOrtho(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far);

/**
 * Load an Frustum projection matrix constructed from the 6 planes
 *
 * @param m
 * @param left
 * @param right
 * @param bottom
 * @param top
 * @param near
 * @param far
 */
extern void __attribute__((overloadable))
rsMatrixLoadFrustum(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far);

/**
 * Load an perspective projection matrix constructed from the 6 planes
 *
 * @param m
 * @param fovy Field of view, in degrees along the Y axis.
 * @param aspect Ratio of x / y.
 * @param near
 * @param far
 */
extern void __attribute__((overloadable))
rsMatrixLoadPerspective(rs_matrix4x4* m, float fovy, float aspect, float near, float far);

#if !defined(RS_VERSION) || (RS_VERSION < 14)
/**
 * Multiply a vector by a matrix and return the result vector.
 * API version 10-13
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float4 in);

/**
 * \overload
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float3 in);

/**
 * \overload
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float2 in);

/**
 * \overload
 */
_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float3 in);

/**
 * \overload
 */
_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float2 in);

/**
 * \overload
 */
_RS_RUNTIME float2 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, float2 in);
#else
/**
 * Multiply a vector by a matrix and return the result vector.
 * API version 10-13
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix4x4 *m, float4 in);

/**
 * \overload
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix4x4 *m, float3 in);

/**
 * \overload
 */
_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix4x4 *m, float2 in);

/**
 * \overload
 */
_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix3x3 *m, float3 in);

/**
 * \overload
 */
_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix3x3 *m, float2 in);

/**
 * \overload
 */
_RS_RUNTIME float2 __attribute__((overloadable))
rsMatrixMultiply(const rs_matrix2x2 *m, float2 in);
#endif


/**
 * Returns true if the matrix was successfully inversed
 *
 * @param m
 */
extern bool __attribute__((overloadable)) rsMatrixInverse(rs_matrix4x4 *m);

/**
 * Returns true if the matrix was successfully inversed and transposed.
 *
 * @param m
 */
extern bool __attribute__((overloadable)) rsMatrixInverseTranspose(rs_matrix4x4 *m);

/**
 * Transpose the matrix m.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix4x4 *m);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix3x3 *m);
/**
 * \overload
 */
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix2x2 *m);

/////////////////////////////////////////////////////
// quaternion ops
/////////////////////////////////////////////////////

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

/////////////////////////////////////////////////////
// utility funcs
/////////////////////////////////////////////////////

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

#undef _RS_RUNTIME

#endif
