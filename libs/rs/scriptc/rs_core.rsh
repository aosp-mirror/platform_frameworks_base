#ifndef __RS_CORE_RSH__
#define __RS_CORE_RSH__

static void __attribute__((overloadable)) rsDebug(const char *s, float2 v) {
    rsDebug(s, v.x, v.y);
}
static void __attribute__((overloadable)) rsDebug(const char *s, float3 v) {
    rsDebug(s, v.x, v.y, v.z);
}
static void __attribute__((overloadable)) rsDebug(const char *s, float4 v) {
    rsDebug(s, v.x, v.y, v.z, v.w);
}

static uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b)
{
    uchar4 c;
    c.x = (uchar)(r * 255.f);
    c.y = (uchar)(g * 255.f);
    c.z = (uchar)(b * 255.f);
    c.w = 255;
    return c;
}

static uchar4 __attribute__((overloadable)) rsPackColorTo8888(float r, float g, float b, float a)
{
    uchar4 c;
    c.x = (uchar)(r * 255.f);
    c.y = (uchar)(g * 255.f);
    c.z = (uchar)(b * 255.f);
    c.w = (uchar)(a * 255.f);
    return c;
}

static uchar4 __attribute__((overloadable)) rsPackColorTo8888(float3 color)
{
    color *= 255.f;
    uchar4 c = {color.x, color.y, color.z, 255};
    return c;
}

static uchar4 __attribute__((overloadable)) rsPackColorTo8888(float4 color)
{
    color *= 255.f;
    uchar4 c = {color.x, color.y, color.z, color.w};
    return c;
}

static float4 rsUnpackColor8888(uchar4 c)
{
    float4 ret = (float4)0.0039156862745f;
    ret *= convert_float4(c);
    return ret;
}

//extern uchar4 __attribute__((overloadable)) rsPackColorTo565(float r, float g, float b);
//extern uchar4 __attribute__((overloadable)) rsPackColorTo565(float3);
//extern float4 rsUnpackColor565(uchar4);


/////////////////////////////////////////////////////
// Matrix ops
/////////////////////////////////////////////////////

static void __attribute__((overloadable))
rsMatrixSet(rs_matrix4x4 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 4 + col] = v;
}

static float __attribute__((overloadable))
rsMatrixGet(const rs_matrix4x4 *m, uint32_t row, uint32_t col) {
    return m->m[row * 4 + col];
}

static void __attribute__((overloadable))
rsMatrixSet(rs_matrix3x3 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 3 + col] = v;
}

static float __attribute__((overloadable))
rsMatrixGet(const rs_matrix3x3 *m, uint32_t row, uint32_t col) {
    return m->m[row * 3 + col];
}

static void __attribute__((overloadable))
rsMatrixSet(rs_matrix2x2 *m, uint32_t row, uint32_t col, float v) {
    m->m[row * 2 + col] = v;
}

static float __attribute__((overloadable))
rsMatrixGet(const rs_matrix2x2 *m, uint32_t row, uint32_t col) {
    return m->m[row * 2 + col];
}

static void __attribute__((overloadable))
rsMatrixLoadIdentity(rs_matrix4x4 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = 0.f;
    m->m[5] = 1.f;
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = 0.f;
    m->m[9] = 0.f;
    m->m[10] = 1.f;
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void __attribute__((overloadable))
rsMatrixLoadIdentity(rs_matrix3x3 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = 1.f;
    m->m[5] = 0.f;
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = 1.f;
}

static void __attribute__((overloadable))
rsMatrixLoadIdentity(rs_matrix2x2 *m) {
    m->m[0] = 1.f;
    m->m[1] = 0.f;
    m->m[2] = 0.f;
    m->m[3] = 1.f;
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix4x4 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
    m->m[4] = v[4];
    m->m[5] = v[5];
    m->m[6] = v[6];
    m->m[7] = v[7];
    m->m[8] = v[8];
    m->m[9] = v[9];
    m->m[10] = v[10];
    m->m[11] = v[11];
    m->m[12] = v[12];
    m->m[13] = v[13];
    m->m[14] = v[14];
    m->m[15] = v[15];
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix3x3 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
    m->m[4] = v[4];
    m->m[5] = v[5];
    m->m[6] = v[6];
    m->m[7] = v[7];
    m->m[8] = v[8];
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix2x2 *m, const float *v) {
    m->m[0] = v[0];
    m->m[1] = v[1];
    m->m[2] = v[2];
    m->m[3] = v[3];
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix4x4 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
    m->m[4] = v->m[4];
    m->m[5] = v->m[5];
    m->m[6] = v->m[6];
    m->m[7] = v->m[7];
    m->m[8] = v->m[8];
    m->m[9] = v->m[9];
    m->m[10] = v->m[10];
    m->m[11] = v->m[11];
    m->m[12] = v->m[12];
    m->m[13] = v->m[13];
    m->m[14] = v->m[14];
    m->m[15] = v->m[15];
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix3x3 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = 0.f;
    m->m[4] = v->m[3];
    m->m[5] = v->m[4];
    m->m[6] = v->m[5];
    m->m[7] = 0.f;
    m->m[8] = v->m[6];
    m->m[9] = v->m[7];
    m->m[10] = v->m[8];
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix4x4 *m, const rs_matrix2x2 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = 0.f;
    m->m[3] = 0.f;
    m->m[4] = v->m[3];
    m->m[5] = v->m[4];
    m->m[6] = 0.f;
    m->m[7] = 0.f;
    m->m[8] = v->m[6];
    m->m[9] = v->m[7];
    m->m[10] = 1.f;
    m->m[11] = 0.f;
    m->m[12] = 0.f;
    m->m[13] = 0.f;
    m->m[14] = 0.f;
    m->m[15] = 1.f;
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix3x3 *m, const rs_matrix3x3 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
    m->m[4] = v->m[4];
    m->m[5] = v->m[5];
    m->m[6] = v->m[6];
    m->m[7] = v->m[7];
    m->m[8] = v->m[8];
}

static void __attribute__((overloadable))
rsMatrixLoad(rs_matrix2x2 *m, const rs_matrix2x2 *v) {
    m->m[0] = v->m[0];
    m->m[1] = v->m[1];
    m->m[2] = v->m[2];
    m->m[3] = v->m[3];
}

static void __attribute__((overloadable))
rsMatrixLoadRotate(rs_matrix4x4 *m, float rot, float x, float y, float z) {
    float c, s;
    m->m[3] = 0;
    m->m[7] = 0;
    m->m[11]= 0;
    m->m[12]= 0;
    m->m[13]= 0;
    m->m[14]= 0;
    m->m[15]= 1;
    rot *= (float)(M_PI / 180.0f);
    c = cos(rot);
    s = sin(rot);

    const float len = x*x + y*y + z*z;
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        x *= recipLen;
        y *= recipLen;
        z *= recipLen;
    }
    const float nc = 1.0f - c;
    const float xy = x * y;
    const float yz = y * z;
    const float zx = z * x;
    const float xs = x * s;
    const float ys = y * s;
    const float zs = z * s;
    m->m[ 0] = x*x*nc +  c;
    m->m[ 4] =  xy*nc - zs;
    m->m[ 8] =  zx*nc + ys;
    m->m[ 1] =  xy*nc + zs;
    m->m[ 5] = y*y*nc +  c;
    m->m[ 9] =  yz*nc - xs;
    m->m[ 2] =  zx*nc - ys;
    m->m[ 6] =  yz*nc + xs;
    m->m[10] = z*z*nc +  c;
}

static void __attribute__((overloadable))
rsMatrixLoadScale(rs_matrix4x4 *m, float x, float y, float z) {
    rsMatrixLoadIdentity(m);
    m->m[0] = x;
    m->m[5] = y;
    m->m[10] = z;
}

static void __attribute__((overloadable))
rsMatrixLoadTranslate(rs_matrix4x4 *m, float x, float y, float z) {
    rsMatrixLoadIdentity(m);
    m->m[12] = x;
    m->m[13] = y;
    m->m[14] = z;
}

static void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs) {
    for (int i=0 ; i<4 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        float ri3 = 0;
        for (int j=0 ; j<4 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
            ri2 += rsMatrixGet(lhs, j, 2) * rhs_ij;
            ri3 += rsMatrixGet(lhs, j, 3) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
        rsMatrixSet(m, i, 2, ri2);
        rsMatrixSet(m, i, 3, ri3);
    }
}

static void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, const rs_matrix4x4 *rhs) {
    rs_matrix4x4 mt;
    rsMatrixLoadMultiply(&mt, m, rhs);
    rsMatrixLoad(m, &mt);
}

static void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *lhs, const rs_matrix3x3 *rhs) {
    for (int i=0 ; i<3 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        float ri2 = 0;
        for (int j=0 ; j<3 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
            ri2 += rsMatrixGet(lhs, j, 2) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
        rsMatrixSet(m, i, 2, ri2);
    }
}

static void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, const rs_matrix3x3 *rhs) {
    rs_matrix3x3 mt;
    rsMatrixLoadMultiply(&mt, m, rhs);
    rsMatrixLoad(m, &mt);
}

static void __attribute__((overloadable))
rsMatrixLoadMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *lhs, const rs_matrix2x2 *rhs) {
    for (int i=0 ; i<2 ; i++) {
        float ri0 = 0;
        float ri1 = 0;
        for (int j=0 ; j<2 ; j++) {
            const float rhs_ij = rsMatrixGet(rhs, i,j);
            ri0 += rsMatrixGet(lhs, j, 0) * rhs_ij;
            ri1 += rsMatrixGet(lhs, j, 1) * rhs_ij;
        }
        rsMatrixSet(m, i, 0, ri0);
        rsMatrixSet(m, i, 1, ri1);
    }
}

static void __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, const rs_matrix2x2 *rhs) {
    rs_matrix2x2 mt;
    rsMatrixLoadMultiply(&mt, m, rhs);
    rsMatrixLoad(m, &mt);
}

static void __attribute__((overloadable))
rsMatrixRotate(rs_matrix4x4 *m, float rot, float x, float y, float z) {
    rs_matrix4x4 m1;
    rsMatrixLoadRotate(&m1, rot, x, y, z);
    rsMatrixMultiply(m, &m1);
}

static void __attribute__((overloadable))
rsMatrixScale(rs_matrix4x4 *m, float x, float y, float z) {
    rs_matrix4x4 m1;
    rsMatrixLoadScale(&m1, x, y, z);
    rsMatrixMultiply(m, &m1);
}

static void __attribute__((overloadable))
rsMatrixTranslate(rs_matrix4x4 *m, float x, float y, float z) {
    rs_matrix4x4 m1;
    rsMatrixLoadTranslate(&m1, x, y, z);
    rsMatrixMultiply(m, &m1);
}

static void __attribute__((overloadable))
rsMatrixLoadOrtho(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far) {
    rsMatrixLoadIdentity(m);
    m->m[0] = 2.f / (right - left);
    m->m[5] = 2.f / (top - bottom);
    m->m[10]= -2.f / (far - near);
    m->m[12]= -(right + left) / (right - left);
    m->m[13]= -(top + bottom) / (top - bottom);
    m->m[14]= -(far + near) / (far - near);
}

static void __attribute__((overloadable))
rsMatrixLoadFrustum(rs_matrix4x4 *m, float left, float right, float bottom, float top, float near, float far) {
    rsMatrixLoadIdentity(m);
    m->m[0] = 2.f * near / (right - left);
    m->m[5] = 2.f * near / (top - bottom);
    m->m[8] = (right + left) / (right - left);
    m->m[9] = (top + bottom) / (top - bottom);
    m->m[10]= -(far + near) / (far - near);
    m->m[11]= -1.f;
    m->m[14]= -2.f * far * near / (far - near);
    m->m[15]= 0.f;
}

static float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float4 in) {
    float4 ret;
    ret.x = (m->m[0] * in.x) + (m->m[4] * in.y) + (m->m[8] * in.z) + (m->m[12] * in.w);
    ret.y = (m->m[1] * in.x) + (m->m[5] * in.y) + (m->m[9] * in.z) + (m->m[13] * in.w);
    ret.z = (m->m[2] * in.x) + (m->m[6] * in.y) + (m->m[10] * in.z) + (m->m[14] * in.w);
    ret.w = (m->m[3] * in.x) + (m->m[7] * in.y) + (m->m[11] * in.z) + (m->m[15] * in.w);
    return ret;
}

static float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float3 in) {
    float4 ret;
    ret.x = (m->m[0] * in.x) + (m->m[4] * in.y) + (m->m[8] * in.z) + m->m[12];
    ret.y = (m->m[1] * in.x) + (m->m[5] * in.y) + (m->m[9] * in.z) + m->m[13];
    ret.z = (m->m[2] * in.x) + (m->m[6] * in.y) + (m->m[10] * in.z) + m->m[14];
    ret.w = (m->m[3] * in.x) + (m->m[7] * in.y) + (m->m[11] * in.z) + m->m[15];
    return ret;
}

static float4 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix4x4 *m, float2 in) {
    float4 ret;
    ret.x = (m->m[0] * in.x) + (m->m[4] * in.y) + m->m[12];
    ret.y = (m->m[1] * in.x) + (m->m[5] * in.y) + m->m[13];
    ret.z = (m->m[2] * in.x) + (m->m[6] * in.y) + m->m[14];
    ret.w = (m->m[3] * in.x) + (m->m[7] * in.y) + m->m[15];
    return ret;
}

static float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float3 in) {
    float3 ret;
    ret.x = (m->m[0] * in.x) + (m->m[3] * in.y) + (m->m[6] * in.z);
    ret.y = (m->m[1] * in.x) + (m->m[4] * in.y) + (m->m[7] * in.z);
    ret.z = (m->m[2] * in.x) + (m->m[5] * in.y) + (m->m[8] * in.z);
    return ret;
}

static float3 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix3x3 *m, float2 in) {
    float3 ret;
    ret.x = (m->m[0] * in.x) + (m->m[3] * in.y);
    ret.y = (m->m[1] * in.x) + (m->m[4] * in.y);
    ret.z = (m->m[2] * in.x) + (m->m[5] * in.y);
    return ret;
}

static float2 __attribute__((overloadable))
rsMatrixMultiply(rs_matrix2x2 *m, float2 in) {
    float2 ret;
    ret.x = (m->m[0] * in.x) + (m->m[2] * in.y);
    ret.y = (m->m[1] * in.x) + (m->m[3] * in.y);
    return ret;
}

// Returns true if the matrix was successfully inversed
static bool __attribute__((overloadable))
rsMatrixInverse(rs_matrix4x4 *m) {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor = (m->m[c0 + 4*r0] * (m->m[c1 + 4*r1] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r1]))
                         - (m->m[c0 + 4*r1] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r0]))
                         + (m->m[c0 + 4*r2] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r1] - m->m[c1 + 4*r1] * m->m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*i + j] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th row of result
    float det = m->m[0]*result.m[0] + m->m[4]*result.m[1] +
                 m->m[8]*result.m[2] + m->m[12]*result.m[3];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m->m[i] = result.m[i] * det;
    }

    return true;
}

// Returns true if the matrix was successfully inversed
static bool __attribute__((overloadable))
rsMatrixInverseTranspose(rs_matrix4x4 *m) {
    rs_matrix4x4 result;

    int i, j;
    for (i = 0; i < 4; ++i) {
        for (j = 0; j < 4; ++j) {
            // computeCofactor for int i, int j
            int c0 = (i+1) % 4;
            int c1 = (i+2) % 4;
            int c2 = (i+3) % 4;
            int r0 = (j+1) % 4;
            int r1 = (j+2) % 4;
            int r2 = (j+3) % 4;

            float minor = (m->m[c0 + 4*r0] * (m->m[c1 + 4*r1] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r1]))
                         - (m->m[c0 + 4*r1] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r2] - m->m[c1 + 4*r2] * m->m[c2 + 4*r0]))
                         + (m->m[c0 + 4*r2] * (m->m[c1 + 4*r0] * m->m[c2 + 4*r1] - m->m[c1 + 4*r1] * m->m[c2 + 4*r0]));

            float cofactor = (i+j) & 1 ? -minor : minor;

            result.m[4*j + i] = cofactor;
        }
    }

    // Dot product of 0th column of source and 0th column of result
    float det = m->m[0]*result.m[0] + m->m[4]*result.m[4] +
                 m->m[8]*result.m[8] + m->m[12]*result.m[12];

    if (fabs(det) < 1e-6) {
        return false;
    }

    det = 1.0f / det;
    for (i = 0; i < 16; ++i) {
        m->m[i] = result.m[i] * det;
    }

    return true;
}

static void __attribute__((overloadable))
rsMatrixTranspose(rs_matrix4x4 *m) {
    int i, j;
    float temp;
    for (i = 0; i < 3; ++i) {
        for (j = i + 1; j < 4; ++j) {
            temp = m->m[i*4 + j];
            m->m[i*4 + j] = m->m[j*4 + i];
            m->m[j*4 + i] = temp;
        }
    }
}

static void __attribute__((overloadable))
rsMatrixTranspose(rs_matrix3x3 *m) {
    int i, j;
    float temp;
    for (i = 0; i < 2; ++i) {
        for (j = i + 1; j < 3; ++j) {
            temp = m->m[i*3 + j];
            m->m[i*3 + j] = m->m[j*4 + i];
            m->m[j*3 + i] = temp;
        }
    }
}

static void __attribute__((overloadable))
rsMatrixTranspose(rs_matrix2x2 *m) {
    float temp = m->m[1];
    m->m[1] = m->m[2];
    m->m[2] = temp;
}

/////////////////////////////////////////////////////
// quaternion ops
/////////////////////////////////////////////////////

static void __attribute__((overloadable))
rsQuaternionSet(rs_quaternion *q, float w, float x, float y, float z) {
    q->w = w;
    q->x = x;
    q->y = y;
    q->z = z;
}

static void __attribute__((overloadable))
rsQuaternionSet(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w = rhs->w;
    q->x = rhs->x;
    q->y = rhs->y;
    q->z = rhs->z;
}

static void __attribute__((overloadable))
rsQuaternionMultiply(rs_quaternion *q, float s) {
    q->w *= s;
    q->x *= s;
    q->y *= s;
    q->z *= s;
}

static void __attribute__((overloadable))
rsQuaternionMultiply(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w = -q->x*rhs->x - q->y*rhs->y - q->z*rhs->z + q->w*rhs->w;
    q->x =  q->x*rhs->w + q->y*rhs->z - q->z*rhs->y + q->w*rhs->x;
    q->y = -q->x*rhs->z + q->y*rhs->w + q->z*rhs->z + q->w*rhs->y;
    q->z =  q->x*rhs->y - q->y*rhs->x + q->z*rhs->w + q->w*rhs->z;
}

static void
rsQuaternionAdd(rs_quaternion *q, const rs_quaternion *rhs) {
    q->w *= rhs->w;
    q->x *= rhs->x;
    q->y *= rhs->y;
    q->z *= rhs->z;
}

static void
rsQuaternionLoadRotateUnit(rs_quaternion *q, float rot, float x, float y, float z) {
    rot *= (float)(M_PI / 180.0f) * 0.5f;
    float c = cos(rot);
    float s = sin(rot);

    q->w = c;
    q->x = x * s;
    q->y = y * s;
    q->z = z * s;
}

static void
rsQuaternionLoadRotate(rs_quaternion *q, float rot, float x, float y, float z) {
    const float len = x*x + y*y + z*z;
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        x *= recipLen;
        y *= recipLen;
        z *= recipLen;
    }
    rsQuaternionLoadRotateUnit(q, rot, x, y, z);
}

static void
rsQuaternionConjugate(rs_quaternion *q) {
    q->x = -q->x;
    q->y = -q->y;
    q->z = -q->z;
}

static float
rsQuaternionDot(const rs_quaternion *q0, const rs_quaternion *q1) {
    return q0->w*q1->w + q0->x*q1->x + q0->y*q1->y + q0->z*q1->z;
}

static void
rsQuaternionNormalize(rs_quaternion *q) {
    const float len = rsQuaternionDot(q, q);
    if (len != 1) {
        const float recipLen = 1.f / sqrt(len);
        rsQuaternionMultiply(q, recipLen);
    }
}

static void
rsQuaternionSlerp(rs_quaternion *q, const rs_quaternion *q0, const rs_quaternion *q1, float t) {
    if(t <= 0.0f) {
        rsQuaternionSet(q, q0);
        return;
    }
    if(t >= 1.0f) {
        rsQuaternionSet(q, q1);
        return;
    }

    rs_quaternion tempq0, tempq1;
    rsQuaternionSet(&tempq0, q0);
    rsQuaternionSet(&tempq1, q1);

    float angle = rsQuaternionDot(q0, q1);
    if(angle < 0) {
        rsQuaternionMultiply(&tempq0, -1.0f);
        angle *= -1.0f;
    }

    float scale, invScale;
    if (angle + 1.0f > 0.05f) {
        if (1.0f - angle >= 0.05f) {
            float theta = acos(angle);
            float invSinTheta = 1.0f / sin(theta);
            scale = sin(theta * (1.0f - t)) * invSinTheta;
            invScale = sin(theta * t) * invSinTheta;
        }
        else {
            scale = 1.0f - t;
            invScale = t;
        }
    }
    else {
        rsQuaternionSet(&tempq1, tempq0.z, -tempq0.y, tempq0.x, -tempq0.w);
        scale = sin(M_PI * (0.5f - t));
        invScale = sin(M_PI * t);
    }

    rsQuaternionSet(q, tempq0.w*scale + tempq1.w*invScale, tempq0.x*scale + tempq1.x*invScale,
                        tempq0.y*scale + tempq1.y*invScale, tempq0.z*scale + tempq1.z*invScale);
}

static void rsQuaternionGetMatrixUnit(rs_matrix4x4 *m, const rs_quaternion *q) {
    float x2 = 2.0f * q->x * q->x;
    float y2 = 2.0f * q->y * q->y;
    float z2 = 2.0f * q->z * q->z;
    float xy = 2.0f * q->x * q->y;
    float wz = 2.0f * q->w * q->z;
    float xz = 2.0f * q->x * q->z;
    float wy = 2.0f * q->w * q->y;
    float wx = 2.0f * q->w * q->x;
    float yz = 2.0f * q->y * q->z;

    m->m[0] = 1.0f - y2 - z2;
    m->m[1] = xy - wz;
    m->m[2] = xz + wy;
    m->m[3] = 0.0f;

    m->m[4] = xy + wz;
    m->m[5] = 1.0f - x2 - z2;
    m->m[6] = yz - wx;
    m->m[7] = 0.0f;

    m->m[8] = xz - wy;
    m->m[9] = yz - wx;
    m->m[10] = 1.0f - x2 - y2;
    m->m[11] = 0.0f;

    m->m[12] = 0.0f;
    m->m[13] = 0.0f;
    m->m[14] = 0.0f;
    m->m[15] = 1.0f;
}

/////////////////////////////////////////////////////
// utility funcs
/////////////////////////////////////////////////////
void __attribute__((overloadable))
rsExtractFrustumPlanes(const rs_matrix4x4 *modelViewProj,
                         float4 *left, float4 *right,
                         float4 *top, float4 *bottom,
                         float4 *near, float4 *far) {
    // x y z w = a b c d in the plane equation
    left->x = modelViewProj->m[3] + modelViewProj->m[0];
    left->y = modelViewProj->m[7] + modelViewProj->m[4];
    left->z = modelViewProj->m[11] + modelViewProj->m[8];
    left->w = modelViewProj->m[15] + modelViewProj->m[12];

    right->x = modelViewProj->m[3] - modelViewProj->m[0];
    right->y = modelViewProj->m[7] - modelViewProj->m[4];
    right->z = modelViewProj->m[11] - modelViewProj->m[8];
    right->w = modelViewProj->m[15] - modelViewProj->m[12];

    top->x = modelViewProj->m[3] - modelViewProj->m[1];
    top->y = modelViewProj->m[7] - modelViewProj->m[5];
    top->z = modelViewProj->m[11] - modelViewProj->m[9];
    top->w = modelViewProj->m[15] - modelViewProj->m[13];

    bottom->x = modelViewProj->m[3] + modelViewProj->m[1];
    bottom->y = modelViewProj->m[7] + modelViewProj->m[5];
    bottom->z = modelViewProj->m[11] + modelViewProj->m[9];
    bottom->w = modelViewProj->m[15] + modelViewProj->m[13];

    near->x = modelViewProj->m[3] + modelViewProj->m[2];
    near->y = modelViewProj->m[7] + modelViewProj->m[6];
    near->z = modelViewProj->m[11] + modelViewProj->m[10];
    near->w = modelViewProj->m[15] + modelViewProj->m[14];

    far->x = modelViewProj->m[3] - modelViewProj->m[2];
    far->y = modelViewProj->m[7] - modelViewProj->m[6];
    far->z = modelViewProj->m[11] - modelViewProj->m[10];
    far->w = modelViewProj->m[15] - modelViewProj->m[14];

    float len = length(left->xyz);
    *left /= len;
    len = length(right->xyz);
    *right /= len;
    len = length(top->xyz);
    *top /= len;
    len = length(bottom->xyz);
    *bottom /= len;
    len = length(near->xyz);
    *near /= len;
    len = length(far->xyz);
    *far /= len;
}

bool __attribute__((overloadable))
rsIsSphereInFrustum(float4 *sphere,
                      float4 *left, float4 *right,
                      float4 *top, float4 *bottom,
                      float4 *near, float4 *far) {

    float distToCenter = dot(left->xyz, sphere->xyz) + left->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(right->xyz, sphere->xyz) + right->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(top->xyz, sphere->xyz) + top->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(bottom->xyz, sphere->xyz) + bottom->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(near->xyz, sphere->xyz) + near->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    distToCenter = dot(far->xyz, sphere->xyz) + far->w;
    if(distToCenter < -sphere->w) {
        return false;
    }
    return true;
}


/////////////////////////////////////////////////////
// int ops
/////////////////////////////////////////////////////

__inline__ static uint __attribute__((overloadable, always_inline)) rsClamp(uint amount, uint low, uint high) {
    return amount < low ? low : (amount > high ? high : amount);
}
__inline__ static int __attribute__((overloadable, always_inline)) rsClamp(int amount, int low, int high) {
    return amount < low ? low : (amount > high ? high : amount);
}
__inline__ static ushort __attribute__((overloadable, always_inline)) rsClamp(ushort amount, ushort low, ushort high) {
    return amount < low ? low : (amount > high ? high : amount);
}
__inline__ static short __attribute__((overloadable, always_inline)) rsClamp(short amount, short low, short high) {
    return amount < low ? low : (amount > high ? high : amount);
}
__inline__ static uchar __attribute__((overloadable, always_inline)) rsClamp(uchar amount, uchar low, uchar high) {
    return amount < low ? low : (amount > high ? high : amount);
}
__inline__ static char __attribute__((overloadable, always_inline)) rsClamp(char amount, char low, char high) {
    return amount < low ? low : (amount > high ? high : amount);
}



#endif

