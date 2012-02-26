/*
 * Copyright (C) 2006 The Android Open Source Project
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

#ifndef ANDROID_OPENGLES_CONTEXT_H
#define ANDROID_OPENGLES_CONTEXT_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>
#include <pthread.h>
#ifdef HAVE_ANDROID_OS
#include <bionic_tls.h>
#endif

#include <private/pixelflinger/ggl_context.h>
#include <hardware/gralloc.h>

#include <GLES/gl.h>
#include <GLES/glext.h>

namespace android {


const unsigned int OGLES_NUM_COMPRESSED_TEXTURE_FORMATS = 10
#ifdef GL_OES_compressed_ETC1_RGB8_texture
        + 1
#endif
        ;

class EGLTextureObject;
class EGLSurfaceManager;
class EGLBufferObjectManager;

namespace gl {

struct ogles_context_t;
struct matrixx_t;
struct transform_t;
struct buffer_t;

ogles_context_t* getGlContext();

template<typename T>
static inline void swap(T& a, T& b) {
    T t(a); a = b; b = t;
}
template<typename T>
inline T max(T a, T b) {
    return a<b ? b : a;
}
template<typename T>
inline T max(T a, T b, T c) {
    return max(a, max(b, c));
}
template<typename T>
inline T min(T a, T b) {
    return a<b ? a : b;
}
template<typename T>
inline T min(T a, T b, T c) {
    return min(a, min(b, c));
}
template<typename T>
inline T min(T a, T b, T c, T d) {
    return min(min(a,b), min(c,d));
}

// ----------------------------------------------------------------------------
// vertices
// ----------------------------------------------------------------------------

struct vec3_t {
    union {
        struct { GLfixed x, y, z; };
        struct { GLfixed r, g, b; };
        struct { GLfixed S, T, R; };
        GLfixed v[3];
    };
};

struct vec4_t {
    union {
        struct { GLfixed x, y, z, w; };
        struct { GLfixed r, g, b, a; };
        struct { GLfixed S, T, R, Q; };
        GLfixed v[4];
    };
};

struct vertex_t {
    enum {
        // these constant matter for our clipping
        CLIP_L          = 0x0001,   // clipping flags
        CLIP_R          = 0x0002,
        CLIP_B          = 0x0004,
        CLIP_T          = 0x0008,
        CLIP_N          = 0x0010,
        CLIP_F          = 0x0020,

        EYE             = 0x0040,
        RESERVED        = 0x0080,

        USER_CLIP_0     = 0x0100,   // user clipping flags
        USER_CLIP_1     = 0x0200,
        USER_CLIP_2     = 0x0400,
        USER_CLIP_3     = 0x0800,
        USER_CLIP_4     = 0x1000,
        USER_CLIP_5     = 0x2000,

        LIT             = 0x4000,   // lighting has been applied
        TT              = 0x8000,   // texture coords transformed

        FRUSTUM_CLIP_ALL= 0x003F,
        USER_CLIP_ALL   = 0x3F00,
        CLIP_ALL        = 0x3F3F,
    };

    // the fields below are arranged to minimize d-cache usage
    // we group together, by cache-line, the fields most likely to be used

    union {
    vec4_t          obj;
    vec4_t          eye;
    };
    vec4_t          clip;

    uint32_t        flags;
    size_t          index;  // cache tag, and vertex index
    GLfixed         fog;
    uint8_t         locked;
    uint8_t         mru;
    uint8_t         reserved[2];
    vec4_t          window;

    vec4_t          color;
    vec4_t          texture[GGL_TEXTURE_UNIT_COUNT];
    uint32_t        reserved1[4];

    inline void clear() {
        flags = index = locked = mru = 0;
    }
};

struct point_size_t {
    GGLcoord    size;
    GLboolean   smooth;
};

struct line_width_t {
    GGLcoord    width;
    GLboolean   smooth;
};

struct polygon_offset_t {
    GLfixed     factor;
    GLfixed     units;
    GLboolean   enable;
};

// ----------------------------------------------------------------------------
// arrays
// ----------------------------------------------------------------------------

struct array_t {
    typedef void (*fetcher_t)(ogles_context_t*, GLfixed*, const GLvoid*);
    fetcher_t       fetch;
    GLvoid const*   physical_pointer;
    GLint           size;
    GLsizei         stride;
    GLvoid const*   pointer;
    buffer_t const* bo;
    uint16_t        type;
    GLboolean       enable;
    GLboolean       pad;
    GLsizei         bounds;
    void init(GLint, GLenum, GLsizei, const GLvoid *, const buffer_t*, GLsizei);
    inline void resolve();
    inline const GLubyte* element(GLint i) const {
        return (const GLubyte*)physical_pointer + i * stride;
    }
};

struct array_machine_t {
    array_t         vertex;
    array_t         normal;
    array_t         color;
    array_t         texture[GGL_TEXTURE_UNIT_COUNT];
    uint8_t         activeTexture;
    uint8_t         tmu;
    uint16_t        cull;
    uint32_t        flags;
    GLenum          indicesType;
    buffer_t const* array_buffer;
    buffer_t const* element_array_buffer;

    void (*compileElements)(ogles_context_t*, vertex_t*, GLint, GLsizei);
    void (*compileElement)(ogles_context_t*, vertex_t*, GLint);

    void (*mvp_transform)(transform_t const*, vec4_t*, vec4_t const*);
    void (*mv_transform)(transform_t const*, vec4_t*, vec4_t const*);
    void (*tex_transform[2])(transform_t const*, vec4_t*, vec4_t const*);
    void (*perspective)(ogles_context_t*c, vertex_t* v);
    void (*clipVertex)(ogles_context_t* c, vertex_t* nv,
            GGLfixed t, const vertex_t* s, const vertex_t* p);
    void (*clipEye)(ogles_context_t* c, vertex_t* nv,
            GGLfixed t, const vertex_t* s, const vertex_t* p);
};

struct vertex_cache_t {
    enum {
        // must be at least 4
        // 3 vertice for triangles
        // or 2 + 2 for indexed triangles w/ cache contention
        VERTEX_BUFFER_SIZE  = 8,
        // must be a power of two and at least 3
        VERTEX_CACHE_SIZE   = 64,   // 8 KB

        INDEX_BITS      = 16,
        INDEX_MASK      = ((1LU<<INDEX_BITS)-1),
        INDEX_SEQ       = 1LU<<INDEX_BITS,
    };
    vertex_t*       vBuffer;
    vertex_t*       vCache;
    uint32_t        sequence;
    void*           base;
    uint32_t        total;
    uint32_t        misses;
    int64_t         startTime;
    void init();
    void uninit();
    void clear();
    void dump_stats(GLenum mode);
};

// ----------------------------------------------------------------------------
// fog
// ----------------------------------------------------------------------------

struct fog_t {
    GLfixed     density;
    GLfixed     start;
    GLfixed     end;
    GLfixed     invEndMinusStart;
    GLenum      mode;
    GLfixed     (*fog)(ogles_context_t* c, GLfixed z);
};

// ----------------------------------------------------------------------------
// user clip planes
// ----------------------------------------------------------------------------

const unsigned int OGLES_MAX_CLIP_PLANES = 6;

struct clip_plane_t {
    vec4_t      equation;
};

struct user_clip_planes_t {
    clip_plane_t    plane[OGLES_MAX_CLIP_PLANES];
    uint32_t        enable;
};

// ----------------------------------------------------------------------------
// lighting
// ----------------------------------------------------------------------------

const unsigned int OGLES_MAX_LIGHTS = 8;

struct light_t {
    vec4_t      ambient;
    vec4_t      diffuse;
    vec4_t      specular;
    vec4_t      implicitAmbient;
    vec4_t      implicitDiffuse;
    vec4_t      implicitSpecular;
    vec4_t      position;       // position in eye space
    vec4_t      objPosition;
    vec4_t      normalizedObjPosition;
    vec4_t      spotDir;
    vec4_t      normalizedSpotDir;
    GLfixed     spotExp;
    GLfixed     spotCutoff;
    GLfixed     spotCutoffCosine;
    GLfixed     attenuation[3];
    GLfixed     rConstAttenuation;
    GLboolean   enable;
};

struct material_t {
    vec4_t      ambient;
    vec4_t      diffuse;
    vec4_t      specular;
    vec4_t      emission;
    GLfixed     shininess;
};

struct light_model_t {
    vec4_t      ambient;
    GLboolean   twoSide;
};

struct color_material_t {
    GLenum      face;
    GLenum      mode;
    GLboolean   enable;
};

struct lighting_t {
    light_t             lights[OGLES_MAX_LIGHTS];
    material_t          front;
    light_model_t       lightModel;
    color_material_t    colorMaterial;
    vec4_t              implicitSceneEmissionAndAmbient;
    vec4_t              objViewer;
    uint32_t            enabledLights;
    GLboolean           enable;
    GLenum              shadeModel;
    typedef void (*light_fct_t)(ogles_context_t*, vertex_t*);
    void (*lightVertex)(ogles_context_t* c, vertex_t* v);
    void (*lightTriangle)(ogles_context_t* c,
            vertex_t* v0, vertex_t* v1, vertex_t* v2);
};

struct culling_t {
    GLenum      cullFace;
    GLenum      frontFace;
    GLboolean   enable;
};

// ----------------------------------------------------------------------------
// textures
// ----------------------------------------------------------------------------

struct texture_unit_t {
    GLuint              name;
    EGLTextureObject*   texture;
    uint8_t             dirty;
};

struct texture_state_t
{
    texture_unit_t      tmu[GGL_TEXTURE_UNIT_COUNT];
    int                 active;     // active tmu
    EGLTextureObject*   defaultTexture;
    GGLContext*         ggl;
    uint8_t             packAlignment;
    uint8_t             unpackAlignment;
};

// ----------------------------------------------------------------------------
// transformation and matrices
// ----------------------------------------------------------------------------

struct matrixf_t;

struct matrixx_t {
    GLfixed m[16];
    void load(const matrixf_t& rhs);
};

struct matrix_stack_t;


struct matrixf_t {
    void loadIdentity();
    void load(const matrixf_t& rhs);

    inline GLfloat* editElements() { return m; }
    inline GLfloat const* elements() const { return m; }

    void set(const GLfixed* rhs);
    void set(const GLfloat* rhs);

    static void multiply(matrixf_t& r,
            const matrixf_t& lhs, const matrixf_t& rhs);

    void dump(const char* what);

private:
    friend struct matrix_stack_t;
    GLfloat     m[16];
    void load(const GLfixed* rhs);
    void load(const GLfloat* rhs);
    void multiply(const matrixf_t& rhs);
    void translate(GLfloat x, GLfloat y, GLfloat z);
    void scale(GLfloat x, GLfloat y, GLfloat z);
    void rotate(GLfloat a, GLfloat x, GLfloat y, GLfloat z);
};

enum {
    OP_IDENTITY         = 0x00,
    OP_TRANSLATE        = 0x01,
    OP_UNIFORM_SCALE    = 0x02,
    OP_SCALE            = 0x05,
    OP_ROTATE           = 0x08,
    OP_SKEW             = 0x10,
    OP_ALL              = 0x1F
};

struct transform_t {
    enum {
        FLAGS_2D_PROJECTION = 0x1
    };
    matrixx_t       matrix;
    uint32_t        flags;
    uint32_t        ops;

    union {
        struct {
            void (*point2)(transform_t const* t, vec4_t*, vec4_t const*);
            void (*point3)(transform_t const* t, vec4_t*, vec4_t const*);
            void (*point4)(transform_t const* t, vec4_t*, vec4_t const*);
        };
        void (*pointv[3])(transform_t const* t, vec4_t*, vec4_t const*);
    };

    void loadIdentity();
    void picker();
    void dump(const char* what);
};

struct mvui_transform_t : public transform_t
{
    void picker();
};

struct matrix_stack_t {
    enum {
        DO_PICKER           = 0x1,
        DO_FLOAT_TO_FIXED   = 0x2
    };
    transform_t     transform;
    uint8_t         maxDepth;
    uint8_t         depth;
    uint8_t         dirty;
    uint8_t         reserved;
    matrixf_t       *stack;
    uint8_t         *ops;
    void init(int depth);
    void uninit();
    void loadIdentity();
    void load(const GLfixed* rhs);
    void load(const GLfloat* rhs);
    void multiply(const matrixf_t& rhs);
    void translate(GLfloat x, GLfloat y, GLfloat z);
    void scale(GLfloat x, GLfloat y, GLfloat z);
    void rotate(GLfloat a, GLfloat x, GLfloat y, GLfloat z);
    GLint push();
    GLint pop();
    void validate();
    matrixf_t& top() { return stack[depth]; }
    const matrixf_t& top() const { return stack[depth]; }
    uint32_t top_ops() const { return ops[depth]; }
    inline bool isRigidBody() const {
        return !(ops[depth] & ~(OP_TRANSLATE|OP_UNIFORM_SCALE|OP_ROTATE));
    }
};

struct vp_transform_t {
    transform_t     transform;
    matrixf_t       matrix;
    GLfloat         zNear;
    GLfloat         zFar;
    void loadIdentity();
};

struct transform_state_t {
    enum {
        MODELVIEW           = 0x01,
        PROJECTION          = 0x02,
        VIEWPORT            = 0x04,
        TEXTURE             = 0x08,
        MVUI                = 0x10,
        MVIT                = 0x20,
        MVP                 = 0x40,
    };
    matrix_stack_t      *current;
    matrix_stack_t      modelview;
    matrix_stack_t      projection;
    matrix_stack_t      texture[GGL_TEXTURE_UNIT_COUNT];

    // modelview * projection
    transform_t         mvp     __attribute__((aligned(32)));
    // viewport transformation
    vp_transform_t      vpt     __attribute__((aligned(32)));
    // same for 4-D vertices
    transform_t         mvp4;
    // full modelview inverse transpose
    transform_t         mvit4;
    // upper 3x3 of mv-inverse-transpose (for normals)
    mvui_transform_t    mvui;

    GLenum              matrixMode;
    GLenum              rescaleNormals;
    uint32_t            dirty;
    void invalidate();
    void update_mvp();
    void update_mvit();
    void update_mvui();
};

struct viewport_t {
    GLint       x;
    GLint       y;
    GLsizei     w;
    GLsizei     h;
    struct {
        GLint       x;
        GLint       y;
    } surfaceport;
    struct {
        GLint       x;
        GLint       y;
        GLsizei     w;
        GLsizei     h;
    } scissor;
};

// ----------------------------------------------------------------------------
// Lerping
// ----------------------------------------------------------------------------

struct compute_iterators_t
{
    void initTriangle(
            vertex_t const* v0,
            vertex_t const* v1,
            vertex_t const* v2);

    void initLine(
            vertex_t const* v0,
            vertex_t const* v1);

    inline void initLerp(vertex_t const* v0, uint32_t enables);

    int iteratorsScale(int32_t it[3],
            int32_t c0, int32_t c1, int32_t c2) const;

    void iterators1616(GGLfixed it[3],
            GGLfixed c0, GGLfixed c1, GGLfixed c2) const;

    void iterators0032(int32_t it[3],
            int32_t c0, int32_t c1, int32_t c2) const;

    void iterators0032(int64_t it[3],
            int32_t c0, int32_t c1, int32_t c2) const;

    GGLcoord area() const { return m_area; }

private:
    // don't change order of members here -- used by iterators.S
    GGLcoord m_dx01, m_dy10, m_dx20, m_dy02;
    GGLcoord m_x0, m_y0;
    GGLcoord m_area;
    uint8_t m_scale;
    uint8_t m_area_scale;
    uint8_t m_reserved[2];

};

// ----------------------------------------------------------------------------
// state
// ----------------------------------------------------------------------------

#ifdef HAVE_ANDROID_OS
    // We have a dedicated TLS slot in bionic
    inline void setGlThreadSpecific(ogles_context_t *value) {
        ((uint32_t *)__get_tls())[TLS_SLOT_OPENGL] = (uint32_t)value;
    }
    inline ogles_context_t* getGlThreadSpecific() {
        return (ogles_context_t *)(((unsigned *)__get_tls())[TLS_SLOT_OPENGL]);
    }
#else
    extern pthread_key_t gGLKey;
    inline void setGlThreadSpecific(ogles_context_t *value) {
        pthread_setspecific(gGLKey, value);
    }
    inline ogles_context_t* getGlThreadSpecific() {
        return static_cast<ogles_context_t*>(pthread_getspecific(gGLKey));
    }
#endif


struct prims_t {
    typedef ogles_context_t* GL;
    void (*renderPoint)(GL, vertex_t*);
    void (*renderLine)(GL, vertex_t*, vertex_t*);
    void (*renderTriangle)(GL, vertex_t*, vertex_t*, vertex_t*);
};

struct ogles_context_t {
    context_t               rasterizer;
    array_machine_t         arrays         __attribute__((aligned(32)));
    texture_state_t         textures;
    transform_state_t       transforms;
    vertex_cache_t          vc;
    prims_t                 prims;
    culling_t               cull;
    lighting_t              lighting;
    user_clip_planes_t      clipPlanes;
    compute_iterators_t     lerp;           __attribute__((aligned(32)));
    vertex_t                current;
    vec4_t                  currentColorClamped;
    vec3_t                  currentNormal;
    viewport_t              viewport;
    point_size_t            point;
    line_width_t            line;
    polygon_offset_t        polygonOffset;
    fog_t                   fog;
    uint32_t                perspective : 1;
    uint32_t                transformTextures : 1;
    EGLSurfaceManager*      surfaceManager;
    EGLBufferObjectManager* bufferObjectManager;

    GLenum                  error;

    static inline ogles_context_t* get() {
        return getGlThreadSpecific();
    }

};

}; // namespace gl
}; // namespace android

using namespace android::gl;

#endif // ANDROID_OPENGLES_CONTEXT_H

