/*
 * Copyright (C) 2019 The Android Open Source Project
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
// This is copied from framework/native/libs/ui in order not to include libui in host build

#include <ui/ColorSpace.h>

using namespace std::placeholders;

namespace android {

static constexpr float linearResponse(float v) {
    return v;
}

static constexpr float rcpResponse(float x, const ColorSpace::TransferParameters& p) {
    return x >= p.d * p.c ? (std::pow(x, 1.0f / p.g) - p.b) / p.a : x / p.c;
}

static constexpr float response(float x, const ColorSpace::TransferParameters& p) {
    return x >= p.d ? std::pow(p.a * x + p.b, p.g) : p.c * x;
}

static constexpr float rcpFullResponse(float x, const ColorSpace::TransferParameters& p) {
    return x >= p.d * p.c ? (std::pow(x - p.e, 1.0f / p.g) - p.b) / p.a : (x - p.f) / p.c;
}

static constexpr float fullResponse(float x, const ColorSpace::TransferParameters& p) {
    return x >= p.d ? std::pow(p.a * x + p.b, p.g) + p.e : p.c * x + p.f;
}

static float absRcpResponse(float x, float g,float a, float b, float c, float d) {
    float xx = std::abs(x);
    return std::copysign(xx >= d * c ? (std::pow(xx, 1.0f / g) - b) / a : xx / c, x);
}

static float absResponse(float x, float g, float a, float b, float c, float d) {
   float xx = std::abs(x);
   return std::copysign(xx >= d ? std::pow(a * xx + b, g) : c * xx, x);
}

static float safePow(float x, float e) {
    return powf(x < 0.0f ? 0.0f : x, e);
}

static ColorSpace::transfer_function toOETF(const ColorSpace::TransferParameters& parameters) {
    if (parameters.e == 0.0f && parameters.f == 0.0f) {
        return std::bind(rcpResponse, _1, parameters);
    }
    return std::bind(rcpFullResponse, _1, parameters);
}

static ColorSpace::transfer_function toEOTF( const ColorSpace::TransferParameters& parameters) {
    if (parameters.e == 0.0f && parameters.f == 0.0f) {
        return std::bind(response, _1, parameters);
    }
    return std::bind(fullResponse, _1, parameters);
}

static ColorSpace::transfer_function toOETF(float gamma) {
    if (gamma == 1.0f) {
        return linearResponse;
    }
    return std::bind(safePow, _1, 1.0f / gamma);
}

static ColorSpace::transfer_function toEOTF(float gamma) {
    if (gamma == 1.0f) {
        return linearResponse;
    }
    return std::bind(safePow, _1, gamma);
}

static constexpr std::array<float2, 3> computePrimaries(const mat3& rgbToXYZ) {
    float3 r(rgbToXYZ * float3{1, 0, 0});
    float3 g(rgbToXYZ * float3{0, 1, 0});
    float3 b(rgbToXYZ * float3{0, 0, 1});

    return {{r.xy / dot(r, float3{1}),
             g.xy / dot(g, float3{1}),
             b.xy / dot(b, float3{1})}};
}

static constexpr float2 computeWhitePoint(const mat3& rgbToXYZ) {
    float3 w(rgbToXYZ * float3{1});
    return w.xy / dot(w, float3{1});
}

ColorSpace::ColorSpace(
        const std::string& name,
        const mat3& rgbToXYZ,
        transfer_function OETF,
        transfer_function EOTF,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(rgbToXYZ)
        , mXYZtoRGB(inverse(rgbToXYZ))
        , mOETF(std::move(OETF))
        , mEOTF(std::move(EOTF))
        , mClamper(std::move(clamper))
        , mPrimaries(computePrimaries(rgbToXYZ))
        , mWhitePoint(computeWhitePoint(rgbToXYZ)) {
}

ColorSpace::ColorSpace(
        const std::string& name,
        const mat3& rgbToXYZ,
        const TransferParameters parameters,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(rgbToXYZ)
        , mXYZtoRGB(inverse(rgbToXYZ))
        , mParameters(parameters)
        , mOETF(toOETF(mParameters))
        , mEOTF(toEOTF(mParameters))
        , mClamper(std::move(clamper))
        , mPrimaries(computePrimaries(rgbToXYZ))
        , mWhitePoint(computeWhitePoint(rgbToXYZ)) {
}

ColorSpace::ColorSpace(
        const std::string& name,
        const mat3& rgbToXYZ,
        float gamma,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(rgbToXYZ)
        , mXYZtoRGB(inverse(rgbToXYZ))
        , mParameters({gamma, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f})
        , mOETF(toOETF(gamma))
        , mEOTF(toEOTF(gamma))
        , mClamper(std::move(clamper))
        , mPrimaries(computePrimaries(rgbToXYZ))
        , mWhitePoint(computeWhitePoint(rgbToXYZ)) {
}

ColorSpace::ColorSpace(
        const std::string& name,
        const std::array<float2, 3>& primaries,
        const float2& whitePoint,
        transfer_function OETF,
        transfer_function EOTF,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(computeXYZMatrix(primaries, whitePoint))
        , mXYZtoRGB(inverse(mRGBtoXYZ))
        , mOETF(std::move(OETF))
        , mEOTF(std::move(EOTF))
        , mClamper(std::move(clamper))
        , mPrimaries(primaries)
        , mWhitePoint(whitePoint) {
}

ColorSpace::ColorSpace(
        const std::string& name,
        const std::array<float2, 3>& primaries,
        const float2& whitePoint,
        const TransferParameters parameters,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(computeXYZMatrix(primaries, whitePoint))
        , mXYZtoRGB(inverse(mRGBtoXYZ))
        , mParameters(parameters)
        , mOETF(toOETF(mParameters))
        , mEOTF(toEOTF(mParameters))
        , mClamper(std::move(clamper))
        , mPrimaries(primaries)
        , mWhitePoint(whitePoint) {
}

ColorSpace::ColorSpace(
        const std::string& name,
        const std::array<float2, 3>& primaries,
        const float2& whitePoint,
        float gamma,
        clamping_function clamper) noexcept
        : mName(name)
        , mRGBtoXYZ(computeXYZMatrix(primaries, whitePoint))
        , mXYZtoRGB(inverse(mRGBtoXYZ))
        , mParameters({gamma, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f})
        , mOETF(toOETF(gamma))
        , mEOTF(toEOTF(gamma))
        , mClamper(std::move(clamper))
        , mPrimaries(primaries)
        , mWhitePoint(whitePoint) {
}

constexpr mat3 ColorSpace::computeXYZMatrix(
        const std::array<float2, 3>& primaries, const float2& whitePoint) {
    const float2& R = primaries[0];
    const float2& G = primaries[1];
    const float2& B = primaries[2];
    const float2& W = whitePoint;

    float oneRxRy = (1 - R.x) / R.y;
    float oneGxGy = (1 - G.x) / G.y;
    float oneBxBy = (1 - B.x) / B.y;
    float oneWxWy = (1 - W.x) / W.y;

    float RxRy = R.x / R.y;
    float GxGy = G.x / G.y;
    float BxBy = B.x / B.y;
    float WxWy = W.x / W.y;

    float BY =
            ((oneWxWy - oneRxRy) * (GxGy - RxRy) - (WxWy - RxRy) * (oneGxGy - oneRxRy)) /
            ((oneBxBy - oneRxRy) * (GxGy - RxRy) - (BxBy - RxRy) * (oneGxGy - oneRxRy));
    float GY = (WxWy - RxRy - BY * (BxBy - RxRy)) / (GxGy - RxRy);
    float RY = 1 - GY - BY;

    float RYRy = RY / R.y;
    float GYGy = GY / G.y;
    float BYBy = BY / B.y;

    return {
        float3{RYRy * R.x, RY, RYRy * (1 - R.x - R.y)},
        float3{GYGy * G.x, GY, GYGy * (1 - G.x - G.y)},
        float3{BYBy * B.x, BY, BYBy * (1 - B.x - B.y)}
    };
}

const ColorSpace ColorSpace::sRGB() {
    return {
        "sRGB IEC61966-2.1",
        {{float2{0.640f, 0.330f}, {0.300f, 0.600f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f},
        {2.4f, 1 / 1.055f, 0.055f / 1.055f, 1 / 12.92f, 0.04045f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::linearSRGB() {
    return {
        "sRGB IEC61966-2.1 (Linear)",
        {{float2{0.640f, 0.330f}, {0.300f, 0.600f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f}
    };
}

const ColorSpace ColorSpace::extendedSRGB() {
    return {
        "scRGB-nl IEC 61966-2-2:2003",
        {{float2{0.640f, 0.330f}, {0.300f, 0.600f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f},
        std::bind(absRcpResponse, _1, 2.4f, 1 / 1.055f, 0.055f / 1.055f, 1 / 12.92f, 0.04045f),
        std::bind(absResponse,    _1, 2.4f, 1 / 1.055f, 0.055f / 1.055f, 1 / 12.92f, 0.04045f),
        std::bind(clamp<float>, _1, -0.799f, 2.399f)
    };
}

const ColorSpace ColorSpace::linearExtendedSRGB() {
    return {
        "scRGB IEC 61966-2-2:2003",
        {{float2{0.640f, 0.330f}, {0.300f, 0.600f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f},
        1.0f,
        std::bind(clamp<float>, _1, -0.5f, 7.499f)
    };
}

const ColorSpace ColorSpace::NTSC() {
    return {
        "NTSC (1953)",
        {{float2{0.67f, 0.33f}, {0.21f, 0.71f}, {0.14f, 0.08f}}},
        {0.310f, 0.316f},
        {1 / 0.45f, 1 / 1.099f, 0.099f / 1.099f, 1 / 4.5f, 0.081f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::BT709() {
    return {
        "Rec. ITU-R BT.709-5",
        {{float2{0.640f, 0.330f}, {0.300f, 0.600f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f},
        {1 / 0.45f, 1 / 1.099f, 0.099f / 1.099f, 1 / 4.5f, 0.081f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::BT2020() {
    return {
        "Rec. ITU-R BT.2020-1",
        {{float2{0.708f, 0.292f}, {0.170f, 0.797f}, {0.131f, 0.046f}}},
        {0.3127f, 0.3290f},
        {1 / 0.45f, 1 / 1.099f, 0.099f / 1.099f, 1 / 4.5f, 0.081f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::AdobeRGB() {
    return {
        "Adobe RGB (1998)",
        {{float2{0.64f, 0.33f}, {0.21f, 0.71f}, {0.15f, 0.06f}}},
        {0.3127f, 0.3290f},
        2.2f
    };
}

const ColorSpace ColorSpace::ProPhotoRGB() {
    return {
        "ROMM RGB ISO 22028-2:2013",
        {{float2{0.7347f, 0.2653f}, {0.1596f, 0.8404f}, {0.0366f, 0.0001f}}},
        {0.34567f, 0.35850f},
        {1.8f, 1.0f, 0.0f, 1 / 16.0f, 0.031248f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::DisplayP3() {
    return {
        "Display P3",
        {{float2{0.680f, 0.320f}, {0.265f, 0.690f}, {0.150f, 0.060f}}},
        {0.3127f, 0.3290f},
        {2.4f, 1 / 1.055f, 0.055f / 1.055f, 1 / 12.92f, 0.039f, 0.0f, 0.0f}
    };
}

const ColorSpace ColorSpace::DCIP3() {
    return {
        "SMPTE RP 431-2-2007 DCI (P3)",
        {{float2{0.680f, 0.320f}, {0.265f, 0.690f}, {0.150f, 0.060f}}},
        {0.314f, 0.351f},
        2.6f
    };
}

const ColorSpace ColorSpace::ACES() {
    return {
        "SMPTE ST 2065-1:2012 ACES",
        {{float2{0.73470f, 0.26530f}, {0.0f, 1.0f}, {0.00010f, -0.0770f}}},
        {0.32168f, 0.33767f},
        1.0f,
        std::bind(clamp<float>, _1, -65504.0f, 65504.0f)
    };
}

const ColorSpace ColorSpace::ACEScg() {
    return {
        "Academy S-2014-004 ACEScg",
        {{float2{0.713f, 0.293f}, {0.165f, 0.830f}, {0.128f, 0.044f}}},
        {0.32168f, 0.33767f},
        1.0f,
        std::bind(clamp<float>, _1, -65504.0f, 65504.0f)
    };
}

std::unique_ptr<float3[]> ColorSpace::createLUT(uint32_t size, const ColorSpace& src,
                                                const ColorSpace& dst) {
    size = clamp(size, 2u, 256u);
    float m = 1.0f / float(size - 1);

    std::unique_ptr<float3[]> lut(new float3[size * size * size]);
    float3* data = lut.get();

    ColorSpaceConnector connector(src, dst);

    for (uint32_t z = 0; z < size; z++) {
        for (int32_t y = int32_t(size - 1); y >= 0; y--) {
            for (uint32_t x = 0; x < size; x++) {
                *data++ = connector.transform({x * m, y * m, z * m});
            }
        }
    }

    return lut;
}

static const float2 ILLUMINANT_D50_XY = {0.34567f, 0.35850f};
static const float3 ILLUMINANT_D50_XYZ = {0.964212f, 1.0f, 0.825188f};
static const mat3 BRADFORD = mat3{
    float3{ 0.8951f, -0.7502f,  0.0389f},
    float3{ 0.2664f,  1.7135f, -0.0685f},
    float3{-0.1614f,  0.0367f,  1.0296f}
};

static mat3 adaptation(const mat3& matrix, const float3& srcWhitePoint, const float3& dstWhitePoint) {
    float3 srcLMS = matrix * srcWhitePoint;
    float3 dstLMS = matrix * dstWhitePoint;
    return inverse(matrix) * mat3{dstLMS / srcLMS} * matrix;
}

ColorSpaceConnector::ColorSpaceConnector(
        const ColorSpace& src,
        const ColorSpace& dst) noexcept
        : mSource(src)
        , mDestination(dst) {

    if (all(lessThan(abs(src.getWhitePoint() - dst.getWhitePoint()), float2{1e-3f}))) {
        mTransform = dst.getXYZtoRGB() * src.getRGBtoXYZ();
    } else {
        mat3 rgbToXYZ(src.getRGBtoXYZ());
        mat3 xyzToRGB(dst.getXYZtoRGB());

        float3 srcXYZ = ColorSpace::XYZ(float3{src.getWhitePoint(), 1});
        float3 dstXYZ = ColorSpace::XYZ(float3{dst.getWhitePoint(), 1});

        if (any(greaterThan(abs(src.getWhitePoint() - ILLUMINANT_D50_XY), float2{1e-3f}))) {
            rgbToXYZ = adaptation(BRADFORD, srcXYZ, ILLUMINANT_D50_XYZ) * src.getRGBtoXYZ();
        }

        if (any(greaterThan(abs(dst.getWhitePoint() - ILLUMINANT_D50_XY), float2{1e-3f}))) {
            xyzToRGB = inverse(adaptation(BRADFORD, dstXYZ, ILLUMINANT_D50_XYZ) * dst.getRGBtoXYZ());
        }

        mTransform = xyzToRGB * rgbToXYZ;
    }
}

}; // namespace android
