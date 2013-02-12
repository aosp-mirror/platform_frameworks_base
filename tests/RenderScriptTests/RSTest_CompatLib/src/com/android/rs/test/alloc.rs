#include "shared.rsh"

int *a;
int dimX;
int dimY;
int dimZ;

rs_allocation aRaw;
rs_allocation aFaces;
rs_allocation aLOD;
rs_allocation aFacesLOD;

void root(int *o, uint32_t x, uint32_t y) {
    *o = x + y * dimX;
}

static bool test_alloc_dims() {
    bool failed = false;
    int i, j;

    _RS_ASSERT(rsAllocationGetDimX(aRaw) == dimX);
    _RS_ASSERT(rsAllocationGetDimY(aRaw) == dimY);
    _RS_ASSERT(rsAllocationGetDimZ(aRaw) == dimZ);

    // Test 2D addressing
    for (j = 0; j < dimY; j++) {
        for (i = 0; i < dimX; i++) {
            rsDebug("Verifying ", i + j * dimX);
            const void *p = rsGetElementAt(aRaw, i, j);
            int val = *(const int *)p;
            _RS_ASSERT(val == (i + j * dimX));
        }
    }

    // Test 1D addressing
    for (i = 0; i < dimX; i++) {
        rsDebug("Verifying ", i);
        const void *p = rsGetElementAt(aRaw, i);
        int val = *(const int *)p;
        _RS_ASSERT(val == i);
    }

    // Test 3D addressing
    for (j = 0; j < dimY; j++) {
        for (i = 0; i < dimX; i++) {
            rsDebug("Verifying ", i + j * dimX);
            const void *p = rsGetElementAt(aRaw, i, j, 0);
            int val = *(const int *)p;
            _RS_ASSERT(val == (i + j * dimX));
        }
    }

    _RS_ASSERT(rsAllocationGetDimX(aFaces) == dimX);
    _RS_ASSERT(rsAllocationGetDimY(aFaces) == dimY);
    _RS_ASSERT(rsAllocationGetDimZ(aFaces) == dimZ);
    _RS_ASSERT(rsAllocationGetDimFaces(aFaces) != 0);
    _RS_ASSERT(rsAllocationGetDimLOD(aFaces) == 0);

    _RS_ASSERT(rsAllocationGetDimX(aLOD) == dimX);
    _RS_ASSERT(rsAllocationGetDimY(aLOD) == dimY);
    _RS_ASSERT(rsAllocationGetDimZ(aLOD) == dimZ);
    _RS_ASSERT(rsAllocationGetDimFaces(aLOD) == 0);
    _RS_ASSERT(rsAllocationGetDimLOD(aLOD) != 0);

    _RS_ASSERT(rsAllocationGetDimX(aFacesLOD) == dimX);
    _RS_ASSERT(rsAllocationGetDimY(aFacesLOD) == dimY);
    _RS_ASSERT(rsAllocationGetDimZ(aFacesLOD) == dimZ);
    _RS_ASSERT(rsAllocationGetDimFaces(aFacesLOD) != 0);
    _RS_ASSERT(rsAllocationGetDimLOD(aFacesLOD) != 0);

    if (failed) {
        rsDebug("test_alloc_dims FAILED", 0);
    }
    else {
        rsDebug("test_alloc_dims PASSED", 0);
    }

    return failed;
}

void alloc_test() {
    bool failed = false;
    failed |= test_alloc_dims();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

