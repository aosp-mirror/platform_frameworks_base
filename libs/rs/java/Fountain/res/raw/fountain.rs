// Fountain test script
#pragma version(1)

#pragma rs java_package_name(com.android.fountain)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

static int newPart = 0;

static float4 partColor;
rs_mesh partMesh;

typedef struct __attribute__((packed, aligned(4))) Point {
    float2 delta;
    float2 position;
    uchar4 color;
} Point_t;
Point_t *point;

#pragma rs export_var(point, partColor, partMesh)

int root() {
    rsgClearColor(0.f, 0.f, 0.f, 1.f);
    float height = rsgGetHeight();
    rs_allocation alloc = rsGetAllocation(point);
    int size = rsAllocationGetDimX(alloc);

    Point_t * p = point;
    for (int ct=0; ct < size; ct++) {
        p->delta.y += 0.15f;
        p->position += p->delta;
        if ((p->position.y > height) && (p->delta.y > 0)) {
            p->delta.y *= -0.3f;
        }
        p++;
    }

    rsgUploadToBufferObject(alloc);
    rsgDrawSimpleMesh(partMesh);
    return 1;
}

#pragma rs export_func(addParticles)

void addParticles(int rate, float x, float y, int newColor)
{
    if (newColor) {
        partColor.x = rsRand(0.5f, 1.0f);
        partColor.y = rsRand(1.0f);
        partColor.z = rsRand(1.0f);
    }
    float rMax = ((float)rate) * 0.005f;
    int size = rsAllocationGetDimX(rsGetAllocation(point));
    uchar4 c = rsPackColorTo8888(partColor);

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

