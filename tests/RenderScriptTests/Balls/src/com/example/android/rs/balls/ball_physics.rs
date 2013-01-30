#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.balls)

#include "balls.rsh"

float2 gGravityVector = {0.f, 9.8f};

float2 gMinPos = {0.f, 0.f};
float2 gMaxPos = {1280.f, 700.f};

static float2 touchPos[10];
static float touchPressure[10];
static const float gDT = 1.f / 30.f;

rs_allocation gGrid;
rs_allocation gGridCache;
rs_allocation gBalls;

float gScale = 1.f;

void touch(float x, float y, float pressure, int id) {
    if (id >= 10) {
        return;
    }

    touchPos[id].x = x;
    touchPos[id].y = y;
    touchPressure[id] = pressure;
}

void root(Ball_t *ball, uint32_t x) {
    float2 fv = 0;
    float pressure = 0;
    float2 pos = ball->position;
    int2 gridPos[9];

    gridPos[0] = convert_int2((ball->position / 100.f) /*- 0.4999f*/);
    gridPos[1] = (int2){gridPos[0].x - 1, gridPos[0].y - 1};
    gridPos[2] = (int2){gridPos[0].x + 0, gridPos[0].y - 1};
    gridPos[3] = (int2){gridPos[0].x + 1, gridPos[0].y - 1};
    gridPos[4] = (int2){gridPos[0].x - 1, gridPos[0].y};
    gridPos[5] = (int2){gridPos[0].x + 1, gridPos[0].y};
    gridPos[6] = (int2){gridPos[0].x - 1, gridPos[0].y + 1};
    gridPos[7] = (int2){gridPos[0].x + 0, gridPos[0].y + 1};
    gridPos[8] = (int2){gridPos[0].x + 1, gridPos[0].y + 1};

    for (int gct=0; gct < 9; gct++) {
        if ((gridPos[gct].x >= rsAllocationGetDimX(gGrid)) ||
            (gridPos[gct].x < 0) ||
            (gridPos[gct].y >= rsAllocationGetDimY(gGrid)) ||
            (gridPos[gct].y < 0)) {
            continue;
        }
        //rsDebug("grid ", gridPos[gct]);
        const BallGrid_t *bg = (const BallGrid_t *)rsGetElementAt(gGrid, gridPos[gct].x, gridPos[gct].y);

        for (int cidx = 0; cidx < bg->count; cidx++) {
            float2 bcptr = rsGetElementAt_float2(gGridCache, bg->cacheIdx + cidx);
            float2 vec = bcptr - pos;
            float2 vec2 = vec * vec;
            float len2 = vec2.x + vec2.y;

            if ((len2 < 10000.f) && (len2 > 0.f)) {
                float t = native_powr(len2, 1.5f) + 16.0f;
                float2 pfv = (vec / t) * 16000.f;
                pressure += length(pfv);
                fv -= pfv;
            }
        }
    }

    //fv /= ball->size * ball->size * ball->size;
    fv -= gGravityVector * 4.f * gScale;
    fv *= gDT;

    for (int i=0; i < 10; i++) {
        if (touchPressure[i] > 0.1f) {
            float2 vec = touchPos[i] - ball->position;
            float2 vec2 = vec * vec;
            float len2 = max(2.f, vec2.x + vec2.y);
            float2 pfv = (vec / len2) * touchPressure[i] * 500.f * gScale;
            pressure += length(pfv);
            fv -= pfv;
        }
    }

    ball->delta = (ball->delta * (1.f - 0.008f)) + fv;
    ball->position = ball->position + (ball->delta * gDT);

    const float wallForce = 400.f * gScale;
    if (ball->position.x > (gMaxPos.x - 20.f)) {
        float d = gMaxPos.x - ball->position.x;
        if (d < 0.f) {
            if (ball->delta.x > 0) {
                ball->delta.x *= -0.7f;
            }
            ball->position.x = gMaxPos.x - 1.f;
        } else {
            ball->delta.x -= min(wallForce / (d * d), 10.f);
        }
    }

    if (ball->position.x < (gMinPos.x + 20.f)) {
        float d = ball->position.x - gMinPos.x;
        if (d < 0.f) {
            if (ball->delta.x < 0) {
                ball->delta.x *= -0.7f;
            }
            ball->position.x = gMinPos.x + 1.f;
        } else {
            ball->delta.x += min(wallForce / (d * d), 10.f);
        }
    }

    if (ball->position.y > (gMaxPos.y - 20.f)) {
        float d = gMaxPos.y - ball->position.y;
        if (d < 0.f) {
            if (ball->delta.y > 0) {
                ball->delta.y *= -0.7f;
            }
            ball->position.y = gMaxPos.y - 1.f;
        } else {
            ball->delta.y -= min(wallForce / (d * d), 10.f);
        }
    }

    if (ball->position.y < (gMinPos.y + 20.f)) {
        float d = ball->position.y - gMinPos.y;
        if (d < 0.f) {
            if (ball->delta.y < 0) {
                ball->delta.y *= -0.7f;
            }
            ball->position.y = gMinPos.y + 1.f;
        } else {
            ball->delta.y += min(wallForce / (d * d * d), 10.f);
        }
    }

    // low pressure ~500, high ~2500
    pressure = max(pressure - 400.f, 0.f);
    ball->pressure = pressure;

    //rsDebug("p ", pressure);

    float4 color = 1.f;
    color.r = pow(pressure, 0.25f) / 12.f;
    color.b = 1.f - color.r;
    color.g = sin(pressure / 1500.f * 3.14f);
    color.rgb = max(color.rgb, (float3)0);
    color.rgb = normalize(color.rgb);
    ball->color = rsPackColorTo8888(color);

    //rsDebug("physics pos out", ball->position);
}

