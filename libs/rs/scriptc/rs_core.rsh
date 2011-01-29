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
_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v);
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
_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col);
_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col);

/**
 * Set the elements of a matrix to the identity matrix.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix4x4 *m);
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix3x3 *m);
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix2x2 *m);

/**
 * Set the elements of a matrix from an array of floats.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const float *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const float *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix2x2 *m, const float *v);

/**
 * Set the elements of a matrix from another matrix.
 *
 * @param m
 */
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix4x4 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix3x3 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix2x2 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const rs_matrix3x3 *v);
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

extern void __attribute__((overloadable))
rsMatrixLoadScale(rs_matrix4x4 *m, float x, float y, float z);

extern void __attribute__((overloadable))
rsMatrixLoadTranslate(rs_matrix4x4 *m, float x, float y, float z);

extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs);

extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *rhs);

extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *lhs, const rs_matrix3x3 *rhs);

extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *rhs);

extern void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *lhs, const rs_matrix2x2 *rhs);

extern void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *rhs);

extern void __attribute__((overloadable))
rsMatrixRotate(rs_matrix4x4 *m, float rot, float x, float y, float z);

extern void __attribute__((overloadable))
rsMatrixScale(rs_matrix4x4 *m, float x, float y, float z);

extern void __attribute__((overloadable))
rsMatrixTranslate(rs_matrix4x4 *m, float x, float y, float z);

extern void __attribute__((overloadable))
rsMatrixLoadOrtho(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far);

extern void __attribute__((overloadable))
rsMatrixLoadFrustum(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far);

extern void __attribute__((overloadable))
rsMatrixLoadPerspective(rs_matrix4x4* m, float fovy, float aspect, float near, float far);

_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float4 in);

_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float3 in);

_RS_RUNTIME float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float2 in);

_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float3 in);

_RS_RUNTIME float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float2 in);

_RS_RUNTIME float2 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, float2 in);

// Returns true if the matrix was successfully inversed
extern bool __attribute__((overloadable)) rsMatrixInverse(rs_matrix4x4 *m);
extern bool __attribute__((overloadable)) rsMatrixInverseTranspose(rs_matrix4x4 *m);
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix4x4 *m);
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix3x3 *m);
extern void __attribute__((overloadable)) rsMatrixTranspose(rs_matrix2x2 *m);

/////////////////////////////////////////////////////
// int ops
/////////////////////////////////////////////////////

_RS_RUNTIME uint __attribute__((overloadable, always_inline)) rsClamp(uint amount, uint low, uint high);
_RS_RUNTIME int __attribute__((overloadable, always_inline)) rsClamp(int amount, int low, int high);
_RS_RUNTIME ushort __attribute__((overloadable, always_inline)) rsClamp(ushort amount, ushort low, ushort high);
_RS_RUNTIME short __attribute__((overloadable, always_inline)) rsClamp(short amount, short low, short high);
_RS_RUNTIME uchar __attribute__((overloadable, always_inline)) rsClamp(uchar amount, uchar low, uchar high);
_RS_RUNTIME char __attribute__((overloadable, always_inline)) rsClamp(char amount, char low, char high);

#undef _RS_RUNTIME

#endif
