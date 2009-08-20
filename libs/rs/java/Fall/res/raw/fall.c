// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)
#pragma stateVertex(PVSky)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

#define RSID_STATE 0
#define RSID_RIPPLE_MAP 1
#define RSID_REFRACTION_MAP 2
#define RSID_LEAVES 3
#define RSID_DROP 4

#define LEAF_STRUCT_FIELDS_COUNT 11
#define LEAF_STRUCT_X 0
#define LEAF_STRUCT_Y 1
#define LEAF_STRUCT_SCALE 2
#define LEAF_STRUCT_ANGLE 3
#define LEAF_STRUCT_SPIN 4
#define LEAF_STRUCT_U1 5
#define LEAF_STRUCT_U2 6
#define LEAF_STRUCT_ALTITUDE 7
#define LEAF_STRUCT_RIPPLED 8
#define LEAF_STRUCT_DELTAX 9
#define LEAF_STRUCT_DELTAY 10

#define LEAVES_TEXTURES_COUNT 4

#define LEAF_SIZE 0.55f

#define REFRACTION 1.333f
#define DAMP 3

#define DROP_RADIUS 2
// The higher, the smaller the ripple
#define RIPPLE_HEIGHT 10.0f

int offset(int x, int y, int width) {
    return x + 1 + (y + 1) * (width + 2);
}

void dropWithStrength(int x, int y, int r, int s) {
    int width = State_meshWidth;
    int height = State_meshHeight;

    if (x < r) x = r;
    if (y < r) y = r;
    if (x >= width - r) x = width - r - 1;
    if (y >= height - r) y = height - r - 1;

    x = width - x;

    int rippleMapSize = State_rippleMapSize;
    int index = State_rippleIndex;
    int origin = offset(0, 0, width);

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    int sqr = r * r;
    float invs = 1.0f / s;

    int h = 0;
    for ( ; h < r; h += 1) {
        int sqv = h * h;
        int yn = origin + (y - h) * (width + 2);
        int yp = origin + (y + h) * (width + 2);
        int w = 0;
        for ( ; w < r; w += 1) {
            int squ = w * w;
            if (squ + sqv < sqr) {
                int v = -sqrtf((sqr - (squ + sqv)) << 16) * invs;
                current[yn + x + w] = v;
                current[yp + x + w] = v;
                current[yn + x - w] = v;
                current[yp + x - w] = v;
            }
        }
    }
}

void drop(int x, int y, int r) {
    dropWithStrength(x, y, r, 1);
}

void updateRipples() {
    int rippleMapSize = State_rippleMapSize;
    int width = State_meshWidth;
    int height = State_meshHeight;
    int index = State_rippleIndex;
    int origin = offset(0, 0, width);

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    int* next = loadArrayI32(RSID_RIPPLE_MAP, (1 - index) * rippleMapSize + origin);

    storeI32(RSID_STATE, OFFSETOF_WorldState_rippleIndex, 1 - index);

    int a = 1;
    int b = width + 2;
    int h = height;
    while (h) {
        int w = width;
        while (w) {
            int droplet = ((current[-b] + current[b] + current[-a] + current[a]) >> 1) - *next;
            droplet -= (droplet >> DAMP);
            *next = droplet;
            current += 1;
            next += 1;
            w -= 1;
        }
        current += 2;
        next += 2;
        h -= 1;
    }
}

int refraction(int d, int wave, int *map) {
    int i = d;
    if (i < 0) i = -i;
    if (i > 512) i = 512;
    int w = (wave + 0x10000) >> 8;
    w &= ~(w >> 31);
    int r = (map[i] * w) >> 3;
    if (d < 0) {
        return -r;
    }
    return r;
}

void generateRipples() {
    int rippleMapSize = loadI32(RSID_STATE, OFFSETOF_WorldState_rippleMapSize);
    int width = State_meshWidth;
    int height = State_meshHeight;
    int index = State_rippleIndex;
    int origin = offset(0, 0, width);

    int b = width + 2;

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    int *map = loadArrayI32(RSID_REFRACTION_MAP, 0);
    float *vertices = loadTriangleMeshVerticesF(NAMED_WaterMesh);

    float fw = (float) width;
    float fh = (float) height;
    float fy = (1.0f / 512.0f) * (1.0f / RIPPLE_HEIGHT);

    int h = height - 1;
    while (h >= 0) {
        int w = width - 1;
        int wave = *current;
        int offset = h * width;
        while (w >= 0) {
            int nextWave = current[1];
            int dx = nextWave - wave;
            int dy = current[b] - wave;

            int offsetx = refraction(dx, wave, map) >> 16;
            int u = (width - w) + offsetx;
            u &= ~(u >> 31);
            if (u >= width) u = width - 1;

            int offsety = refraction(dy, wave, map) >> 16;
            int v = (height - h) + offsety;
            v &= ~(v >> 31);
            if (v >= height) v = height - 1;

            int index = (offset + w) << 3;
            vertices[index + 3] = u / fw;
            vertices[index + 4] = v / fh;

            // Update Z coordinate of the vertex
            vertices[index + 7] = dy * fy;
            
            w -= 1;
            current += 1;
            wave = nextWave;
        }
        h -= 1;
        current += 2;
    }

    // Compute the normals for lighting
    int y = 0;
    int w8 = width << 3;
    for ( ; y < height; y += 1) {
        int x = 0;
        int yOffset = y * width;
        for ( ; x < width; x += 1) {
            int o = (yOffset + x) << 3;
            int o1 = o + 8;
            int ow = o + w8;
            int ow1 = ow + 8;

            // V1
            float v1x = vertices[o + 5];
            float v1y = vertices[o + 6];
            float v1z = vertices[o + 7];

            // V2
            float v2x = vertices[o1 + 5];
            float v2y = vertices[o1 + 6];
            float v2z = vertices[o1 + 7];
            
            // V3
            float v3x = vertices[ow + 5];
            float v3y = vertices[ow + 6];
            float v3z = vertices[ow + 7];

            // N1
            float n1x = v2x - v1x;
            float n1y = v2y - v1y;
            float n1z = v2z - v1z;

            // N2
            float n2x = v3x - v1x;
            float n2y = v3y - v1y;
            float n2z = v3z - v1z;

            // N1 x N2
            float n3x = n1y * n2z - n1z * n2y;
            float n3y = n1z * n2x - n1x * n2z;
            float n3z = n1x * n2y - n1y * n2x;

            // Normalize
            float len = 1.0f / magf3(n3x, n3y, n3z);
            n3x *= len;
            n3y *= len;
            n3z *= len;
            
            // V2
            v2x = vertices[ow1 + 5];
            v2y = vertices[ow1 + 6];
            v2z = vertices[ow1 + 7];

            // N1
            n1x = v2x - v1x;
            n1y = v2y - v1y;
            n1z = v2z - v1z;

            // N2
            n2x = v3x - v1x;
            n2y = v3y - v1y;
            n2z = v3z - v1z;

            // Average of previous normal and N1 x N2
            n3x = n3x * 0.5f + (n1y * n2z - n1z * n2y) * 0.5f;
            n3y = n3y * 0.5f + (n1z * n2x - n1x * n2z) * 0.5f;
            n3z = n3z * 0.5f + (n1x * n2y - n1y * n2x) * 0.5f;

            // Normalize
            len = 1.0f / magf3(n3x, n3y, n3z);
            n3x *= len;
            n3y *= len;
            n3z *= len;

            vertices[o + 0] = n3x;
            vertices[o + 1] = n3y;
            vertices[o + 2] = -n3z;
            
            // reset Z
            //vertices[(yOffset + x) << 3 + 7] = 0.0f;
        }
    }
}

float averageZ(float x1, float x2, float y1, float y2, float* vertices,
        int meshWidth, int meshHeight, float glWidth, float glHeight) {

    x1 = ((x1 + glWidth * 0.5f) / glWidth) * meshWidth;
    x2 = ((x2 + glWidth * 0.5f) / glWidth) * meshWidth;
    y1 = ((y1 + glHeight * 0.5f) / glHeight) * meshHeight;
    y2 = ((y2 + glHeight * 0.5f) / glHeight) * meshHeight;

    int quadX1 = clamp(x1, 0, meshWidth);
    int quadX2 = clamp(x2, 0, meshWidth);
    int quadY1 = clamp(y1, 0, meshHeight);
    int quadY2 = clamp(y2, 0, meshHeight);

    float z = 0.0f;
    int vertexCount = 0;

    int y = quadY1;
    for ( ; y < quadY2; y += 1) {
        int x = quadX1;
        int yOffset = y * meshWidth;
        for ( ; x < quadX2; x += 1) {
            z += vertices[(yOffset + x) << 3 + 7];
            vertexCount += 1;
        }
    }

    return 55.0f * z / vertexCount;
}

void drawLeaf(int index, float* vertices, int meshWidth, int meshHeight,
        float glWidth, float glHeight) {

    float *leafStruct = loadArrayF(RSID_LEAVES, index);

    float x = leafStruct[LEAF_STRUCT_X];
    float x1 = x - LEAF_SIZE;
    float x2 = x + LEAF_SIZE;

    float y = leafStruct[LEAF_STRUCT_Y];
    float y1 = y - LEAF_SIZE;
    float y2 = y + LEAF_SIZE;

    float u1 = leafStruct[LEAF_STRUCT_U1];
    float u2 = leafStruct[LEAF_STRUCT_U2];

    float z1 = 0.0f;
    float z2 = 0.0f;
    float z3 = 0.0f;
    float z4 = 0.0f;
    
    float a = leafStruct[LEAF_STRUCT_ALTITUDE];
    float s = leafStruct[LEAF_STRUCT_SCALE];
    float r = leafStruct[LEAF_STRUCT_ANGLE];

    float tz = 0.0f;
    if (a > 0.0f) {
        tz = -a;
    } else {
//        z1 = averageZ(x1, x, y1, y, vertices, meshWidth, meshHeight, glWidth, glHeight);
//        z2 = averageZ(x, x2, y1, y, vertices, meshWidth, meshHeight, glWidth, glHeight);
//        z3 = averageZ(x, x2, y, y2, vertices, meshWidth, meshHeight, glWidth, glHeight);
//        z4 = averageZ(x1, x, y, y2, vertices, meshWidth, meshHeight, glWidth, glHeight);
    }

    x1 -= x;
    x2 -= x;
    y1 -= y;
    y2 -= y;

    float matrix[16];
    matrixLoadIdentity(matrix);
    matrixTranslate(matrix, x, y, tz);
    matrixScale(matrix, s, s, 1.0f);
    matrixRotate(matrix, r, 0.0f, 0.0f, 1.0f);
    vpLoadModelMatrix(matrix);

    drawQuadTexCoords(x1, y1, z1, u1, 1.0f,
                      x2, y1, z2, u2, 1.0f,
                      x2, y2, z3, u2, 0.0f,
                      x1, y2, z4, u1, 0.0f);

    float spin = leafStruct[LEAF_STRUCT_SPIN];
    if (a <= 0.0f) {
        float rippled = leafStruct[LEAF_STRUCT_RIPPLED];
        if (rippled < 0.0f) {
            drop(((x + glWidth * 0.5f) / glWidth) * meshWidth,
                 meshHeight - ((y + glHeight * 0.5f) / glHeight) * meshHeight,
                 DROP_RADIUS);
            spin /= 4.0f;
            leafStruct[LEAF_STRUCT_SPIN] = spin;
            leafStruct[LEAF_STRUCT_RIPPLED] = 1.0f;
        } else {
//            dropWithStrength(((x + glWidth / 2.0f) / glWidth) * meshWidth,
//                meshHeight - ((y + glHeight / 2.0f) / glHeight) * meshHeight,
//                2, 5);
        }
        leafStruct[LEAF_STRUCT_X] = x + leafStruct[LEAF_STRUCT_DELTAX];
        leafStruct[LEAF_STRUCT_Y] = y + leafStruct[LEAF_STRUCT_DELTAY];
        r += spin;
        leafStruct[LEAF_STRUCT_ANGLE] = r;
    } else {
        a -= 0.005f;
        leafStruct[LEAF_STRUCT_ALTITUDE] = a;
        r += spin * 2.0f;
        leafStruct[LEAF_STRUCT_ANGLE] = r;
    }

    if (-LEAF_SIZE * s + x > glWidth / 2.0f || LEAF_SIZE * s + x < -glWidth / 2.0f ||
        LEAF_SIZE * s + y < -glHeight / 2.0f) {

        int sprite = randf(LEAVES_TEXTURES_COUNT);
        leafStruct[LEAF_STRUCT_X] = randf2(-1.0f, 1.0f);   
        leafStruct[LEAF_STRUCT_Y] = glHeight / 2.0f + LEAF_SIZE * 2 * randf(1.0f);
        leafStruct[LEAF_STRUCT_SCALE] = randf2(0.4f, 0.5f);
        leafStruct[LEAF_STRUCT_SPIN] = degf(randf2(-0.02f, 0.02f)) / 4.0f;
        leafStruct[LEAF_STRUCT_U1] = sprite / (float) LEAVES_TEXTURES_COUNT;
        leafStruct[LEAF_STRUCT_U2] = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
        leafStruct[LEAF_STRUCT_DELTAX] = randf2(-0.02f, 0.02f) / 60.0f;
        leafStruct[LEAF_STRUCT_DELTAY] = -0.08f * randf2(0.9f, 1.1f) / 60.0f;
    }
}

void drawLeaves() {
    bindProgramFragment(NAMED_PFBackground);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindProgramVertex(NAMED_PVSky);
    bindTexture(NAMED_PFBackground, 0, NAMED_TLeaves);

    int leavesCount = State_leavesCount;
    int count = leavesCount * LEAF_STRUCT_FIELDS_COUNT;
    int width = State_meshWidth;
    int height = State_meshHeight;    
    float glWidth = State_glWidth;
    float glHeight = State_glHeight;

    float *vertices = loadTriangleMeshVerticesF(NAMED_WaterMesh);    

    int i = 0;
    for ( ; i < count; i += LEAF_STRUCT_FIELDS_COUNT) {
        drawLeaf(i, vertices, width, height, glWidth, glHeight);
    }
    
    float matrix[16];
    matrixLoadIdentity(matrix);
    vpLoadModelMatrix(matrix);
}

void drawRiverbed() {
    bindTexture(NAMED_PFBackground, 0, NAMED_TRiverbed);

    drawTriangleMesh(NAMED_WaterMesh);
}

void drawSky() {
    color(1.0f, 1.0f, 1.0f, 0.8f);

    bindProgramFragment(NAMED_PFSky);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindTexture(NAMED_PFSky, 0, NAMED_TSky);

    float x = State_skyOffsetX + State_skySpeedX;
    float y = State_skyOffsetY + State_skySpeedY;

    if (x > 1.0f) x = 0.0f;
    if (x < -1.0f) x = 0.0f;
    if (y > 1.0f) y = 0.0f;

    storeF(RSID_STATE, OFFSETOF_WorldState_skyOffsetX, x);
    storeF(RSID_STATE, OFFSETOF_WorldState_skyOffsetY, y);

    float matrix[16];
    matrixLoadTranslate(matrix, x, y, 0.0f);
    vpLoadTextureMatrix(matrix);

    drawTriangleMesh(NAMED_WaterMesh);

    matrixLoadIdentity(matrix);
    vpLoadTextureMatrix(matrix);
}

void drawLighting() {
    ambient(0.0f, 0.0f, 0.0f, 1.0f);
    diffuse(0.0f, 0.0f, 0.0f, 1.0f);
    specular(0.44f, 0.44f, 0.44f, 1.0f);
    shininess(40.0f);

    bindProgramFragmentStore(NAMED_PFSBackground);
    bindProgramFragment(NAMED_PFLighting);
    bindProgramVertex(NAMED_PVLight);

    drawTriangleMesh(NAMED_WaterMesh);
}

void drawNormals() {
    int width = State_meshWidth;
    int height = State_meshHeight;

    float *vertices = loadTriangleMeshVerticesF(NAMED_WaterMesh);

    bindProgramVertex(NAMED_PVSky);
    bindProgramFragment(NAMED_PFLighting);

    color(1.0f, 0.0f, 0.0f, 1.0f);

    float scale = 1.0f / 10.0f;
    int y = 0;
    for ( ; y < height; y += 1) {
        int yOffset = y * width;
        int x = 0;
        for ( ; x < width; x += 1) {
            int offset = (yOffset + x) << 3;
            float vx = vertices[offset + 5];
            float vy = vertices[offset + 6];
            float vz = vertices[offset + 7];
            float nx = vertices[offset + 0];
            float ny = vertices[offset + 1];
            float nz = vertices[offset + 2];
            drawLine(vx, vy, vz, vx + nx * scale, vy + ny * scale, vz + nz * scale);
        }
    }
}

int main(int index) {
    int dropX = Drop_dropX;
    if (dropX != -1) {
        int dropY = Drop_dropY;
        drop(dropX, dropY, DROP_RADIUS);
        storeI32(RSID_DROP, OFFSETOF_DropState_dropX, -1);
        storeI32(RSID_DROP, OFFSETOF_DropState_dropY, -1);
    }

    updateRipples();
    generateRipples();
    updateTriangleMesh(NAMED_WaterMesh);

    drawRiverbed();
    drawSky();
    drawLighting();
    drawLeaves();
    //drawNormals();

    return 1;
}
