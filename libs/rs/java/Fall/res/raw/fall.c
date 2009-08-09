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
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

#define RSID_STATE 0
#define RSID_FRAME_COUNT 0
#define RSID_WIDTH 1
#define RSID_HEIGHT 2
#define RSID_MESH_WIDTH 3
#define RSID_MESH_HEIGHT 4
#define RSID_RIPPLE_MAP_SIZE 5
#define RSID_RIPPLE_INDEX 6
#define RSID_DROP_X 7
#define RSID_DROP_Y 8
#define RSID_RUNNING 9
#define RSID_LEAVES_COUNT 10
    
#define RSID_RIPPLE_MAP 1
#define RSID_REFRACTION_MAP 2
#define RSID_LEAVES 3

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

#define LEAF_SIZE 0.35f

#define REFRACTION 1.333f
#define DAMP 3

#define DROP_RADIUS 2
// The higher, the smaller the ripple
#define RIPPLE_HEIGHT 10.0f

int offset(int x, int y, int width) {
    return x + 1 + (y + 1) * (width + 2);
}

void drop(int x, int y, int r) {
    int width = loadI32(RSID_STATE, RSID_MESH_WIDTH);
    int height = loadI32(RSID_STATE, RSID_MESH_HEIGHT);

    if (x < r) x = r;
    if (y < r) y = r;
    if (x >= width - r) x = width - r - 1;
    if (y >= height - r) x = height - r - 1;
    
    x = width - x;

    int rippleMapSize = loadI32(RSID_STATE, RSID_RIPPLE_MAP_SIZE);
    int index = loadI32(RSID_STATE, RSID_RIPPLE_INDEX);
    int origin = offset(0, 0, width);

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    int sqr = r * r;

    int h = 0;
    for ( ; h < r; h++) {
        int sqv = h * h;
        int yn = origin + (y - h) * (width + 2);
        int yp = origin + (y + h) * (width + 2);
        int w = 0;
        for ( ; w < r; w++) {
            int squ = w * w;
            if (squ + sqv < sqr) {
                int v = -sqrtf((sqr - (squ + sqv)) << 16);
                current[yn + x + w] = v;
                current[yp + x + w] = v;
                current[yn + x - w] = v;
                current[yp + x - w] = v;
            }
        }
    }
}

void updateRipples() {
    int rippleMapSize = loadI32(RSID_STATE, RSID_RIPPLE_MAP_SIZE);
    int width = loadI32(RSID_STATE, RSID_MESH_WIDTH);
    int height = loadI32(RSID_STATE, RSID_MESH_HEIGHT);
    int index = loadI32(RSID_STATE, RSID_RIPPLE_INDEX);
    int origin = offset(0, 0, width);

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    int* next = loadArrayI32(RSID_RIPPLE_MAP, (1 - index) * rippleMapSize + origin);

    storeI32(RSID_STATE, RSID_RIPPLE_INDEX, 1 - index);

    int a = 1;
    int b = width + 2;
    int h = height;
    while (h) {
        int w = width;
        while (w) {
            int droplet = ((current[-b] + current[b] + current[-a] + current[a]) >> 1) - next[0];
            droplet -= (droplet >> DAMP);
            next[0] = droplet;
            current++;
            next++;
            w--;
        }
        current += 2;
        next += 2;
        h--;
    }
}

int refraction(int d, int wave) {
    int* map = loadArrayI32(RSID_REFRACTION_MAP, 0);
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
    int rippleMapSize = loadI32(RSID_STATE, RSID_RIPPLE_MAP_SIZE);
    int width = loadI32(RSID_STATE, RSID_MESH_WIDTH);
    int height = loadI32(RSID_STATE, RSID_MESH_HEIGHT);
    int index = loadI32(RSID_STATE, RSID_RIPPLE_INDEX);
    int origin = offset(0, 0, width);

    int b = width + 2;

    int* current = loadArrayI32(RSID_RIPPLE_MAP, index * rippleMapSize + origin);
    float *vertices = loadTriangleMeshVerticesF(NAMED_mesh);

    int h = height - 1;
    while (h >= 0) {
        int w = width - 1;
        int wave = current[0];
        int offset = h * width;
        while (w >= 0) {
            int nextWave = current[1];
            int dx = nextWave - wave;
            int dy = current[b] - wave;

            int offsetx = refraction(dx, wave) >> 16;
            int u = (width - w) + offsetx;
            u &= ~(u >> 31);
            if (u >= width) u = width - 1;

            int offsety = refraction(dy, wave) >> 16;
            int v = (height - h) + offsety;
            v &= ~(v >> 31);
            if (v >= height) v = height - 1;

            vertices[(offset + w) * 8 + 3] = u / (float) width;
            vertices[(offset + w) * 8 + 4] = v / (float) height;

            // Update Z coordinate of the vertex
            vertices[(offset + w) * 8 + 7] = (dy / 512.0f) / RIPPLE_HEIGHT;
            
            w--;
            current++;
            wave = nextWave;
        }
        h--;
        current += 2;
    }

    // Compute the normals for lighting
    int y = 0;
    for ( ; y < height; y++) {
        int x = 0;
        int yOffset = y * width;
        for ( ; x < width; x++) {
            // V1
            float v1x = vertices[(yOffset + x) * 8 + 5];
            float v1y = vertices[(yOffset + x) * 8 + 6];
            float v1z = vertices[(yOffset + x) * 8 + 7];

            // V2
            float v2x = vertices[(yOffset + x + 1) * 8 + 5];
            float v2y = vertices[(yOffset + x + 1) * 8 + 6];
            float v2z = vertices[(yOffset + x + 1) * 8 + 7];
            
            // V3
            float v3x = vertices[(yOffset + width + x) * 8 + 5];
            float v3y = vertices[(yOffset + width + x) * 8 + 6];
            float v3z = vertices[(yOffset + width + x) * 8 + 7];

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
            float len = magf3(n3x, n3y, n3z);
            n3x /= len;
            n3y /= len;
            n3z /= len;
            
            // V2
            v2x = vertices[(yOffset + width + x + 1) * 8 + 5];
            v2y = vertices[(yOffset + width + x + 1) * 8 + 6];
            v2z = vertices[(yOffset + width + x + 1) * 8 + 7];

            // N1
            n1x = v2x - v1x;
            n1y = v2y - v1y;
            n1z = v2z - v1z;

            // N2
            n2x = v3x - v1x;
            n2y = v3y - v1y;
            n2z = v3z - v1z;

            // Avegare of previous normal and N1 x N2
            n3x = n3x / 2.0f + (n1y * n2z - n1z * n2y) / 2.0f;
            n3y = n3y / 2.0f + (n1z * n2x - n1x * n2z) / 2.0f;
            n3z = n3z / 2.0f + (n1x * n2y - n1y * n2x) / 2.0f;

            // Normalize
            len = magf3(n3x, n3y, n3z);
            n3x /= len;
            n3y /= len;
            n3z /= len;

            vertices[(yOffset + x) * 8 + 0] = n3x;
            vertices[(yOffset + x) * 8 + 1] = n3y;
            vertices[(yOffset + x) * 8 + 2] = -n3z;
            
            // reset Z
            vertices[(yOffset + x) * 8 + 7] = 0.0f;
        }
    }
}

void drawNormals() {
    int width = loadI32(RSID_STATE, RSID_MESH_WIDTH);
    int height = loadI32(RSID_STATE, RSID_MESH_HEIGHT);

    float *vertices = loadTriangleMeshVerticesF(NAMED_mesh);
    
    bindProgramVertex(NAMED_PVLines);
    color(1.0f, 0.0f, 0.0f, 1.0f);

    int y = 0;
    for ( ; y < height; y++) {
        int yOffset = y * width;
        int x = 0;
        for ( ; x < width; x++) {
            float vx = vertices[(yOffset + x) * 8 + 5];
            float vy = vertices[(yOffset + x) * 8 + 6];
            float vz = vertices[(yOffset + x) * 8 + 7];
            float nx = vertices[(yOffset + x) * 8 + 0];
            float ny = vertices[(yOffset + x) * 8 + 1];
            float nz = vertices[(yOffset + x) * 8 + 2];
            drawLine(vx, vy, vz, vx + nx / 10.0f, vy + ny / 10.0f, vz + nz / 10.0f);
        }
    }
}

void drawLeaf(int index, int frameCount) {
    float *leafStruct = loadArrayF(RSID_LEAVES, index);

    float x = leafStruct[LEAF_STRUCT_X];
    float x1 = x - LEAF_SIZE;
    float x2 = x + LEAF_SIZE;

    float y = leafStruct[LEAF_STRUCT_Y];
    float y1 = y - LEAF_SIZE;
    float y2 = y + LEAF_SIZE;

    float u1 = leafStruct[LEAF_STRUCT_U1];
    float u2 = leafStruct[LEAF_STRUCT_U2];

    drawQuadTexCoords(x1, y1, 0.0f, u1, 1.0f,
                      x2, y1, 0.0f, u2, 1.0f,
                      x2, y2, 0.0f, u2, 0.0f,
                      x1, y2, 0.0f, u1, 0.0f);
}

void drawLeaves(int frameCount) {
    bindProgramFragment(NAMED_PFLeaf);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindTexture(NAMED_PFLeaf, 0, NAMED_TLeaves);

    int leavesCount = loadI32(RSID_STATE, RSID_LEAVES_COUNT);
    int count = leavesCount * LEAF_STRUCT_FIELDS_COUNT;

    int i = 0;
    for ( ; i < count; i += LEAF_STRUCT_FIELDS_COUNT) {
        drawLeaf(i, frameCount);
    }
}

int main(int index) {
    int frameCount = loadI32(RSID_STATE, RSID_FRAME_COUNT);

    int dropX = loadI32(RSID_STATE, RSID_DROP_X);
    if (dropX != -1) {
        int dropY = loadI32(RSID_STATE, RSID_DROP_Y);
        drop(dropX, dropY, DROP_RADIUS);
        storeI32(RSID_STATE, RSID_DROP_X, -1);
        storeI32(RSID_STATE, RSID_DROP_Y, -1);
    }

    int isRunning = loadI32(RSID_STATE, RSID_RUNNING);
    if (isRunning) {
        updateRipples();
        generateRipples();
        updateTriangleMesh(NAMED_mesh);
    }

    bindTexture(NAMED_PFBackground, 0, NAMED_TRiverbed);
    drawTriangleMesh(NAMED_mesh);

    ambient(0.0f, 0.0f, 0.0f, 1.0f);
    diffuse(0.0f, 0.0f, 0.0f, 1.0f);
    specular(0.44f, 0.44f, 0.44f, 1.0f);
    shininess(40.0f);
    bindProgramFragment(NAMED_PFLighting);
    drawTriangleMesh(NAMED_mesh);

    drawLeaves(frameCount);

    if (!isRunning) {
        drawNormals();
    }

    frameCount++;
    storeI32(RSID_STATE, RSID_FRAME_COUNT, frameCount);

    return 1;
}
