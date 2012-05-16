// Fountain test script
#pragma version(1)
#pragma rs_fp_relaxed

#pragma rs java_package_name(com.example.android.rs.fountain)

#pragma stateFragment(parent)

#include "rs_graphics.rsh"

static int newPart = 0;
rs_mesh partMesh;

typedef struct __attribute__((packed, aligned(4))) Point {
    float2 delta;
    float2 position;
    uchar4 color;
} Point_t;
Point_t *point;

int root() {
    float dt = min(rsGetDt(), 0.1f);
    rsgClearColor(0.f, 0.f, 0.f, 1.f);
    const float height = rsgGetHeight();
    const int size = rsAllocationGetDimX(rsGetAllocation(point));
    float dy2 = dt * (10.f);
    Point_t * p = point;
    for (int ct=0; ct < size; ct++) {
        p->delta.y += dy2;
        p->position += p->delta;
        if ((p->position.y > height) && (p->delta.y > 0)) {
            p->delta.y *= -0.3f;
        }
        p++;
    }

    rsgDrawMesh(partMesh);
    return 1;
}

static float4 partColor[10];
void addParticles(int rate, float x, float y, int index, bool newColor)
{
    if (newColor) {
        partColor[index].x = rsRand(0.5f, 1.0f);
        partColor[index].y = rsRand(1.0f);
        partColor[index].z = rsRand(1.0f);
    }
    float rMax = ((float)rate) * 0.02f;
    int size = rsAllocationGetDimX(rsGetAllocation(point));
    uchar4 c = rsPackColorTo8888(partColor[index]);

    Point_t * np = &point[newPart];
    float2 p = {x, y};
    while (rate--) {
        float angle = rsRand(3.14f * 2.f);
        float len = rsRand(rMax);
        np->delta.x = len * sin(angle);
        np->delta.y = len * cos(angle);
        np->position = p;
        np->color = c;
        newPart++;
        np++;
        if (newPart >= size) {
            newPart = 0;
            np = &point[newPart];
        }
    }
}

