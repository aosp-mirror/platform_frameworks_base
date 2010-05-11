// Fountain test script
#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

static int newPart = 0;

float4 partColor;
rs_mesh partMesh;
rs_allocation partBuffer;

typedef struct __attribute__((packed, aligned(4))) Point_s {
    float2 delta;
    rs_position2 pos;
    rs_color4u color;
} Point_t;
Point_t *point;

#pragma rs export_var(point, partColor, partMesh, partBuffer)
//#pragma rs export_type(Point_s)
//#pragma rs export_element(point)

int root() {
    debugPf(1, partColor.x);
    debugPi(4, partMesh);
    debugPi(5, partBuffer);

    float height = getHeight();
    int size = allocGetDimX(partBuffer);

    Point_t * p = point;
    for (int ct=0; ct < size; ct++) {
        p->delta.y += 0.15f;
        p->pos += p->delta;
        if ((p->pos.y > height) && (p->delta.y > 0)) {
            p->delta.y *= -0.3f;
        }
        p++;
    }

    uploadToBufferObject(partBuffer);
    drawSimpleMesh(partMesh);
    return 1;
}

void addParticles(int rate, int x, int y)
{
    float rMax = ((float)rate) * 0.005f;
    int size = allocGetDimX(partBuffer);

    rs_color4u c = convertColorTo8888(partColor.x, partColor.y, partColor.z);
    Point_t * np = &point[newPart];

    float2 p = {x, y};
    while (rate--) {
        np->delta = vec2Rand(rMax);
        np->pos = p;
        np->color = c;
        newPart++;
        np++;
        if (newPart >= size) {
            newPart = 0;
            np = &point[newPart];
        }
    }
}

