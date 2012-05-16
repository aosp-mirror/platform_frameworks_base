#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.balls)

#include "balls.rsh"

float2 gGravityVector = {0.f, 9.8f};

float2 gMinPos = {0.f, 0.f};
float2 gMaxPos = {1280.f, 700.f};

static float2 touchPos[10];
static float touchPressure[10];

void touch(float x, float y, float pressure, int id) {
    if (id >= 10) {
        return;
    }

    touchPos[id].x = x;
    touchPos[id].y = y;
    touchPressure[id] = pressure;
}

void root(const Ball_t *ballIn, Ball_t *ballOut, const BallControl_t *ctl, uint32_t x) {
    float2 fv = {0, 0};
    float2 pos = ballIn->position;

    int arcID = -1;
    float arcInvStr = 100000;

    const Ball_t * bPtr = rsGetElementAt(ctl->ain, 0);
    for (uint32_t xin = 0; xin < ctl->dimX; xin++) {
        float2 vec = bPtr[xin].position - pos;
        float2 vec2 = vec * vec;
        float len2 = vec2.x + vec2.y;

        if (len2 < 10000) {
            //float minDist = ballIn->size + bPtr[xin].size;
            float forceScale = ballIn->size * bPtr[xin].size;
            forceScale *= forceScale;

            if (len2 > 16 /* (minDist*minDist)*/)  {
                // Repulsion
                float len = sqrt(len2);
                fv -= (vec / (len * len * len)) * 20000.f * forceScale;
            } else {
                if (len2 < 1) {
                    if (xin == x) {
                        continue;
                    }
                    ballOut->delta = 0.f;
                    ballOut->position = ballIn->position;
                    if (xin > x) {
                        ballOut->position.x += 1.f;
                    } else {
                        ballOut->position.x -= 1.f;
                    }
                    //ballOut->color.rgb = 1.f;
                    //ballOut->arcID = -1;
                    //ballOut->arcStr = 0;
                    continue;
                }
                // Collision
                float2 axis = normalize(vec);
                float e1 = dot(axis, ballIn->delta);
                float e2 = dot(axis, bPtr[xin].delta);
                float e = (e1 - e2) * 0.45f;
                if (e1 > 0) {
                    fv -= axis * e;
                } else {
                    fv += axis * e;
                }
            }
        }
    }

    fv /= ballIn->size * ballIn->size * ballIn->size;
    fv -= gGravityVector * 4.f;
    fv *= ctl->dt;

    for (int i=0; i < 10; i++) {
        if (touchPressure[i] > 0.1f) {
            float2 vec = touchPos[i] - ballIn->position;
            float2 vec2 = vec * vec;
            float len2 = max(2.f, vec2.x + vec2.y);
            fv -= (vec / len2) * touchPressure[i] * 300.f;
        }
    }

    ballOut->delta = (ballIn->delta * (1.f - 0.004f)) + fv;
    ballOut->position = ballIn->position + (ballOut->delta * ctl->dt);

    const float wallForce = 400.f;
    if (ballOut->position.x > (gMaxPos.x - 20.f)) {
        float d = gMaxPos.x - ballOut->position.x;
        if (d < 0.f) {
            if (ballOut->delta.x > 0) {
                ballOut->delta.x *= -0.7f;
            }
            ballOut->position.x = gMaxPos.x;
        } else {
            ballOut->delta.x -= min(wallForce / (d * d), 10.f);
        }
    }

    if (ballOut->position.x < (gMinPos.x + 20.f)) {
        float d = ballOut->position.x - gMinPos.x;
        if (d < 0.f) {
            if (ballOut->delta.x < 0) {
                ballOut->delta.x *= -0.7f;
            }
            ballOut->position.x = gMinPos.x + 1.f;
        } else {
            ballOut->delta.x += min(wallForce / (d * d), 10.f);
        }
    }

    if (ballOut->position.y > (gMaxPos.y - 20.f)) {
        float d = gMaxPos.y - ballOut->position.y;
        if (d < 0.f) {
            if (ballOut->delta.y > 0) {
                ballOut->delta.y *= -0.7f;
            }
            ballOut->position.y = gMaxPos.y;
        } else {
            ballOut->delta.y -= min(wallForce / (d * d), 10.f);
        }
    }

    if (ballOut->position.y < (gMinPos.y + 20.f)) {
        float d = ballOut->position.y - gMinPos.y;
        if (d < 0.f) {
            if (ballOut->delta.y < 0) {
                ballOut->delta.y *= -0.7f;
            }
            ballOut->position.y = gMinPos.y + 1.f;
        } else {
            ballOut->delta.y += min(wallForce / (d * d * d), 10.f);
        }
    }

    ballOut->size = ballIn->size;

    //rsDebug("physics pos out", ballOut->position);
}

