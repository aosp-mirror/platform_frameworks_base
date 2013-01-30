#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.balls)
#include "rs_graphics.rsh"

#include "balls.rsh"

#pragma stateVertex(parent)
#pragma stateStore(parent)

rs_program_fragment gPFPoints;
rs_mesh partMesh;

rs_allocation gGrid;
BallGrid_t *unused1;
float2 *gGridCache;

typedef struct __attribute__((packed, aligned(4))) Point {
    float2 position;
    uchar4 color;
} Point_t;
Point_t *point;

typedef struct VpConsts {
    rs_matrix4x4 MVP;
} VpConsts_t;
VpConsts_t *vpConstants;

rs_script physics_script;


void initParts(int w, int h)
{
    uint32_t dimX = rsAllocationGetDimX(rsGetAllocation(balls));

    for (uint32_t ct=0; ct < dimX; ct++) {
        balls[ct].position.x = rsRand(0.f, (float)w);
        balls[ct].position.y = rsRand(0.f, (float)h);
        balls[ct].delta.x = 0.f;
        balls[ct].delta.y = 0.f;
    }
}

int root() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);

    int2 gridDims = (int2){ rsAllocationGetDimX(gGrid),
                            rsAllocationGetDimY(gGrid) };

    rs_allocation ain = rsGetAllocation(balls);
    int32_t dimX = rsAllocationGetDimX(ain);

    // Binning
    // Clear the particle list
    for (uint32_t ct=0; ct < dimX; ct++) {
        balls[ct].next = -1;
    }

    // Clear the grid
    for (uint32_t y=0; y < gridDims.y; y++) {
        for (uint32_t x=0; x < gridDims.x; x++) {
            BallGrid_t *bg = (BallGrid_t *)rsGetElementAt(gGrid, x, y);
            bg->count = 0;
            bg->idx = -1;
        }
    }

    // Create the particle list per grid
    for (uint32_t ct=0; ct < dimX; ct++) {
        int2 p = convert_int2(balls[ct].position / 100.f);
        p.x = rsClamp(p.x, 0, (int)(gridDims.x-1));
        p.y = rsClamp(p.y, 0, (int)(gridDims.y-1));
        BallGrid_t *bg = (BallGrid_t *)rsGetElementAt(gGrid, p.x, p.y);
        bg->count ++;
        balls[ct].next = bg->idx;
        bg->idx = ct;
    }

    // Create the sorted grid cache
    uint32_t gridIdx = 0;
    for (uint32_t y=0; y < gridDims.y; y++) {
        for (uint32_t x=0; x < gridDims.x; x++) {
            BallGrid_t *bg = (BallGrid_t *)rsGetElementAt(gGrid, x, y);
            bg->cacheIdx = gridIdx;

            int idx = bg->idx;
            while (idx >= 0) {
                const Ball_t * bPtr = &balls[idx];
                gGridCache[gridIdx++] = bPtr->position;
                idx = bPtr->next;
            }
        }
    }


    rsForEach(physics_script, ain, ain);

    for (uint32_t ct=0; ct < dimX; ct++) {
        point[ct].position = balls[ct].position;
        point[ct].color = balls[ct].color;
    }

    rsgBindProgramFragment(gPFPoints);
    rsgDrawMesh(partMesh);
    return 1;
}

