#include <stdint.h>


typedef void * RsAdapter1D;
typedef void * RsAdapter2D;
typedef void * RsAllocation;
typedef void * RsContext;
typedef void * RsDevice;
typedef void * RsElement;
typedef void * RsSampler;
typedef void * RsScript;
typedef void * RsMesh;
typedef void * RsType;
typedef void * RsProgramFragment;
typedef void * RsProgramStore;

typedef struct {
    float m[16];
} rsc_Matrix;


typedef struct {
    float v[4];
} rsc_Vector4;

#define RS_PROGRAM_VERTEX_MODELVIEW_OFFSET 0
#define RS_PROGRAM_VERTEX_PROJECTION_OFFSET 16
#define RS_PROGRAM_VERTEX_TEXTURE_OFFSET 32
#define RS_PROGRAM_VERTEX_MVP_OFFSET 48
