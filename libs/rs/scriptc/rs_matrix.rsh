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
 *  \brief Matrix routines
 *
 *
 */

#ifndef __RS_MATRIX_RSH__
#define __RS_MATRIX_RSH__

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
 * API version 14+
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


#endif
