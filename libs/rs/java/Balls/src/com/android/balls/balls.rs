#pragma version(1)
#pragma rs java_package_name(com.android.balls)
#include "rs_graphics.rsh"

#include "balls.rsh"

#pragma stateVertex(parent)
#pragma stateStore(parent)

rs_program_fragment gPFPoints;
rs_program_fragment gPFLines;
rs_mesh partMesh;
rs_mesh arcMesh;

typedef struct __attribute__((packed, aligned(4))) Point {
    float2 position;
    uchar4 color;
    float size;
} Point_t;
Point_t *point;
Point_t *arc;

typedef struct VpConsts {
    //rs_matrix4x4 Proj;
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
        balls1[ct].arcID = -1;
        balls1[ct].color = 0.f;
    }
}



int root() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);

    BallControl_t bc = {0};
    Ball_t *bout;

    if (frame & 1) {
        rsSetObject(&bc.ain, rsGetAllocation(balls2));
        rsSetObject(&bc.aout, rsGetAllocation(balls1));
        bout = balls2;
    } else {
        rsSetObject(&bc.ain, rsGetAllocation(balls1));
        rsSetObject(&bc.aout, rsGetAllocation(balls2));
        bout = balls1;
    }

    bc.dimX = rsAllocationGetDimX(bc.ain);
    bc.dt = 1.f / 30.f;

    rsForEach(physics_script, bc.ain, bc.aout, &bc);

    uint32_t arcIdx = 0;
    for (uint32_t ct=0; ct < bc.dimX; ct++) {
        point[ct].position = bout[ct].position;
        point[ct].color = rsPackColorTo8888(bout[ct].color);
        point[ct].size = 6.f + bout[ct].color.g * 6.f;

        if (bout[ct].arcID >= 0) {
            arc[arcIdx].position = bout[ct].position;
            arc[arcIdx].color.r = min(bout[ct].arcStr, 1.f) * 0xff;
            arc[arcIdx].color.g = 0;
            arc[arcIdx].color.b = 0;
            arc[arcIdx].color.a = 0xff;
            arc[arcIdx+1].position = bout[bout[ct].arcID].position;
            arc[arcIdx+1].color = arc[arcIdx].color;
            arcIdx += 2;
        }
    }

    frame++;
    rsgBindProgramFragment(gPFLines);
    rsgDrawMesh(arcMesh, 0, 0, arcIdx);
    rsgBindProgramFragment(gPFPoints);
    rsgDrawMesh(partMesh);
    rsClearObject(&bc.ain);
    rsClearObject(&bc.aout);
    return 1;
}

