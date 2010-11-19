#pragma version(1)
#pragma rs java_package_name(com.android.balls)

#include "balls.rsh"

float2 gGravityVector = {0.f, 9.8f};

float2 gMinPos = {0.f, 0.f};
float2 gMaxPos = {1280.f, 700.f};

float touchX;
float touchY;
float touchPressure = 0.f;

void setGamma(float g) {
}


void root(const Ball_t *ballIn, Ball_t *ballOut, const BallControl_t *ctl, uint32_t x) {
    float2 fv = {0, 0};
    float2 pos = ballIn->position;
    //rsDebug("physics pos in", pos);

    int arcID = -1;
    float arcInvStr = 100000;

    const Ball_t * bPtr = rsGetElementAt(ctl->ain, 0);
    for (uint32_t xin = 0; xin < ctl->dimX; xin++) {
        float2 vec = bPtr[xin].position - pos;
        float2 vec2 = vec * vec;
        float len2 = vec2.x + vec2.y;

        if (len2 < 1000) {
            if (len2 > (4*4)) {
                // Repulsion
                float len = sqrt(len2);
                if (len < arcInvStr) {
                    arcInvStr = len;
                    arcID = xin;
                }
                fv -= (vec / (len * len * len)) * 20000.f;
            } else {
                if (len2 < 0.1) {
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

    fv -= gGravityVector;
    fv *= ctl->dt;

    {
        float2 tp = {touchX, touchY};
        float2 vec = tp - ballIn->position;
        float2 vec2 = vec * vec;
        float len2 = vec2.x + vec2.y;

        if (len2 > 0.2) {
            float len = sqrt(len2);
            fv -= (vec / (len * len)) * touchPressure * 1000.f;
        }
    }

    ballOut->delta = ballIn->delta * 0.998f;
    ballOut->position = ballIn->position;

    ballOut->delta += fv;
    ballOut->position += ballOut->delta * ctl->dt;

    if (ballOut->position.x > gMaxPos.x) {
        if (ballOut->delta.x > 0) {
            ballOut->delta.x *= -0.7;
        }
        ballOut->position.x = gMaxPos.x;
    }
    if (ballOut->position.y > gMaxPos.y) {
        if (ballOut->delta.y > 0) {
            ballOut->delta.y *= -0.7;
        }
        ballOut->position.y = gMaxPos.y - 1.f;
    }
    if (ballOut->position.x < gMinPos.x) {
        if (ballOut->delta.x < 0) {
            ballOut->delta.x *= -0.7;
        }
        ballOut->position.x = gMinPos.x + 1.f;
    }
    if (ballOut->position.y < gMinPos.y) {
        if (ballOut->delta.y < 0) {
            ballOut->delta.y *= -0.7;
        }
        ballOut->position.y = gMinPos.y + 1.f;
    }

    ballOut->color.b = 1.f;
    ballOut->color.r = min(sqrt(length(ballOut->delta)) * 0.1f, 1.f);
    ballOut->color.g = min(sqrt(length(fv) * 0.1f), 1.f);
    ballOut->arcID = arcID;
    ballOut->arcStr = 8 / arcInvStr;

    //rsDebug("physics pos out", ballOut->position);
}

