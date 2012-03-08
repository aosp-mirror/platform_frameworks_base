#include "shared.rsh"

// Testing vector types
float2 f2 = { 1.0f, 2.0f };
float3 f3 = { 1.0f, 2.0f, 3.0f };
float4 f4 = { 1.0f, 2.0f, 3.0f, 4.0f };

double2 d2 = { 1.0, 2.0 };
double3 d3 = { 1.0, 2.0, 3.0 };
double4 d4 = { 1.0, 2.0, 3.0, 4.0 };

char2 i8_2 = { 1, 2 };
char3 i8_3 = { 1, 2, 3 };
char4 i8_4 = { 1, 2, 3, 4 };

uchar2 u8_2 = { 1, 2 };
uchar3 u8_3 = { 1, 2, 3 };
uchar4 u8_4 = { 1, 2, 3, 4 };

short2 i16_2 = { 1, 2 };
short3 i16_3 = { 1, 2, 3 };
short4 i16_4 = { 1, 2, 3, 4 };

ushort2 u16_2 = { 1, 2 };
ushort3 u16_3 = { 1, 2, 3 };
ushort4 u16_4 = { 1, 2, 3, 4 };

int2 i32_2 = { 1, 2 };
int3 i32_3 = { 1, 2, 3 };
int4 i32_4 = { 1, 2, 3, 4 };

uint2 u32_2 = { 1, 2 };
uint3 u32_3 = { 1, 2, 3 };
uint4 u32_4 = { 1, 2, 3, 4 };

long2 i64_2 = { 1, 2 };
long3 i64_3 = { 1, 2, 3 };
long4 i64_4 = { 1, 2, 3, 4 };

ulong2 u64_2 = { 1, 2 };
ulong3 u64_3 = { 1, 2, 3 };
ulong4 u64_4 = { 1, 2, 3, 4 };

static bool test_vector_types() {
    bool failed = false;

    rsDebug("Testing F32", 0);
    _RS_ASSERT(f2.x == 2.99f);
    _RS_ASSERT(f2.y == 3.99f);

    _RS_ASSERT(f3.x == 2.99f);
    _RS_ASSERT(f3.y == 3.99f);
    _RS_ASSERT(f3.z == 4.99f);

    _RS_ASSERT(f4.x == 2.99f);
    _RS_ASSERT(f4.y == 3.99f);
    _RS_ASSERT(f4.z == 4.99f);
    _RS_ASSERT(f4.w == 5.99f);

    rsDebug("Testing F64", 0);
    _RS_ASSERT(d2.x == 2.99);
    _RS_ASSERT(d2.y == 3.99);

    _RS_ASSERT(d3.x == 2.99);
    _RS_ASSERT(d3.y == 3.99);
    _RS_ASSERT(d3.z == 4.99);

    _RS_ASSERT(d4.x == 2.99);
    _RS_ASSERT(d4.y == 3.99);
    _RS_ASSERT(d4.z == 4.99);
    _RS_ASSERT(d4.w == 5.99);

    rsDebug("Testing I8", 0);
    _RS_ASSERT(i8_2.x == 2);
    _RS_ASSERT(i8_2.y == 3);

    _RS_ASSERT(i8_3.x == 2);
    _RS_ASSERT(i8_3.y == 3);
    _RS_ASSERT(i8_3.z == 4);

    _RS_ASSERT(i8_4.x == 2);
    _RS_ASSERT(i8_4.y == 3);
    _RS_ASSERT(i8_4.z == 4);
    _RS_ASSERT(i8_4.w == 5);

    rsDebug("Testing U8", 0);
    _RS_ASSERT(u8_2.x == 2);
    _RS_ASSERT(u8_2.y == 3);

    _RS_ASSERT(u8_3.x == 2);
    _RS_ASSERT(u8_3.y == 3);
    _RS_ASSERT(u8_3.z == 4);

    _RS_ASSERT(u8_4.x == 2);
    _RS_ASSERT(u8_4.y == 3);
    _RS_ASSERT(u8_4.z == 4);
    _RS_ASSERT(u8_4.w == 5);

    rsDebug("Testing I16", 0);
    _RS_ASSERT(i16_2.x == 2);
    _RS_ASSERT(i16_2.y == 3);

    _RS_ASSERT(i16_3.x == 2);
    _RS_ASSERT(i16_3.y == 3);
    _RS_ASSERT(i16_3.z == 4);

    _RS_ASSERT(i16_4.x == 2);
    _RS_ASSERT(i16_4.y == 3);
    _RS_ASSERT(i16_4.z == 4);
    _RS_ASSERT(i16_4.w == 5);

    rsDebug("Testing U16", 0);
    _RS_ASSERT(u16_2.x == 2);
    _RS_ASSERT(u16_2.y == 3);

    _RS_ASSERT(u16_3.x == 2);
    _RS_ASSERT(u16_3.y == 3);
    _RS_ASSERT(u16_3.z == 4);

    _RS_ASSERT(u16_4.x == 2);
    _RS_ASSERT(u16_4.y == 3);
    _RS_ASSERT(u16_4.z == 4);
    _RS_ASSERT(u16_4.w == 5);

    rsDebug("Testing I32", 0);
    _RS_ASSERT(i32_2.x == 2);
    _RS_ASSERT(i32_2.y == 3);

    _RS_ASSERT(i32_3.x == 2);
    _RS_ASSERT(i32_3.y == 3);
    _RS_ASSERT(i32_3.z == 4);

    _RS_ASSERT(i32_4.x == 2);
    _RS_ASSERT(i32_4.y == 3);
    _RS_ASSERT(i32_4.z == 4);
    _RS_ASSERT(i32_4.w == 5);

    rsDebug("Testing U32", 0);
    _RS_ASSERT(u32_2.x == 2);
    _RS_ASSERT(u32_2.y == 3);

    _RS_ASSERT(u32_3.x == 2);
    _RS_ASSERT(u32_3.y == 3);
    _RS_ASSERT(u32_3.z == 4);

    _RS_ASSERT(u32_4.x == 2);
    _RS_ASSERT(u32_4.y == 3);
    _RS_ASSERT(u32_4.z == 4);
    _RS_ASSERT(u32_4.w == 5);

    rsDebug("Testing I64", 0);
    _RS_ASSERT(i64_2.x == 2);
    _RS_ASSERT(i64_2.y == 3);

    _RS_ASSERT(i64_3.x == 2);
    _RS_ASSERT(i64_3.y == 3);
    _RS_ASSERT(i64_3.z == 4);

    _RS_ASSERT(i64_4.x == 2);
    _RS_ASSERT(i64_4.y == 3);
    _RS_ASSERT(i64_4.z == 4);
    _RS_ASSERT(i64_4.w == 5);

    rsDebug("Testing U64", 0);
    _RS_ASSERT(u64_2.x == 2);
    _RS_ASSERT(u64_2.y == 3);

    _RS_ASSERT(u64_3.x == 2);
    _RS_ASSERT(u64_3.y == 3);
    _RS_ASSERT(u64_3.z == 4);

    _RS_ASSERT(u64_4.x == 2);
    _RS_ASSERT(u64_4.y == 3);
    _RS_ASSERT(u64_4.z == 4);
    _RS_ASSERT(u64_4.w == 5);

    if (failed) {
        rsDebug("test_vector FAILED", 0);
    }
    else {
        rsDebug("test_vector PASSED", 0);
    }

    return failed;
}

void vector_test() {
    bool failed = false;
    failed |= test_vector_types();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

