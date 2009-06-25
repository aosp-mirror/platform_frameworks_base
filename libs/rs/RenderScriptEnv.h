#include <stdint.h>


typedef void * RsAdapter1D;
typedef void * RsAdapter2D;
typedef void * RsAllocation;
typedef void * RsContext;
typedef void * RsDevice;
typedef void * RsElement;
typedef void * RsSampler;
typedef void * RsScript;
typedef void * RsScriptBasicTemp;
typedef void * RsTriangleMesh;
typedef void * RsType;
typedef void * RsProgramFragment;
typedef void * RsProgramFragmentStore;
typedef void * RsLight;


typedef struct {
    float m[16];
} rsc_Matrix;


typedef struct {
    float v[4];
} rsc_Vector4;

#define RS_PROGRAM_VERTEX_MODELVIEW_OFFSET 0
#define RS_PROGRAM_VERTEX_PROJECTION_OFFSET 16
#define RS_PROGRAM_VERTEX_TEXTURE_OFFSET 32

typedef struct {
    const void * (*loadEnvVp)(uint32_t bank, uint32_t offset);

    float (*loadEnvF)(uint32_t bank, uint32_t offset);
    int32_t (*loadEnvI32)(uint32_t bank, uint32_t offset);
    uint32_t (*loadEnvU32)(uint32_t bank, uint32_t offset);
    void (*loadEnvVec4)(uint32_t bank, uint32_t offset, rsc_Vector4 *);
    void (*loadEnvMatrix)(uint32_t bank, uint32_t offset, rsc_Matrix *);

    void (*storeEnvF)(uint32_t bank, uint32_t offset, float);
    void (*storeEnvI32)(uint32_t bank, uint32_t offset, int32_t);
    void (*storeEnvU32)(uint32_t bank, uint32_t offset, uint32_t);
    void (*storeEnvVec4)(uint32_t bank, uint32_t offset, const rsc_Vector4 *);
    void (*storeEnvMatrix)(uint32_t bank, uint32_t offset, const rsc_Matrix *);

    void (*matrixLoadIdentity)(rsc_Matrix *);
    void (*matrixLoadFloat)(rsc_Matrix *, const float *);
    void (*matrixLoadMat)(rsc_Matrix *, const rsc_Matrix *);
    void (*matrixLoadRotate)(rsc_Matrix *, float rot, float x, float y, float z);
    void (*matrixLoadScale)(rsc_Matrix *, float x, float y, float z);
    void (*matrixLoadTranslate)(rsc_Matrix *, float x, float y, float z);
    void (*matrixLoadMultiply)(rsc_Matrix *, const rsc_Matrix *lhs, const rsc_Matrix *rhs);
    void (*matrixMultiply)(rsc_Matrix *, const rsc_Matrix *rhs);
    void (*matrixRotate)(rsc_Matrix *, float rot, float x, float y, float z);
    void (*matrixScale)(rsc_Matrix *, float x, float y, float z);
    void (*matrixTranslate)(rsc_Matrix *, float x, float y, float z);

    void (*color)(float r, float g, float b, float a);

    void (*programFragmentBindTexture)(RsProgramFragment, uint32_t slot, RsAllocation);
    void (*programFragmentBindSampler)(RsProgramFragment, uint32_t slot, RsAllocation);

    void (*materialDiffuse)(float r, float g, float b, float a);
    void (*materialSpecular)(float r, float g, float b, float a);
    void (*lightPosition)(float x, float y, float z, float w);
    void (*materialShininess)(float s);

    void (*uploadToTexture)(RsAllocation va, uint32_t baseMipLevel);

    void (*enable)(uint32_t);
    void (*disable)(uint32_t);

    uint32_t (*rand)(uint32_t max);

    void (*contextBindProgramFragment)(RsProgramFragment pf);
    void (*contextBindProgramFragmentStore)(RsProgramFragmentStore pfs);


    // Drawing funcs
    void (*renderTriangleMesh)(RsTriangleMesh);
    void (*renderTriangleMeshRange)(RsTriangleMesh, uint32_t start, uint32_t count);

    // Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
    void (*drawTriangleArray)(RsAllocation alloc, uint32_t count);

    void (*drawRect)(int32_t x1, int32_t x2, int32_t y1, int32_t y2);
} rsc_FunctionTable;

typedef int (*rsc_RunScript)(uint32_t launchIndex, const rsc_FunctionTable *);


/* EnableCap */
#define GL_LIGHTING                       0x0B50

/* LightName */
#define GL_LIGHT0                         0x4000
