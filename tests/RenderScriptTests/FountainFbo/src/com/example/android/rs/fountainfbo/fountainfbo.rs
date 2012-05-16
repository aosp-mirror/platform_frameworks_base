// Fountain test script
#pragma version(1)

#pragma rs java_package_name(com.example.android.rs.fountainfbo)

#pragma stateFragment(parent)

#include "rs_graphics.rsh"

static int newPart = 0;
rs_mesh partMesh;
rs_program_vertex gProgramVertex;

//allocation for color buffer
rs_allocation gColorBuffer;
//fragment shader for rendering without a texture (used for rendering to framebuffer object)
rs_program_fragment gProgramFragment;
//fragment shader for rendering with a texture (used for rendering to default framebuffer)
rs_program_fragment gTextureProgramFragment;

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
    //Tell Renderscript runtime to render to the frame buffer object
    rsgBindColorTarget(gColorBuffer, 0);

    //Begin rendering on a white background
    rsgClearColor(1.f, 1.f, 1.f, 1.f);
    rsgDrawMesh(partMesh);

    //When done, tell Renderscript runtime to stop rendering to framebuffer object
    rsgClearAllRenderTargets();

    //Bind a new fragment shader that declares the framebuffer object to be used as a texture
    rsgBindProgramFragment(gTextureProgramFragment);

    //Bind the framebuffer object to the fragment shader at slot 0 as a texture
    rsgBindTexture(gTextureProgramFragment, 0, gColorBuffer);

    //Draw a quad using the framebuffer object as the texture
    float startX = 10, startY = 10;
    float s = 256;
    rsgDrawQuadTexCoords(startX, startY, 0, 0, 1,
                         startX, startY + s, 0, 0, 0,
                         startX + s, startY + s, 0, 1, 0,
                         startX + s, startY, 0, 1, 1);

    //Rebind the original fragment shader to render as normal
    rsgBindProgramFragment(gProgramFragment);

    //Render the main scene
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


