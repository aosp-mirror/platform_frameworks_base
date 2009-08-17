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
#define RSID_PARTICLES_COUNT 3
#define RSID_GALAXY_RADIUS 4

#define RSID_PARTICLES 1

#define PARTICLE_STRUCT_FIELDS_COUNT 7
#define PARTICLE_STRUCT_ANGLE 0
#define PARTICLE_STRUCT_DISTANCE 1
#define PARTICLE_STRUCT_SPEED 2
#define PARTICLE_STRUCT_Z 3
#define PARTICLE_STRUCT_RADIUS 4
#define PARTICLE_STRUCT_U1 5
#define PARTICLE_STRUCT_U2 6

#define RSID_PARTICLES_BUFFER 2
#define PARTICLE_BUFFER_COMPONENTS_COUNT 6

#define PARTICLES_TEXTURES_COUNT 2

#define ELLIPSE_RATIO 0.86f
#define ELLIPSE_TWIST 0.02333333333f

void drawSpace(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSpace);
    drawQuadTexCoords(
            0.0f, 0.0f, 0.0f,
            0.0f, 1.0f,
            width, 0.0f, 0.0f,
            2.0f, 1.0f,
            width, height, 0.0f,
            2.0f, 0.0f,
            0.0f, height, 0.0f,
            0.0f, 0.0f);
}

void drawLights(int width, int height) {
    bindProgramFragment(NAMED_PFBackground);
    bindProgramFragmentStore(NAMED_PFSLights);

    float x = (width - 512.0f) / 2.0f;
    float y = (height - 512.0f) / 2.0f;

    bindTexture(NAMED_PFBackground, 0, NAMED_TLight1);
    drawQuad(x + 512.0f, y         , 0.0f,
             x         , y         , 0.0f,
             x         , y + 512.0f, 0.0f,
             x + 512.0f, y + 512.0f, 0.0f);

    bindTexture(NAMED_PFBackground, 0, NAMED_TLight2);
    drawQuad(x + 512.0f, y         , 0.0f,
             x         , y         , 0.0f,
             x         , y + 512.0f, 0.0f,
             x + 512.0f, y + 512.0f, 0.0f);
}

void drawParticle(int index, int bufferIndex, int width, int height, int radius) {
    float *particle = loadArrayF(RSID_PARTICLES, index);

    float distance = particle[PARTICLE_STRUCT_DISTANCE];
    float angle = particle[PARTICLE_STRUCT_ANGLE];
    float speed = particle[PARTICLE_STRUCT_SPEED];
    float r = particle[PARTICLE_STRUCT_RADIUS];

    int red;
    int green;
    int blue;

    if (distance < radius / 3.0f) {
        red = 220 + (distance / (float) radius) * 35;
        green = 220;
        blue = 220;
    } else {
        red = 180;
        green = 180;
        blue = clamp(140 + (distance / (float) radius) * 115, 140, 255);
    }

    int color = 0xFF000000 | red | green << 8 | blue << 16;

    float a = angle + speed * (0.5f + (0.5f * radius / distance));
    float x = distance * sinf(a);
    float y = distance * cosf(a) * ELLIPSE_RATIO;
    float z = distance * ELLIPSE_TWIST;
    float s = cosf(z);
    float t = sinf(z);

    float sX = t * x + s * y + width / 2.0f;
    float sY = s * x - t * y + height / 2.0f;
    float sZ = particle[PARTICLE_STRUCT_Z];

    float u1 = particle[PARTICLE_STRUCT_U1];
    float u2 = particle[PARTICLE_STRUCT_U2];

    // lower left vertex of the particle's triangle
    storeI32(RSID_PARTICLES_BUFFER, bufferIndex, color);        // ABGR

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 1, sX - r);     // X
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 2, sY + r);     // Y
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 3, sZ);         // Z

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 4, u1);         // S
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 5, 1.0f);       // T

    // lower right vertex of the particle's triangle
    bufferIndex += PARTICLE_BUFFER_COMPONENTS_COUNT;
    storeI32(RSID_PARTICLES_BUFFER, bufferIndex, color);

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 1, sX + r);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 2, sY + r);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 3, sZ);

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 4, u2);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 5, 1.0f);

    // upper middle vertex of the particle's triangle
    bufferIndex += PARTICLE_BUFFER_COMPONENTS_COUNT;
    storeI32(RSID_PARTICLES_BUFFER, bufferIndex, color);

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 1, sX);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 2, sY - r);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 3, sZ);

    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 4, u1 + (u2 - u1) / 2.0f);
    storeF(RSID_PARTICLES_BUFFER, bufferIndex + 5, 0.0f);

    particle[PARTICLE_STRUCT_ANGLE] = a;
}

void drawParticles(int width, int height) {
    bindProgramFragment(NAMED_PFLighting);
    bindTexture(NAMED_PFLighting, 0, NAMED_TFlares);

    int radius = loadI32(RSID_STATE, RSID_GALAXY_RADIUS);
    int particlesCount = loadI32(RSID_STATE, RSID_PARTICLES_COUNT);
    int count = particlesCount * PARTICLE_STRUCT_FIELDS_COUNT;

    int i = 0;
    int bufferIndex = 0;
    for ( ; i < count; i += PARTICLE_STRUCT_FIELDS_COUNT) {
        drawParticle(i, bufferIndex, width, height, radius);
        // each particle is a triangle (3 vertices) of 6 properties (ABGR, X, Y, Z, S, T)
        bufferIndex += 3 * PARTICLE_BUFFER_COMPONENTS_COUNT;
    }

    uploadToBufferObject(NAMED_BParticles);
    drawSimpleMeshRange(NAMED_MParticles, 0, particlesCount * 3);
}

int main(int index) {
    int width = loadI32(RSID_STATE, RSID_WIDTH);
    int height = loadI32(RSID_STATE, RSID_HEIGHT);

    drawSpace(width, height);
    drawParticles(width, height);
    drawLights(width, height);

    return 1;
}
