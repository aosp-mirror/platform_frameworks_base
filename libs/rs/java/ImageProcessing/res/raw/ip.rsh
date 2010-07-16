#pragma rs java_package_name(com.android.rs.image)

#define MAX_RADIUS 25

typedef struct {
    float *gaussian; //[MAX_RADIUS * 2 + 1];
    rs_matrix3x3 colorMat;

    int height;
    int width;
    int radius;

} FilterStruct;


