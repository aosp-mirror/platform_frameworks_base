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
    const void * (*loadEnvVp)(void *con, uint32_t bank, uint32_t offset);

    float (*loadEnvF)(void *con, uint32_t bank, uint32_t offset);
    int32_t (*loadEnvI32)(void *con, uint32_t bank, uint32_t offset);
    uint32_t (*loadEnvU32)(void *con, uint32_t bank, uint32_t offset);
    void (*loadEnvVec4)(void *con, uint32_t bank, uint32_t offset, rsc_Vector4 *);
    void (*loadEnvMatrix)(void *con, uint32_t bank, uint32_t offset, rsc_Matrix *);

    void (*storeEnvF)(void *con, uint32_t bank, uint32_t offset, float);
    void (*storeEnvI32)(void *con, uint32_t bank, uint32_t offset, int32_t);
    void (*storeEnvU32)(void *con, uint32_t bank, uint32_t offset, uint32_t);
    void (*storeEnvVec4)(void *con, uint32_t bank, uint32_t offset, const rsc_Vector4 *);
    void (*storeEnvMatrix)(void *con, uint32_t bank, uint32_t offset, const rsc_Matrix *);

    void (*matrixLoadIdentity)(void *con, rsc_Matrix *);
    void (*matrixLoadFloat)(void *con, rsc_Matrix *, const float *);
    void (*matrixLoadMat)(void *con, rsc_Matrix *, const rsc_Matrix *);
    void (*matrixLoadRotate)(void *con, rsc_Matrix *, float rot, float x, float y, float z);
    void (*matrixLoadScale)(void *con, rsc_Matrix *, float x, float y, float z);
    void (*matrixLoadTranslate)(void *con, rsc_Matrix *, float x, float y, float z);
    void (*matrixLoadMultiply)(void *con, rsc_Matrix *, const rsc_Matrix *lhs, const rsc_Matrix *rhs);
    void (*matrixMultiply)(void *con, rsc_Matrix *, const rsc_Matrix *rhs);
    void (*matrixRotate)(void *con, rsc_Matrix *, float rot, float x, float y, float z);
    void (*matrixScale)(void *con, rsc_Matrix *, float x, float y, float z);
    void (*matrixTranslate)(void *con, rsc_Matrix *, float x, float y, float z);

    void (*color)(void *con, float r, float g, float b, float a);

    void (*programFragmentBindTexture)(void *con, RsProgramFragment, uint32_t slot, RsAllocation);
    void (*programFragmentBindSampler)(void *con, RsProgramFragment, uint32_t slot, RsAllocation);

    void (*materialDiffuse)(void *con, float r, float g, float b, float a);
    void (*materialSpecular)(void *con, float r, float g, float b, float a);
    void (*lightPosition)(void *con, float x, float y, float z, float w);
    void (*materialShininess)(void *con, float s);

    void (*uploadToTexture)(void *con, RsAllocation va, uint32_t baseMipLevel);

    void (*enable)(void *con, uint32_t);
    void (*disable)(void *con, uint32_t);

    uint32_t (*rand)(void *con, uint32_t max);

    void (*contextBindProgramFragment)(void *con, RsProgramFragment pf);
    void (*contextBindProgramFragmentStore)(void *con, RsProgramFragmentStore pfs);


    // Drawing funcs
    void (*renderTriangleMesh)(void *con, RsTriangleMesh);
    void (*renderTriangleMeshRange)(void *con, RsTriangleMesh, uint32_t start, uint32_t count);

    // Assumes (GL_FIXED) x,y,z (GL_UNSIGNED_BYTE)r,g,b,a
    void (*drawTriangleArray)(void *con, RsAllocation alloc, uint32_t count);

    void (*drawRect)(void *con, int32_t x1, int32_t x2, int32_t y1, int32_t y2);
} rsc_FunctionTable;

typedef int (*rsc_RunScript)(void *con, const rsc_FunctionTable *, uint32_t launchID);


/* EnableCap */
#define GL_LIGHTING                       0x0B50

/* LightName */
#define GL_LIGHT0                         0x4000
