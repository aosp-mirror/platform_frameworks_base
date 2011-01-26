#ifndef __RS_CORE_RSH__
#define __RS_CORE_RSH__

#define _RS_RUNTIME extern

// Debugging, print to the LOG a description string and a value.
extern void __attribute__((overloadable))
    rsDebug(const char *, float);
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float);
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float, float);
extern void __attribute__((overloadable))
    rsDebug(const char *, float, float, float, float);
extern void __attribute__((overloadable))
    rsDebug(const char *, double);
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix4x4 *);
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix3x3 *);
extern void __attribute__((overloadable))
    rsDebug(const char *, const rs_matrix2x2 *);
extern void __attribute__((overloadable))
    rsDebug(const char *, int);
extern void __attribute__((overloadable))
    rsDebug(const char *, uint);
extern void __attribute__((overloadable))
    rsDebug(const char *, long);
extern void __attribute__((overloadable))
    rsDebug(const char *, unsigned long);
extern void __attribute__((overloadable))
    rsDebug(const char *, long long);
extern void __attribute__((overloadable))
    rsDebug(const char *, unsigned long long);
extern void __attribute__((overloadable))
    rsDebug(const char *, const void *);
#define RS_DEBUG(a) rsDebug(#a, a)
#define RS_DEBUG_MARKER rsDebug(__FILE__, __LINE__)

_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float2 v);
_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float3 v);
_RS_RUNTIME void __attribute__((overloadable)) rsDebug(const char *s, float4 v);

_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b);

_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b, float a);

_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float3 color);

_RS_RUNTIME uchar4 __attribute__((overloadable)) rsPackColorTo8888(float4 color);

_RS_RUNTIME float4 rsUnpackColor8888(uchar4 c);

//extern uchar4 __attribute__((overloadable)) rsPackColorTo565(float r, float g, float b);
//extern uchar4 __attribute__((overloadable)) rsPackColorTo565(float3);
//extern float4 rsUnpackColor565(uchar4);


/////////////////////////////////////////////////////
// Matrix ops
/////////////////////////////////////////////////////

_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix4x4 *m, uint32_t row, uint32_t col, float v);

_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix4x4 *m, uint32_t row, uint32_t col);

_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v);

_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col);

_RS_RUNTIME void __attribute__((overloadable))
rsMatrixSet(rs_matrix2x2 *m, uint32_t row, uint32_t col, float v);

_RS_RUNTIME float __attribute__((overloadable))
rsMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col);

extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix4x4 *m);
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix3x3 *m);
extern void __attribute__((overloadable)) rsMatrixLoadIdentity(rs_matrix2x2 *m);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const float *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const float *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix2x2 *m, const float *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix4x4 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix3x3 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix2x2 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix3x3 *m, const rs_matrix3x3 *v);
extern void __attribute__((overloadable)) rsMatrixLoad(rs_matrix2x2 *m, const rs_matrix2x2 *v);

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
