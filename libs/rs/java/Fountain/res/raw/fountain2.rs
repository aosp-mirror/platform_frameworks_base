// Fountain test script
#pragma version(1)

#include "../../../../scriptc/rs_types.rsh"
#include "../../../../scriptc/rs_math.rsh"
#include "../../../../scriptc/rs_graphics.rsh"

static int newPart = 0;

typedef struct Control_s {
    int x, y;
    int rate;
    int count;
    float r, g, b;
    rs_mesh partMesh;
    rs_allocation partBuffer;
} Control_t;
Control_t *Control;

typedef struct Point_s{
    float2 delta;
    rs_position2 pos;
    rs_color4u color;
} Point_t;
Point_t *point;

int main(int launchID) {
    int ct;
    int count = Control->count;
    int rate = Control->rate;
    float height = getHeight();
    Point_t * p = point;

    if (rate) {
        float rMax = ((float)rate) * 0.005f;
        int color = ((int)(Control->r * 255.f)) |
                    ((int)(Control->g * 255.f)) << 8 |
                    ((int)(Control->b * 255.f)) << 16 |
                    (0xf0 << 24);
        Point_t * np = &p[newPart];

        while (rate--) {
            np->delta.x = rand(rMax);
            np->delta.y = rand(rMax);
            //np->delta = vec2Rand(rMax);
            np->pos.x = Control->x;
            np->pos.y = Control->y;
            np->color = color;
            newPart++;
            np++;
            if (newPart >= count) {
                newPart = 0;
                np = &p[newPart];
            }
        }
    }

    for (ct=0; ct < count; ct++) {
        float dy = p->delta.y + 0.15f;
        float posy = p->pos.y + dy;
        if ((posy > height) && (dy > 0)) {
            dy *= -0.3f;
        }
        p->delta.y = dy;
        p->pos.x += p->delta.x;
        p->pos.y = posy;
        p++;
    }

    uploadToBufferObject(Control->partBuffer);
    drawSimpleMesh(Control->partMesh);
    return 1;
}
