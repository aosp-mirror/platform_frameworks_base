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

#define WVGA_PORTRAIT_WIDTH 480.0f
#define WVGA_PORTRAIT_HEIGHT 762.0f

#define RSID_STATE 0
#define RSID_FRAME_COUNT 0
#define RSID_BLADES_COUNT 1

#define RSID_SKY_TEXTURES 1
#define RSID_SKY_TEXTURE_NIGHT 0
#define RSID_SKY_TEXTURE_SUNRISE 1
#define RSID_SKY_TEXTURE_NOON 2
#define RSID_SKY_TEXTURE_SUNSET 3

#define RSID_BLADES 2
#define BLADE_STRUCT_FIELDS_COUNT 12
#define BLADE_STRUCT_DEGREE 0
#define BLADE_STRUCT_SIZE 1
#define BLADE_STRUCT_XPOS 2
#define BLADE_STRUCT_YPOS 3
#define BLADE_STRUCT_OFFSET 4
#define BLADE_STRUCT_SCALE 5
#define BLADE_STRUCT_LENGTHX 6
#define BLADE_STRUCT_LENGTHY 7
#define BLADE_STRUCT_HARDNESS 8
#define BLADE_STRUCT_H 9
#define BLADE_STRUCT_S 10
#define BLADE_STRUCT_B 11

#define MIDNIGHT 0.0f
#define MORNING 0.375f
#define AFTERNOON 0.6f
#define DUSK 0.8f

#define SECONDS_IN_DAY 24.0f * 3600.0f

#define PI 3.1415926f

#define REAL_TIME 0

float time(int frameCount) {
    if (REAL_TIME) {
        return (hour() * 3600.0f + minute() * 60.0f + second()) / SECONDS_IN_DAY;
    }
    return (frameCount % 180) / 180.0f;
}

void alpha(float a) {
    color(1.0f, 1.0f, 1.0f, a);
}

void drawNight() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_NIGHT));
    // NOTE: Hacky way to draw the night sky
    drawRect(WVGA_PORTRAIT_WIDTH - 512.0f, -32.0f, WVGA_PORTRAIT_WIDTH, 1024.0f - 32.0f, 0.0f);
}

void drawSunrise() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_SUNRISE));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

void drawNoon() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_NOON));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

void drawSunset() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_SUNSET));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

void drawBlade(int index, float now) {
    float offset = loadF(RSID_BLADES, index + BLADE_STRUCT_OFFSET);
    float scale = loadF(RSID_BLADES, index + BLADE_STRUCT_SCALE);
    float degree = loadF(RSID_BLADES, index + BLADE_STRUCT_DEGREE);
    float hardness = loadF(RSID_BLADES, index + BLADE_STRUCT_HARDNESS);
    
    float xpos = loadF(RSID_BLADES, index + BLADE_STRUCT_XPOS);
    float ypos = loadF(RSID_BLADES, index + BLADE_STRUCT_YPOS);

    float lengthX = loadF(RSID_BLADES, index + BLADE_STRUCT_LENGTHX);
    float lengthY = loadF(RSID_BLADES, index + BLADE_STRUCT_LENGTHY);

    int size = loadF(RSID_BLADES, index + BLADE_STRUCT_SIZE);

    float h = loadF(RSID_BLADES, index + BLADE_STRUCT_H);
    float s = loadF(RSID_BLADES, index + BLADE_STRUCT_S);
    float b = loadF(RSID_BLADES, index + BLADE_STRUCT_B);

    float newB = 1.0f;
    if (now >= MIDNIGHT && now < MORNING) {
        newB = now / MORNING;
    }

    if (now >= AFTERNOON && now < DUSK) {
        newB = 1.0f - normf(AFTERNOON, DUSK, now);
    }

    if (now >= DUSK) {
        newB = 0.0f;
    }

    hsb(h, s, lerpf(0, b, newB), 1.0f);

    float targetDegree = 0.0f; // TODO Compute
    degree += (targetDegree - degree) * 0.3f;

    float angle = PI / 2.0f;
    
    float currentX = xpos;
    float currentY = ypos;
    
    int i = size;

    for ( ; i > 0; i--) {
        float nextX = currentX - cosf(angle) * size * lengthX;
        float nextY = currentY - sinf(angle) * size * lengthY;
        angle += degree * hardness;

        drawQuad(nextX + (i - 1) * scale, nextY, 0.0f,
                 nextX - (i - 1) * scale, nextY, 0.0f,
                 currentX - i * scale, currentY + 0.7f, 0.0f,
                 currentX + i * scale, currentY + 0.7f, 0.0f);

        currentX = nextX;
        currentY = nextY;
    }
    
    storeF(RSID_BLADES, index + BLADE_STRUCT_DEGREE, degree);
}

void drawBlades(float now) {
    bindTexture(NAMED_PFBackground, 0, 0);    

    int bladesCount = loadI32(RSID_STATE, RSID_BLADES_COUNT);
    int count = bladesCount * BLADE_STRUCT_FIELDS_COUNT;

    int i = 0;
    for ( ; i < count; i += BLADE_STRUCT_FIELDS_COUNT) {
        drawBlade(i, now);
    }
}

int main(int launchID) {
    int frameCount = loadI32(RSID_STATE, RSID_FRAME_COUNT);
    float now = time(frameCount);
    alpha(1.0f);

    if (now >= MIDNIGHT && now < MORNING) {
        drawNight();
        alpha(normf(MIDNIGHT, MORNING, now));
        drawSunrise();
    }
    
    if (now >= MORNING && now < AFTERNOON) {
        drawSunrise();
        alpha(normf(MORNING, AFTERNOON, now));
        drawNoon();
    }

    if (now >= AFTERNOON && now < DUSK) {
        drawNoon();
        alpha(normf(AFTERNOON, DUSK, now));
        drawSunset();
    }
    
    if (now >= DUSK) {
        drawNight();
        alpha(1.0f - normf(DUSK, 1.0f, now));
        drawSunset();
    }
    
    drawBlades(now);

    frameCount++;
    storeI32(RSID_STATE, RSID_FRAME_COUNT, frameCount);

    return 1;
}
