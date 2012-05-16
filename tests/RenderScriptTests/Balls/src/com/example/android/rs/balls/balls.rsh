
typedef struct __attribute__((packed, aligned(4))) Ball {
    float2 delta;
    float2 position;
    //float3 color;
    float size;
    //int arcID;
    //float arcStr;
} Ball_t;
Ball_t *balls;


typedef struct BallControl {
    uint32_t dimX;
    rs_allocation ain;
    rs_allocation aout;
    float dt;
} BallControl_t;
