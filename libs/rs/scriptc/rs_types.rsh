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

/** @file rs_types.rsh
 *
 *  Define the standard Renderscript types
 *
 *  Integers
 *  8 bit: char, int8_t
 *  16 bit: short, int16_t
 *  32 bit: int, in32_t
 *  64 bit: long, long long, int64_t
 *
 *  Unsigned Integers
 *  8 bit: uchar, uint8_t
 *  16 bit: ushort, uint16_t
 *  32 bit: uint, uint32_t
 *  64 bit: ulong, uint64_t
 *
 *  Floating point
 *  32 bit: float
 *  64 bit: double
 *
 *  Vectors of length 2, 3, and 4 are supported for all the types above.
 *
 */

#ifndef __RS_TYPES_RSH__
#define __RS_TYPES_RSH__

#define M_PI        3.14159265358979323846264338327950288f   /* pi */

#include "stdbool.h"
/**
 * 8 bit integer type
 */
typedef char int8_t;
/**
 * 16 bit integer type
 */
typedef short int16_t;
/**
 * 32 bit integer type
 */
typedef int int32_t;
/**
 * 64 bit integer type
 */
typedef long long int64_t;
/**
 * 8 bit unsigned integer type
 */
typedef unsigned char uint8_t;
/**
 * 16 bit unsigned integer type
 */
typedef unsigned short uint16_t;
/**
 * 32 bit unsigned integer type
 */
typedef unsigned int uint32_t;
/**
 * 64 bit unsigned integer type
 */
typedef unsigned long long uint64_t;
/**
 * 8 bit unsigned integer type
 */
typedef uint8_t uchar;
/**
 * 16 bit unsigned integer type
 */
typedef uint16_t ushort;
/**
 * 32 bit unsigned integer type
 */
typedef uint32_t uint;
/**
 * Typedef for unsigned long (use for 64-bit unsigned integers)
 */
typedef uint64_t ulong;
/**
 * Typedef for unsigned int
 */
typedef uint32_t size_t;
/**
 * Typedef for int (use for 32-bit integers)
 */
typedef int32_t ssize_t;

/**
 * \brief Opaque handle to a Renderscript element.
 *
 * See: android.renderscript.Element
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_element;
/**
 * \brief Opaque handle to a Renderscript type.
 *
 * See: android.renderscript.Type
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_type;
/**
 * \brief Opaque handle to a Renderscript allocation.
 *
 * See: android.renderscript.Allocation
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_allocation;
/**
 * \brief Opaque handle to a Renderscript sampler object.
 *
 * See: android.renderscript.Sampler
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_sampler;
/**
 * \brief Opaque handle to a Renderscript script object.
 *
 * See: android.renderscript.ScriptC
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_script;
/**
 * \brief Opaque handle to a Renderscript mesh object.
 *
 * See: android.renderscript.Mesh
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_mesh;
/**
 * \brief Opaque handle to a Renderscript Path object.
 *
 * See: android.renderscript.Path
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_path;
/**
 * \brief Opaque handle to a Renderscript ProgramFragment object.
 *
 * See: android.renderscript.ProgramFragment
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_fragment;
/**
 * \brief Opaque handle to a Renderscript ProgramVertex object.
 *
 * See: android.renderscript.ProgramVertex
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_vertex;
/**
 * \brief Opaque handle to a Renderscript ProgramRaster object.
 *
 * See: android.renderscript.ProgramRaster
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_raster;
/**
 * \brief Opaque handle to a Renderscript ProgramStore object.
 *
 * See: android.renderscript.ProgramStore
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_program_store;
/**
 * \brief Opaque handle to a Renderscript font object.
 *
 * See: android.renderscript.Font
 */
typedef struct { const int* const p; } __attribute__((packed, aligned(4))) rs_font;

/**
 * Vector version of the basic float type.
 * Provides two float fields packed into a single 64 bit field with 64 bit
 * alignment.
 */
typedef float float2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic float type. Provides three float fields packed
 * into a single 128 bit field with 128 bit alignment.
 */
typedef float float3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic float type.
 * Provides four float fields packed into a single 128 bit field with 128 bit
 * alignment.
 */
typedef float float4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic double type. Provides two double fields packed
 * into a single 128 bit field with 128 bit alignment.
 */
typedef double double2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic double type. Provides three double fields packed
 * into a single 256 bit field with 256 bit alignment.
 */
typedef double double3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic double type. Provides four double fields packed
 * into a single 256 bit field with 256 bit alignment.
 */
typedef double double4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic uchar type. Provides two uchar fields packed
 * into a single 16 bit field with 16 bit alignment.
 */
typedef uchar uchar2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic uchar type. Provides three uchar fields packed
 * into a single 32 bit field with 32 bit alignment.
 */
typedef uchar uchar3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic uchar type. Provides four uchar fields packed
 * into a single 32 bit field with 32 bit alignment.
 */
typedef uchar uchar4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic ushort type. Provides two ushort fields packed
 * into a single 32 bit field with 32 bit alignment.
 */
typedef ushort ushort2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic ushort type. Provides three ushort fields packed
 * into a single 64 bit field with 64 bit alignment.
 */
typedef ushort ushort3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic ushort type. Provides four ushort fields packed
 * into a single 64 bit field with 64 bit alignment.
 */
typedef ushort ushort4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic uint type. Provides two uint fields packed into a
 * single 64 bit field with 64 bit alignment.
 */
typedef uint uint2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic uint type. Provides three uint fields packed into
 * a single 128 bit field with 128 bit alignment.
 */
typedef uint uint3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic uint type. Provides four uint fields packed into
 * a single 128 bit field with 128 bit alignment.
 */
typedef uint uint4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic ulong type. Provides two ulong fields packed into
 * a single 128 bit field with 128 bit alignment.
 */
typedef ulong ulong2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic ulong type. Provides three ulong fields packed
 * into a single 256 bit field with 256 bit alignment.
 */
typedef ulong ulong3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic ulong type. Provides four ulong fields packed
 * into a single 256 bit field with 256 bit alignment.
 */
typedef ulong ulong4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic char type. Provides two char fields packed into a
 * single 16 bit field with 16 bit alignment.
 */
typedef char char2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic char type. Provides three char fields packed into
 * a single 32 bit field with 32 bit alignment.
 */
typedef char char3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic char type. Provides four char fields packed into
 * a single 32 bit field with 32 bit alignment.
 */
typedef char char4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic short type. Provides two short fields packed into
 * a single 32 bit field with 32 bit alignment.
 */
typedef short short2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic short type. Provides three short fields packed
 * into a single 64 bit field with 64 bit alignment.
 */
typedef short short3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic short type. Provides four short fields packed
 * into a single 64 bit field with 64 bit alignment.
 */
typedef short short4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic int type. Provides two int fields packed into a
 * single 64 bit field with 64 bit alignment.
 */
typedef int int2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic int type. Provides three int fields packed into a
 * single 128 bit field with 128 bit alignment.
 */
typedef int int3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic int type. Provides two four fields packed into a
 * single 128 bit field with 128 bit alignment.
 */
typedef int int4 __attribute__((ext_vector_type(4)));

/**
 * Vector version of the basic long type. Provides two long fields packed into a
 * single 128 bit field with 128 bit alignment.
 */
typedef long long2 __attribute__((ext_vector_type(2)));
/**
 * Vector version of the basic long type. Provides three long fields packed into
 * a single 256 bit field with 256 bit alignment.
 */
typedef long long3 __attribute__((ext_vector_type(3)));
/**
 * Vector version of the basic long type. Provides four long fields packed into
 * a single 256 bit field with 256 bit alignment.
 */
typedef long long4 __attribute__((ext_vector_type(4)));

/**
 * \brief 4x4 float matrix
 *
 * Native holder for RS matrix.  Elements are stored in the array at the
 * location [row*4 + col]
 */
typedef struct {
    float m[16];
} rs_matrix4x4;
/**
 * \brief 3x3 float matrix
 *
 * Native holder for RS matrix.  Elements are stored in the array at the
 * location [row*3 + col]
 */
typedef struct {
    float m[9];
} rs_matrix3x3;
/**
 * \brief 2x2 float matrix
 *
 * Native holder for RS matrix.  Elements are stored in the array at the
 * location [row*2 + col]
 */
typedef struct {
    float m[4];
} rs_matrix2x2;

/**
 * quaternion type for use with the quaternion functions
 */
typedef float4 rs_quaternion;

#define RS_PACKED __attribute__((packed, aligned(4)))
#define NULL ((void *)0)

#if (defined(RS_VERSION) && (RS_VERSION >= 14))

/**
 * \brief Enum for selecting cube map faces
 */
typedef enum {
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_X = 0,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_X = 1,
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_Y = 2,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_Y = 3,
    RS_ALLOCATION_CUBEMAP_FACE_POSITIVE_Z = 4,
    RS_ALLOCATION_CUBEMAP_FACE_NEGATIVE_Z = 5
} rs_allocation_cubemap_face;

/**
 * \brief Bitfield to specify the usage types for an allocation.
 *
 * These values are ORed together to specify which usages or memory spaces are
 * relevant to an allocation or an operation on an allocation.
 */
typedef enum {
    RS_ALLOCATION_USAGE_SCRIPT = 0x0001,
    RS_ALLOCATION_USAGE_GRAPHICS_TEXTURE = 0x0002,
    RS_ALLOCATION_USAGE_GRAPHICS_VERTEX = 0x0004,
    RS_ALLOCATION_USAGE_GRAPHICS_CONSTANTS = 0x0008,
    RS_ALLOCATION_USAGE_GRAPHICS_RENDER_TARGET = 0x0010
} rs_allocation_usage_type;

#endif //defined(RS_VERSION) && (RS_VERSION >= 14)

/**
 * Describes the way mesh vertex data is interpreted when rendering
 *
 **/
typedef enum {
    RS_PRIMITIVE_POINT,
    RS_PRIMITIVE_LINE,
    RS_PRIMITIVE_LINE_STRIP,
    RS_PRIMITIVE_TRIANGLE,
    RS_PRIMITIVE_TRIANGLE_STRIP,
    RS_PRIMITIVE_TRIANGLE_FAN,

    RS_PRIMITIVE_INVALID = 100,
} rs_primitive;

/**
 * \brief Enumeration for possible element data types
 *
 * DataType represents the basic type information for a basic element.  The
 * naming convention follows.  For numeric types it is FLOAT,
 * SIGNED, or UNSIGNED followed by the _BITS where BITS is the
 * size of the data.  BOOLEAN is a true / false (1,0)
 * represented in an 8 bit container.  The UNSIGNED variants
 * with multiple bit definitions are for packed graphical data
 * formats and represent vectors with per vector member sizes
 * which are treated as a single unit for packing and alignment
 * purposes.
 *
 * MATRIX the three matrix types contain FLOAT_32 elements and are treated
 * as 32 bits for alignment purposes.
 *
 * RS_* objects.  32 bit opaque handles.
 */
typedef enum {
    RS_TYPE_NONE,
    //RS_TYPE_FLOAT_16,
    RS_TYPE_FLOAT_32 = 2,
    RS_TYPE_FLOAT_64,
    RS_TYPE_SIGNED_8,
    RS_TYPE_SIGNED_16,
    RS_TYPE_SIGNED_32,
    RS_TYPE_SIGNED_64,
    RS_TYPE_UNSIGNED_8,
    RS_TYPE_UNSIGNED_16,
    RS_TYPE_UNSIGNED_32,
    RS_TYPE_UNSIGNED_64,

    RS_TYPE_BOOLEAN,

    RS_TYPE_UNSIGNED_5_6_5,
    RS_TYPE_UNSIGNED_5_5_5_1,
    RS_TYPE_UNSIGNED_4_4_4_4,

    RS_TYPE_MATRIX_4X4,
    RS_TYPE_MATRIX_3X3,
    RS_TYPE_MATRIX_2X2,

    RS_TYPE_ELEMENT = 1000,
    RS_TYPE_TYPE,
    RS_TYPE_ALLOCATION,
    RS_TYPE_SAMPLER,
    RS_TYPE_SCRIPT,
    RS_TYPE_MESH,
    RS_TYPE_PROGRAM_FRAGMENT,
    RS_TYPE_PROGRAM_VERTEX,
    RS_TYPE_PROGRAM_RASTER,
    RS_TYPE_PROGRAM_STORE,

    RS_TYPE_INVALID = 10000,
} rs_data_type;

/**
 * \brief Enumeration for possible element data kind
 *
 * The special interpretation of the data if required.  This is primarly
 * useful for graphical data.  USER indicates no special interpretation is
 * expected.  PIXEL is used in conjunction with the standard data types for
 * representing texture formats.
 */
typedef enum {
    RS_KIND_USER,

    RS_KIND_PIXEL_L = 7,
    RS_KIND_PIXEL_A,
    RS_KIND_PIXEL_LA,
    RS_KIND_PIXEL_RGB,
    RS_KIND_PIXEL_RGBA,
    RS_KIND_PIXEL_DEPTH,

    RS_KIND_INVALID = 100,
} rs_data_kind;

#endif
