
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

typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_element;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_type;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_allocation;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_sampler;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_script;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_mesh;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_program_fragment;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_program_vertex;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_program_raster;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_program_store;
typedef struct { int* p; } __attribute__((packed, aligned(4))) rs_font;

typedef float float2 __attribute__((ext_vector_type(2)));
typedef float float3 __attribute__((ext_vector_type(3)));
typedef float float4 __attribute__((ext_vector_type(4)));

typedef uchar uchar2 __attribute__((ext_vector_type(2)));
typedef uchar uchar3 __attribute__((ext_vector_type(3)));
typedef uchar uchar4 __attribute__((ext_vector_type(4)));

typedef ushort ushort2 __attribute__((ext_vector_type(2)));
typedef ushort ushort3 __attribute__((ext_vector_type(3)));
typedef ushort ushort4 __attribute__((ext_vector_type(4)));

typedef uint uint2 __attribute__((ext_vector_type(2)));
typedef uint uint3 __attribute__((ext_vector_type(3)));
typedef uint uint4 __attribute__((ext_vector_type(4)));

typedef char char2 __attribute__((ext_vector_type(2)));
typedef char char3 __attribute__((ext_vector_type(3)));
typedef char char4 __attribute__((ext_vector_type(4)));

typedef short short2 __attribute__((ext_vector_type(2)));
typedef short short3 __attribute__((ext_vector_type(3)));
typedef short short4 __attribute__((ext_vector_type(4)));

typedef int int2 __attribute__((ext_vector_type(2)));
typedef int int3 __attribute__((ext_vector_type(3)));
typedef int int4 __attribute__((ext_vector_type(4)));


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

