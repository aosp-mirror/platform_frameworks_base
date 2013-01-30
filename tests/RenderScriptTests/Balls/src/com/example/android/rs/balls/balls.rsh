
typedef struct __attribute__((packed, aligned(4))) Ball {
    float2 delta;
    float2 position;
    uchar4 color;
    float pressure;
    //float size;
    int32_t next;
    //int arcID;
    //float arcStr;
} Ball_t;
Ball_t *balls;


typedef struct BallGrid {
    int32_t idx;
    int32_t count;
    int32_t cacheIdx;
} BallGrid_t;
