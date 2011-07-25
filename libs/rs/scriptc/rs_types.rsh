#ifndef __RS_TYPES_RSH__
#define __RS_TYPES_RSH__

#define M_PI        3.14159265358979323846264338327950288f   /* pi */

#include "stdbool.h"
typedef char int8_t;
typedef short int16_t;
typedef int int32_t;
typedef long long int64_t;

typedef unsigned char uint8_t;
typedef unsigned short uint16_t;
typedef unsigned int uint32_t;
typedef unsigned long long uint64_t;

typedef uint8_t uchar;
typedef uint16_t ushort;
typedef uint32_t uint;
typedef uint64_t ulong;

typedef uint32_t size_t;
typedef int32_t ssize_t;

typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_element;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_type;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_allocation;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_sampler;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_script;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_mesh;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_fragment;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_vertex;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_raster;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_store;
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_font;


typedef float float2 __attribute__((ext_vector_type(2)));
typedef float float3 __attribute__((ext_vector_type(3)));
typedef float float4 __attribute__((ext_vector_type(4)));

typedef double double2 __attribute__((ext_vector_type(2)));
typedef double double3 __attribute__((ext_vector_type(3)));
typedef double double4 __attribute__((ext_vector_type(4)));

typedef uchar uchar2 __attribute__((ext_vector_type(2)));
typedef uchar uchar3 __attribute__((ext_vector_type(3)));
typedef uchar uchar4 __attribute__((ext_vector_type(4)));

typedef ushort ushort2 __attribute__((ext_vector_type(2)));
typedef ushort ushort3 __attribute__((ext_vector_type(3)));
typedef ushort ushort4 __attribute__((ext_vector_type(4)));

typedef uint uint2 __attribute__((ext_vector_type(2)));
typedef uint uint3 __attribute__((ext_vector_type(3)));
typedef uint uint4 __attribute__((ext_vector_type(4)));

typedef ulong ulong2 __attribute__((ext_vector_type(2)));
typedef ulong ulong3 __attribute__((ext_vector_type(3)));
typedef ulong ulong4 __attribute__((ext_vector_type(4)));

typedef char char2 __attribute__((ext_vector_type(2)));
typedef char char3 __attribute__((ext_vector_type(3)));
typedef char char4 __attribute__((ext_vector_type(4)));

typedef short short2 __attribute__((ext_vector_type(2)));
typedef short short3 __attribute__((ext_vector_type(3)));
typedef short short4 __attribute__((ext_vector_type(4)));

typedef int int2 __attribute__((ext_vector_type(2)));
typedef int int3 __attribute__((ext_vector_type(3)));
typedef int int4 __attribute__((ext_vector_type(4)));

typedef long long2 __attribute__((ext_vector_type(2)));
typedef long long3 __attribute__((ext_vector_type(3)));
typedef long long4 __attribute__((ext_vector_type(4)));

typedef struct {
    float m[16];
} rs_matrix4x4;

typedef struct {
    float m[9];
} rs_matrix3x3;

typedef struct {
    float m[4];
} rs_matrix2x2;

typedef float4 rs_quaternion;

#define RS_PACKED __attribute__((packed, aligned(4)))

#define NULL ((const void *)0)

typedef enum {
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X = 0,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_X = 1,
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_Y = 2,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_Y = 3,
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_Z = 4,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_Z = 5
} rs_allocation_cubemap_face;

typedef enum {
    RS_ALLOCATION_USAGE_SCRIPT = 0x0001,
    RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE = 0x0002,
    RS_ALLOCATION_USAGE_GRAPHICS_VERTEX = 0x0004,
    RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS = 0x0008,
    RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET = 0x0010
} rs_allocation_usage_type;

#endif
