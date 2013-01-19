#include "shared.rsh"
#include "rs_graphics.rsh"

rs_element simpleElem;
rs_element complexElem;
typedef struct ComplexStruct {
    float subElem0;
    float subElem1;
    int subElem2;
    float arrayElem0[2];
    int arrayElem1[5];
    char subElem3;
    float subElem4;
    float2 subElem5;
    float3 subElem6;
    float4 subElem_7;
} ComplexStruct_t;

ComplexStruct_t *complexStruct;

static const char *subElemNames[] = {
    "subElem0",
    "subElem1",
    "subElem2",
    "arrayElem0",
    "arrayElem1",
    "subElem3",
    "subElem4",
    "subElem5",
    "subElem6",
    "subElem_7",
};

static uint32_t subElemNamesSizes[] = {
    8,
    8,
    8,
    10,
    10,
    8,
    8,
    8,
    8,
    9,
};

static uint32_t subElemArraySizes[] = {
    1,
    1,
    1,
    2,
    5,
    1,
    1,
    1,
    1,
    1,
};

static void resetStruct() {
    uint8_t *bytePtr = (uint8_t*)complexStruct;
    uint32_t sizeOfStruct = sizeof(*complexStruct);
    for(uint32_t i = 0; i < sizeOfStruct; i ++) {
        bytePtr[i] = 0;
    }
}

static bool equals(const char *name0, const char * name1, uint32_t len) {
    for (uint32_t i = 0; i < len; i ++) {
        if (name0[i] != name1[i]) {
            return false;
        }
    }
    return true;
}

static bool test_element_getters() {
    bool failed = false;

    uint32_t subElemOffsets[10];
    uint32_t index = 0;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem0   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem1   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem2   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->arrayElem0 - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->arrayElem1 - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem3   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem4   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem5   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem6   - (uint32_t)complexStruct;
    subElemOffsets[index++] = (uint32_t)&complexStruct->subElem_7  - (uint32_t)complexStruct;

    uint32_t subElemCount = rsElementGetSubElementCount(simpleElem);
    _RS_ASSERT(subElemCount == 0);
    _RS_ASSERT(rsElementGetDataType(simpleElem) == RS_TYPE_FLOAT_32);
    _RS_ASSERT(rsElementGetVectorSize(simpleElem) == 3);

    subElemCount = rsElementGetSubElementCount(complexElem);
    _RS_ASSERT(subElemCount == 10);
    _RS_ASSERT(rsElementGetDataType(complexElem) == RS_TYPE_NONE);
    _RS_ASSERT(rsElementGetVectorSize(complexElem) == 1);
    _RS_ASSERT(rsElementGetBytesSize(complexElem) == sizeof(*complexStruct));

    char buffer[64];
    for (uint32_t i = 0; i < subElemCount; i ++) {
        rs_element subElem = rsElementGetSubElement(complexElem, i);
        _RS_ASSERT(rsIsObject(subElem));

        _RS_ASSERT(rsElementGetSubElementNameLength(complexElem, i) == subElemNamesSizes[i] + 1);

        uint32_t written = rsElementGetSubElementName(complexElem, i, buffer, 64);
        _RS_ASSERT(written == subElemNamesSizes[i]);
        _RS_ASSERT(equals(buffer, subElemNames[i], written));

        _RS_ASSERT(rsElementGetSubElementArraySize(complexElem, i) == subElemArraySizes[i]);
        _RS_ASSERT(rsElementGetSubElementOffsetBytes(complexElem, i) == subElemOffsets[i]);
    }

    // Tests error checking
    rs_element subElem = rsElementGetSubElement(complexElem, subElemCount);
    _RS_ASSERT(!rsIsObject(subElem));

    _RS_ASSERT(rsElementGetSubElementNameLength(complexElem, subElemCount) == 0);

    _RS_ASSERT(rsElementGetSubElementName(complexElem, subElemCount, buffer, 64) == 0);
    _RS_ASSERT(rsElementGetSubElementName(complexElem, 0, NULL, 64) == 0);
    _RS_ASSERT(rsElementGetSubElementName(complexElem, 0, buffer, 0) == 0);
    uint32_t written = rsElementGetSubElementName(complexElem, 0, buffer, 5);
    _RS_ASSERT(written == 4);
    _RS_ASSERT(buffer[4] == '\0');

    _RS_ASSERT(rsElementGetSubElementArraySize(complexElem, subElemCount) == 0);
    _RS_ASSERT(rsElementGetSubElementOffsetBytes(complexElem, subElemCount) == 0);

    if (failed) {
        rsDebug("test_element_getters FAILED", 0);
    }
    else {
        rsDebug("test_element_getters PASSED", 0);
    }

    return failed;
}

void element_test() {
    bool failed = false;
    failed |= test_element_getters();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

