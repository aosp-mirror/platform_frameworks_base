#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.balls)
#include "rs_graphics.rsh"

#include "balls.rsh"

#pragma stateVertex(parent)
#pragma stateStore(parent)

rs_program_fragment gPFPoints;
rs_program_fragment gPFLines;
rs_mesh partMesh;

typedef struct __attribute__((packed, aligned(4))) Point {
    float2 position;
    float size;
} Point_t;
Point_t *point;

typedef struct VpConsts {
    rs_matrix4x4 MVP;
} VpConsts_t;
VpConsts_t *vpConstants;

rs_script physics_script;

Ball_t *balls1;
Ball_t *balls2;

static int frame = 0;

void initParts(int w, int h)
{
    uint32_t dimX = rsAllocationGetDimX(rsGetAllocation(balls1));

    for (uint32_t ct=0; ct < dimX; ct++) {
        balls1[ct].position.x = rsRand(0.f, (float)w);
        balls1[ct].position.y = rsRand(0.f, (float)h);
        balls1[ct].delta.x = 0.f;
        balls1[ct].delta.y = 0.f;
        balls1[ct].size = 1.f;

        float r = rsRand(100.f);
        if (r > 90.f) {
            balls1[ct].size += pow(10.f, rsRand(0.f, 2.f)) * 0.07f;
        }
    }
}



int root() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);

    BallControl_t bc;
    Ball_t *bout;

    if (frame & 1) {
        bc.ain = rsGetAllocation(balls2);
        bc.aout = rsGetAllocation(balls1);
        bout = balls1;
    } else {
        bc.ain = rsGetAllocation(balls1);
        bc.aout = rsGetAllocation(balls2);
        bout = balls2;
    }

    bc.dimX = rsAllocationGetDimX(bc.ain);
    bc.dt = 1.f / 30.f;

    rsForEach(physics_script, bc.ain, bc.aout, &bc, sizeof(bc));

    for (uint32_t ct=0; ct < bc.dimX; ct++) {
        point[ct].position = bout[ct].position;
        point[ct].size = 6.f /*+ bout[ct].color.g * 6.f*/ * bout[ct].size;
    }

    frame++;
    rsgBindProgramFragment(gPFPoints);
    rsgDrawMesh(partMesh);
    return 1;
}

