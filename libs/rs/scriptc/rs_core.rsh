#ifndef __RS_CORE_RSH__
#define __RS_CORE_RSH__


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




#endif

