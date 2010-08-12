#pragma rs java_package_name(com.android.rs.image)

#define MAX_RADIUS 25

typedef struct FilterStruct_s {
    rs_allocation ain;

    float *gaussian; //[MAX_RADIUS * 2 + 1];
    int height;
    int width;
    int radius;

} FilterStruct;


