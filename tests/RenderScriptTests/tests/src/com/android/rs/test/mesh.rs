#include "shared.rsh"
#include "rs_graphics.rsh"

rs_mesh mesh;
rs_allocation vertexAlloc0;
rs_allocation vertexAlloc1;

rs_allocation indexAlloc0;
rs_allocation indexAlloc2;

static bool test_mesh_getters() {
    bool failed = false;

    _RS_ASSERT(rsgMeshGetVertexAllocationCount(mesh) == 2);
    _RS_ASSERT(rsgMeshGetPrimitiveCount(mesh) == 3);

    rs_allocation meshV0 = rsgMeshGetVertexAllocation(mesh, 0);
    rs_allocation meshV1 = rsgMeshGetVertexAllocation(mesh, 1);
    rs_allocation meshV2 = rsgMeshGetVertexAllocation(mesh, 2);
    _RS_ASSERT(meshV0.p == vertexAlloc0.p);
    _RS_ASSERT(meshV1.p == vertexAlloc1.p);
    _RS_ASSERT(!rsIsObject(meshV2));

    rs_allocation meshI0 = rsgMeshGetIndexAllocation(mesh, 0);
    rs_allocation meshI1 = rsgMeshGetIndexAllocation(mesh, 1);
    rs_allocation meshI2 = rsgMeshGetIndexAllocation(mesh, 2);
    rs_allocation meshI3 = rsgMeshGetIndexAllocation(mesh, 3);
    _RS_ASSERT(meshI0.p == indexAlloc0.p);
    _RS_ASSERT(!rsIsObject(meshI1));
    _RS_ASSERT(meshI2.p == indexAlloc2.p);
    _RS_ASSERT(!rsIsObject(meshI3));

    rs_primitive p0 = rsgMeshGetPrimitive(mesh, 0);
    rs_primitive p1 = rsgMeshGetPrimitive(mesh, 1);
    rs_primitive p2 = rsgMeshGetPrimitive(mesh, 2);
    rs_primitive p3 = rsgMeshGetPrimitive(mesh, 3);

    _RS_ASSERT(p0 == RS_PRIMITIVE_POINT);
    _RS_ASSERT(p1 == RS_PRIMITIVE_LINE);
    _RS_ASSERT(p2 == RS_PRIMITIVE_TRIANGLE);
    _RS_ASSERT(p3 == RS_PRIMITIVE_INVALID);

    if (failed) {
        rsDebug("test_mesh_getters FAILED", 0);
    }
    else {
        rsDebug("test_mesh_getters PASSED", 0);
    }

    return failed;
}

void mesh_test() {
    bool failed = false;
    failed |= test_mesh_getters();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

