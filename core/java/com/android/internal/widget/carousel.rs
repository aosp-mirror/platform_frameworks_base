/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma version(1)
#pragma rs java_package_name(com.android.internal.widget)
#pragma rs set_reflect_license()

#include "rs_graphics.rsh"

typedef struct __attribute__((aligned(4))) Card {
    rs_allocation texture;
    rs_mesh geometry;
    //rs_matrix4x4 matrix; // custom transform for this card/geometry
    int textureState;  // whether or not the texture is loaded.
    int geometryState; // whether or not geometry is loaded
    int visible; // not bool because of packing bug?
} Card_t;

typedef struct Ray_s {
    float3 position;
    float3 direction;
} Ray;

typedef struct PerspectiveCamera_s {
    float3 from;
    float3 at;
    float3 up;
    float  fov;
    float  aspect;
    float  near;
    float  far;
} PerspectiveCamera;

// Request states. Used for loading 3D object properties from the Java client.
// Typical properties: texture, geometry and matrices.
enum {
    STATE_INVALID = 0, // item hasn't been loaded
    STATE_LOADING, // we've requested an item but are waiting for it to load
    STATE_LOADED // item was delivered
};

// Client messages *** THIS LIST MUST MATCH THOSE IN CarouselRS.java. ***
static const int CMD_CARD_SELECTED = 100;
static const int CMD_REQUEST_TEXTURE = 200;
static const int CMD_INVALIDATE_TEXTURE = 210;
static const int CMD_REQUEST_GEOMETRY = 300;
static const int CMD_INVALIDATE_GEOMETRY = 310;
static const int CMD_ANIMATION_STARTED = 400;
static const int CMD_ANIMATION_FINISHED = 500;
static const int CMD_PING = 600;

// Constants
static const int ANIMATION_SCALE_TIME = 200; // Time it takes to animate selected card, in ms
static const float3 SELECTED_SCALE_FACTOR = { 0.2f, 0.2f, 0.2f }; // increase by this %

// Debug flags
bool debugCamera = false; // dumps ray/camera coordinate stuff
bool debugPicking = false; // renders picking area on top of geometry

// Exported variables. These will be reflected to Java set_* variables.
Card_t *cards; // array of cards to draw
float startAngle; // position of initial card, in radians
int slotCount; // number of positions where a card can be
int cardCount; // number of cards in stack
int visibleSlotCount; // number of visible slots (for culling)
float radius; // carousel radius. Cards will be centered on a circle with this radius
float cardRotation; // rotation of card in XY plane relative to Z=1
rs_program_store programStore;
rs_program_fragment fragmentProgram;
rs_program_vertex vertexProgram;
rs_program_raster rasterProgram;
rs_allocation defaultTexture; // shown when no other texture is assigned
rs_allocation loadingTexture; // progress texture (shown when app is fetching the texture)
rs_mesh defaultGeometry; // shown when no geometry is loaded
rs_mesh loadingGeometry; // shown when geometry is loading
rs_matrix4x4 projectionMatrix;
rs_matrix4x4 modelviewMatrix;

#pragma rs export_var(radius, cards, slotCount, visibleSlotCount, cardRotation)
#pragma rs export_var(programStore, fragmentProgram, vertexProgram, rasterProgram)
#pragma rs export_var(startAngle, defaultTexture, loadingTexture, defaultGeometry, loadingGeometry)
#pragma rs export_func(createCards, lookAt, doStart, doStop, doMotion, doSelection, setTexture)
#pragma rs export_func(setGeometry, debugCamera, debugPicking)

// Local variables
static float bias; // rotation bias, in radians. Used for animation and dragging.
static bool updateCamera;    // force a recompute of projection and lookat matrices
static bool initialized;
static float3 backgroundColor = { 0.0f, 0.0f, 0.0f };
static const float FLT_MAX = 1.0e37;
static int currentSelection = -1;
static int64_t touchTime = -1;  // time of first touch (see doStart())
static float touchBias = 0.0f; // bias on first touch

// Default geometry when card.geometry is not set.
static const float3 cardVertices[4] = {
        { -1.0, -1.0, 0.0 },
        { 1.0, -1.0, 0.0 },
        { 1.0, 1.0, 0.0 },
        {-1.0, 1.0, 0.0 }
};

// Default camera
static PerspectiveCamera camera = {
        {2,2,2}, // from
        {0,0,0}, // at
        {0,1,0}, // up
        25.0f,   // field of view
        1.0f,    // aspect
        0.1f,    // near
        100.0f   // far
};

// Forward references
static int intersectGeometry(Ray* ray, float *bestTime);
static bool makeRayForPixelAt(Ray* ray, float x, float y);
static float deltaTimeInSeconds(int64_t current);

void init() {
    // initializers currently have a problem when the variables are exported, so initialize
    // globals here.
    rsDebug("Renderscript: init()", 0);
    startAngle = 0.0f;
    slotCount = 10;
    visibleSlotCount = 1;
    bias = 0.0f;
    radius = 1.0f;
    cardRotation = 0.0f;
    updateCamera = true;
    initialized = false;
}

static void updateAllocationVars()
{
    // Cards
    rs_allocation cardAlloc = rsGetAllocation(cards);
    // TODO: use new rsIsObject()
    cardCount = cardAlloc.p != 0 ? rsAllocationGetDimX(cardAlloc) : 0;
}

void createCards(int n)
{
    rsDebug("CreateCards: ", n);
    initialized = false;
    updateAllocationVars();
}

// Return angle for position p. Typically p will be an integer position, but can be fractional.
static float cardPosition(float p)
{
    return startAngle + bias + 2.0f * M_PI * p / slotCount;
}

// Return slot for a card in position p. Typically p will be an integer slot, but can be fractional.
static float slotPosition(float p)
{
    return startAngle + 2.0f * M_PI * p / slotCount;
}

// Return the lowest slot number for a given angular position.
static int cardIndex(float angle)
{
    return floor(angle - startAngle - bias) * slotCount / (2.0f * M_PI);
}

// Set basic camera properties:
//    from - position of the camera in x,y,z
//    at - target we're looking at - used to compute view direction
//    up - a normalized vector indicating up (typically { 0, 1, 0})
//
// NOTE: the view direction and up vector cannot be parallel/antiparallel with each other
void lookAt(float fromX, float fromY, float fromZ,
        float atX, float atY, float atZ,
        float upX, float upY, float upZ)
{
    camera.from.x = fromX;
    camera.from.y = fromY;
    camera.from.z = fromZ;
    camera.at.x = atX;
    camera.at.y = atY;
    camera.at.z = atZ;
    camera.up.x = upX;
    camera.up.y = upY;
    camera.up.z = upZ;
    updateCamera = true;
}

// Load a projection matrix for the given parameters.  This is equivalent to gluPerspective()
static void loadPerspectiveMatrix(rs_matrix4x4* matrix, float fovy, float aspect, float near, float far)
{
    rsMatrixLoadIdentity(matrix);
    float top = near * tan((float) (fovy * M_PI / 360.0f));
    float bottom = -top;
    float left = bottom * aspect;
    float right = top * aspect;
    rsMatrixLoadFrustum(matrix, left, right, bottom, top, near, far);
}

// Construct a matrix based on eye point, center and up direction. Based on the
// man page for gluLookat(). Up must be normalized.
static void loadLookatMatrix(rs_matrix4x4* matrix, float3 eye, float3 center, float3 up)
{
    float3 f = normalize(center - eye);
    float3 s = normalize(cross(f, up));
    float3 u = cross(s, f);
    float m[16];
    m[0] = s.x;
    m[4] = s.y;
    m[8] = s.z;
    m[12] = 0.0f;
    m[1] = u.x;
    m[5] = u.y;
    m[9] = u.z;
    m[13] = 0.0f;
    m[2] = -f.x;
    m[6] = -f.y;
    m[10] = -f.z;
    m[14] = 0.0f;
    m[3] = m[7] = m[11] = 0.0f;
    m[15] = 1.0f;
    rsMatrixLoad(matrix, m);
    rsMatrixTranslate(matrix, -eye.x, -eye.y, -eye.z);
}

void setTexture(int n, rs_allocation texture)
{
    cards[n].texture = texture;
    if (cards[n].texture.p != 0)
        cards[n].textureState = STATE_LOADED;
    else
        cards[n].textureState = STATE_INVALID;
}

void setGeometry(int n, rs_mesh geometry)
{
    cards[n].geometry = geometry;
    if (cards[n].geometry.p != 0)
        cards[n].geometryState = STATE_LOADED;
    else
        cards[n].geometryState = STATE_INVALID;
}

static float3 getAnimatedScaleForSelected()
{
    int64_t dt = (rsUptimeMillis() - touchTime);
    float fraction = (dt < ANIMATION_SCALE_TIME) ? (float) dt / ANIMATION_SCALE_TIME : 1.0f;
    const float3 one = { 1.0f, 1.0f, 1.0f };
    return one + fraction * SELECTED_SCALE_FACTOR;
}

static void getMatrixForCard(rs_matrix4x4* matrix, int i)
{
    float theta = cardPosition(i);
    rsMatrixRotate(matrix, degrees(theta), 0, 1, 0);
    rsMatrixTranslate(matrix, radius, 0, 0);
    rsMatrixRotate(matrix, degrees(-theta + cardRotation), 0, 1, 0);
    if (i == currentSelection) {
        float3 scale = getAnimatedScaleForSelected();
        rsMatrixScale(matrix, scale.x, scale.y, scale.z);
    }
    // TODO: apply custom matrix for cards[i].geometry
}

static void drawCards()
{
    float depth = 1.0f;
    for (int i = 0; i < cardCount; i++) {
        if (cards[i].visible) {
            // Bind texture
            if (cards[i].textureState == STATE_LOADED) {
                rsgBindTexture(fragmentProgram, 0, cards[i].texture);
            } else if (cards[i].textureState == STATE_LOADING) {
                rsgBindTexture(fragmentProgram, 0, loadingTexture);
            } else {
                rsgBindTexture(fragmentProgram, 0, defaultTexture);
            }

            // Draw geometry
            rs_matrix4x4 matrix = modelviewMatrix;
            getMatrixForCard(&matrix, i);
            rsgProgramVertexLoadModelMatrix(&matrix);
            if (cards[i].geometryState == STATE_LOADED && cards[i].geometry.p != 0) {
                rsgDrawMesh(cards[i].geometry);
            } else if (cards[i].geometryState == STATE_LOADING && loadingGeometry.p != 0) {
                rsgDrawMesh(loadingGeometry);
            } else if (defaultGeometry.p != 0) {
                rsgDrawMesh(defaultGeometry);
            } else {
                // Draw place-holder geometry
                rsgDrawQuad(
                    cardVertices[0].x, cardVertices[0].y, cardVertices[0].z,
                    cardVertices[1].x, cardVertices[1].y, cardVertices[1].z,
                    cardVertices[2].x, cardVertices[2].y, cardVertices[2].z,
                    cardVertices[3].x, cardVertices[3].y, cardVertices[3].z);
            }
        }
    }
}

static void updateCameraMatrix(float width, float height)
{
    float aspect = width / height;
    if (aspect != camera.aspect || updateCamera) {
        camera.aspect = aspect;
        loadPerspectiveMatrix(&projectionMatrix, camera.fov, camera.aspect, camera.near, camera.far);
        rsgProgramVertexLoadProjectionMatrix(&projectionMatrix);

        loadLookatMatrix(&modelviewMatrix, camera.from, camera.at, camera.up);
        rsgProgramVertexLoadModelMatrix(&modelviewMatrix);
        updateCamera = false;
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Behavior/Physics
////////////////////////////////////////////////////////////////////////////////////////////////////
static float velocity = 0.0f;  // angular velocity in radians/s
static bool isDragging;
static int64_t lastTime = 0L; // keep track of how much time has passed between frames
static float2 lastPosition;
static bool animating = false;
static float velocityThreshold = 0.1f * M_PI / 180.0f;
static float velocityTracker;
static int velocityTrackerCount;
static float mass = 5.0f; // kg

static const float G = 9.80f; // gravity constant, in m/s
static const float springConstant = 0.0f;
static const float frictionCoeff = 10.0f;
static const float dragFactor = 0.25f;

static float dragFunction(float x, float y)
{
    return dragFactor * ((x - lastPosition.x) / rsgGetWidth()) * M_PI;
}

static float deltaTimeInSeconds(int64_t current)
{
    return (lastTime > 0L) ? (float) (current - lastTime) / 1000.0f : 0.0f;
}

int doSelection(float x, float y)
{
    Ray ray;
    if (makeRayForPixelAt(&ray, x, y)) {
        float bestTime = FLT_MAX;
        return intersectGeometry(&ray, &bestTime);
    }
    return -1;
}

void doStart(float x, float y)
{
    lastPosition.x = x;
    lastPosition.y = y;
    velocity = 0.0f;
    if (animating) {
        rsSendToClient(CMD_ANIMATION_FINISHED);
        animating = false;
    }
    velocityTracker = 0.0f;
    velocityTrackerCount = 0;
    touchTime = rsUptimeMillis();
    touchBias = bias;
    currentSelection = doSelection(x, y);
}


void doStop(float x, float y)
{
    int64_t currentTime = rsUptimeMillis();
    updateAllocationVars();
    if (currentSelection != -1 && (currentTime - touchTime) < ANIMATION_SCALE_TIME) {
        rsDebug("HIT!", currentSelection);
        int data[1];
        data[0] = currentSelection;
        rsSendToClientBlocking(CMD_CARD_SELECTED, data, sizeof(data));
    } else {
        velocity = velocityTrackerCount > 0 ?
                    (velocityTracker / velocityTrackerCount) : 0.0f;  // avg velocity
        if (fabs(velocity) > velocityThreshold) {
            animating = true;
            rsSendToClient(CMD_ANIMATION_STARTED);
        }
    }
    currentSelection = -1;
    lastTime = rsUptimeMillis();
}

void doMotion(float x, float y)
{
    int64_t currentTime = rsUptimeMillis();
    float deltaOmega = dragFunction(x, y);
    bias += deltaOmega;
    lastPosition.x = x;
    lastPosition.y = y;
    float dt = deltaTimeInSeconds(currentTime);
    if (dt > 0.0f) {
        float v = deltaOmega / dt;
        //if ((velocityTracker > 0.0f) == (v > 0.0f)) {
            velocityTracker += v;
            velocityTrackerCount++;
        //} else {
        //    velocityTracker = v;
        //    velocityTrackerCount = 1;
        //}
    }

    // Drop current selection if user drags position +- a partial slot
    if (currentSelection != -1) {
        const float slotMargin = 0.5f * (2.0f * M_PI / slotCount);
        if (fabs(touchBias - bias) > slotMargin) {
            currentSelection = -1;
        }
    }
    lastTime = currentTime;
}

////////////////////////////////////////////////////////////////////////////////////////////////////
// Hit detection using ray casting.
////////////////////////////////////////////////////////////////////////////////////////////////////

static bool
rayTriangleIntersect(Ray* ray, float3 p0, float3 p1, float3 p2, float *tout)
{
    static const float tmin = 0.0f;

    float3 e1 = p1 - p0;
    float3 e2 = p2 - p0;
    float3 s1 = cross(ray->direction, e2);

    float div = dot(s1, e1);
    if (div == 0.0f) return false;  // ray is parallel to plane.

    float3 d = ray->position - p0;
    float invDiv = 1.0f / div;

    float u = dot(d, s1) * invDiv;
    if (u < 0.0f || u > 1.0f) return false;

    float3 s2 = cross(d, e1);
    float v = dot(ray->direction, s2) * invDiv;
    if ( v < 0.0f || (u+v) > 1.0f) return false;

    float t = dot(e2, s2) * invDiv;
    if (t < tmin || t > *tout)
        return false;
    *tout = t;
    return true;
}

// Creates a ray for an Android pixel coordinate.
// Note that the Y coordinate is opposite of GL rendering coordinates.
static bool makeRayForPixelAt(Ray* ray, float x, float y)
{
    if (debugCamera) {
        rsDebug("------ makeRay() -------", 0);
        rsDebug("Camera.from:", camera.from);
        rsDebug("Camera.at:", camera.at);
        rsDebug("Camera.dir:", normalize(camera.at - camera.from));
    }

    // Vector math.  This has the potential to be much faster.
    // TODO: pre-compute lowerLeftRay, du, dv to eliminate most of this math.
    if (true) {
        const float u = x / rsgGetWidth();
        const float v = 1.0f - (y / rsgGetHeight());
        const float aspect = (float) rsgGetWidth() / rsgGetHeight();
        const float tanfov2 = 2.0f * tan(radians(camera.fov / 2.0f));
        float3 dir = normalize(camera.at - camera.from);
        float3 du = tanfov2 * normalize(cross(dir, camera.up));
        float3 dv = tanfov2 * normalize(cross(du, dir));
        du *= aspect;
        float3 lowerLeftRay = dir - (0.5f * du) - (0.5f * dv);
        const float3 rayPoint = camera.from;
        const float3 rayDir = normalize(lowerLeftRay + u*du + v*dv);
        if (debugCamera) {
            rsDebug("Ray direction (vector math) = ", rayDir);
        }

        ray->position =  rayPoint;
        ray->direction = rayDir;
    }

    // Matrix math.  This is more generic if we allow setting model view and projection matrices
    // directly
    else {
        rs_matrix4x4 pm = modelviewMatrix;
        rsMatrixLoadMultiply(&pm, &projectionMatrix, &modelviewMatrix);
        if (!rsMatrixInverse(&pm)) {
            rsDebug("ERROR: SINGULAR PM MATRIX", 0);
            return false;
        }
        const float width = rsgGetWidth();
        const float height = rsgGetHeight();
        const float winx = 2.0f * x / width - 1.0f;
        const float winy = 2.0f * y / height - 1.0f;

        float4 eye = { 0.0f, 0.0f, 0.0f, 1.0f };
        float4 at = { winx, winy, 1.0f, 1.0f };

        eye = rsMatrixMultiply(&pm, eye);
        eye *= 1.0f / eye.w;

        at = rsMatrixMultiply(&pm, at);
        at *= 1.0f / at.w;

        const float3 rayPoint = { eye.x, eye.y, eye.z };
        const float3 atPoint = { at.x, at.y, at.z };
        const float3 rayDir = normalize(atPoint - rayPoint);
        if (debugCamera) {
            rsDebug("winx: ", winx);
            rsDebug("winy: ", winy);
            rsDebug("Ray position (transformed) = ", eye);
            rsDebug("Ray direction (transformed) = ", rayDir);
        }
        ray->position =  rayPoint;
        ray->direction = rayDir;
    }

    return true;
}

static int intersectGeometry(Ray* ray, float *bestTime)
{
    int hit = -1;
    for (int id = 0; id < cardCount; id++) {
        if (cards[id].visible) {
            rs_matrix4x4 matrix;
            float3 p[4];

            // Transform card vertices to world space
            rsMatrixLoadIdentity(&matrix);
            getMatrixForCard(&matrix, id);
            for (int vertex = 0; vertex < 4; vertex++) {
                float4 tmp = rsMatrixMultiply(&matrix, cardVertices[vertex]);
                if (tmp.w != 0.0f) {
                    p[vertex].x = tmp.x;
                    p[vertex].y = tmp.y;
                    p[vertex].z = tmp.z;
                    p[vertex] *= 1.0f / tmp.w;
                } else {
                    rsDebug("Bad w coord: ", tmp);
                }
            }

            // Intersect card geometry
            if (rayTriangleIntersect(ray, p[0], p[1], p[2], bestTime)
                || rayTriangleIntersect(ray, p[2], p[3], p[0], bestTime)) {
                hit = id;
            }
        }
    }
    return hit;
}

// This method computes the position of all the cards by updating bias based on a
// simple physics model.
// If the cards are still in motion, returns true.
static bool updateNextPosition(int64_t currentTime)
{
    if (animating) {
        float dt = deltaTimeInSeconds(currentTime);
        if (dt <= 0.0f)
            return animating;
        const float minStepTime = 1.0f / 300.0f; // ~5 steps per frame
        const int N = (dt > minStepTime) ? (1 + round(dt / minStepTime)) : 1;
        dt /= N;
        for (int i = 0; i < N; i++) {
            // Force friction - always opposes motion
            const float Ff = -frictionCoeff * velocity;

            // Restoring force to match cards with slots
            const float theta = startAngle + bias;
            const float dtheta = 2.0f * M_PI / slotCount;
            const float position = theta / dtheta;
            const float fraction = position - floor(position); // fractional position between slots
            float x;
            if (fraction > 0.5f) {
                x = - (1.0f - fraction);
            } else {
                x = fraction;
            }
            const float Fr = - springConstant * x;

            // compute velocity
            const float momentum = mass * velocity + (Ff + Fr)*dt;
            velocity = momentum / mass;
            bias += velocity * dt;
        }

        // TODO: Add animation to smoothly move back to slots. Currently snaps to location.
        if (cardCount <= visibleSlotCount) {
            // TODO: this aligns the cards to the first slot (theta = startAngle) when there aren't
            // enough visible cards. It should be generalized to allow alignment to front,
            // middle or back of the stack.
            if (cardPosition(0) != slotPosition(0)) {
                bias = 0.0f;
            }
        } else {
            if (cardPosition(cardCount) < 0.0f) {
                bias = -slotPosition(cardCount);
            } else if (cardPosition(0) > slotPosition(0)) {
                bias = 0.0f;
            }
        }

        animating = fabs(velocity) > velocityThreshold;
        if (!animating) {
            const float dtheta = 2.0f * M_PI / slotCount;
            bias = round((startAngle + bias) / dtheta) * dtheta - startAngle;
            rsSendToClient(CMD_ANIMATION_FINISHED);
        }
    }
    lastTime = currentTime;

    return animating;
}

// Cull cards based on visibility and visibleSlotCount.
// If visibleSlotCount is > 0, then only show those slots and cull the rest.
// Otherwise, it should cull based on bounds of geometry.
static int cullCards()
{
    const float thetaFirst = slotPosition(-1); // -1 keeps the card in front around a bit longer
    const float thetaLast = slotPosition(visibleSlotCount);

    int count = 0;
    for (int i = 0; i < cardCount; i++) {
        if (visibleSlotCount > 0) {
            // If visibleSlotCount is specified, then only show up to visibleSlotCount cards.
            float p = cardPosition(i);
            if (p >= thetaFirst && p < thetaLast) {
                cards[i].visible = true;
                count++;
            } else {
                cards[i].visible = false;
            }
        } else {
            // Cull the rest of the cards using bounding box of geometry.
            // TODO
            cards[i].visible = true;
            count++;
        }
    }
    return count;
}

// Request texture/geometry for items that have come into view
// or doesn't have a texture yet.
static void updateCardResources()
{
    for (int i = 0; i < cardCount; i++) {
        int data[1];
        if (cards[i].visible) {
            // request texture from client if not loaded
            if (cards[i].textureState == STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_REQUEST_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].textureState = STATE_LOADING;
                } else {
                    rsDebug("Couldn't send CMD_REQUEST_TEXTURE", 0);
                }
            }
            // request geometry from client if not loaded
            if (cards[i].geometryState == STATE_INVALID) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_REQUEST_GEOMETRY, data, sizeof(data));
                if (enqueued) {
                    cards[i].geometryState = STATE_LOADING;
                } else {
                    rsDebug("Couldn't send CMD_REQUEST_GEOMETRY", 0);
                }
            }
        } else {
            // ask the host to remove the texture
            if (cards[i].textureState == STATE_LOADED) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_INVALIDATE_TEXTURE, data, sizeof(data));
                if (enqueued) {
                    cards[i].textureState = STATE_INVALID;
                } else {
                    rsDebug("Couldn't send CMD_INVALIDATE_TEXTURE", 0);
                }
            }
            // ask the host to remove the geometry
            if (cards[i].geometryState == STATE_LOADED) {
                data[0] = i;
                bool enqueued = rsSendToClient(CMD_INVALIDATE_GEOMETRY, data, sizeof(data));
                if (enqueued) {
                    cards[i].geometryState = STATE_INVALID;
                } else {
                    rsDebug("Couldn't send CMD_INVALIDATE_GEOMETRY", 0);
                }
            }

        }
    }
}

// Places dots on geometry to visually inspect that objects can be seen by rays.
// NOTE: the color of the dot is somewhat random, as it depends on texture of previously-rendered
// card.
static void renderWithRays()
{
    const float w = rsgGetWidth();
    const float h = rsgGetHeight();
    const int skip = 8;
    color(1.0f, 0.0f, 0.0f, 1.0f);
    for (int j = 0; j < (int) h; j+=skip) {
        float posY = (float) j;
        for (int i = 0; i < (int) w; i+=skip) {
            float posX = (float) i;
            Ray ray;
            if (makeRayForPixelAt(&ray, posX, posY)) {
                float bestTime = FLT_MAX;
                if (intersectGeometry(&ray, &bestTime) != -1) {
                    rsgDrawSpriteScreenspace(posX, h - posY - 1, 0.0f, 2.0f, 2.0f);
                }
            }
        }
    }
}

int root() {
    int64_t currentTime = rsUptimeMillis();

    rsgClearDepth(1.0f);
    rsgBindProgramVertex(vertexProgram);
    rsgBindProgramFragment(fragmentProgram);
    rsgBindProgramStore(programStore);
    rsgBindProgramRaster(rasterProgram);

    updateAllocationVars();

    if (!initialized) {
        for (int i = 0; i < cardCount; i++)
            cards[i].textureState = STATE_INVALID;
        initialized = true;
    }

    if (false) { // for debugging - flash the screen so we know we're still rendering
        static bool toggle;
        if (toggle)
            rsgClearColor(backgroundColor.x, backgroundColor.y, backgroundColor.z, 1.0);
        else
            rsgClearColor(1.0f, 0.0f, 0.0f, 1.f);
        toggle = !toggle;
    } else {
        rsgClearColor(backgroundColor.x, backgroundColor.y, backgroundColor.z, 1.0);
    }

    updateCameraMatrix(rsgGetWidth(), rsgGetHeight());

    const bool timeExpired = (currentTime - touchTime) > ANIMATION_SCALE_TIME;
    if (timeExpired) {
        //currentSelection = -1;
    }
    bool stillAnimating = updateNextPosition(currentTime) || !timeExpired;

    cullCards();

    updateCardResources();

    drawCards();

    if (debugPicking) {
        renderWithRays();
    }

    //rsSendToClient(CMD_PING);

    return stillAnimating ? 1 : 0;
}
