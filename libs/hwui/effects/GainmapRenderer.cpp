/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include "GainmapRenderer.h"

#include <SkGainmapShader.h>

#include "Gainmap.h"
#include "Rect.h"
#include "utils/Trace.h"

#ifdef __ANDROID__
#include "include/core/SkColorSpace.h"
#include "include/core/SkImage.h"
#include "include/core/SkShader.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/private/SkGainmapInfo.h"
#include "renderthread/CanvasContext.h"
#include "src/core/SkColorFilterPriv.h"
#include "src/core/SkImageInfoPriv.h"
#include "src/core/SkRuntimeEffectPriv.h"

#include <cmath>
#endif

namespace android::uirenderer {

using namespace renderthread;

float getTargetHdrSdrRatio(const SkColorSpace* destColorspace) {
    // We should always have a known destination colorspace. If we don't we must be in some
    // legacy mode where we're lost and also definitely not going to HDR
    if (destColorspace == nullptr) {
        return 1.f;
    }

    constexpr float GenericSdrWhiteNits = 203.f;
    constexpr float maxPQLux = 10000.f;
    constexpr float maxHLGLux = 1000.f;
    skcms_TransferFunction destTF;
    destColorspace->transferFn(&destTF);
    if (skcms_TransferFunction_isPQish(&destTF)) {
        return maxPQLux / GenericSdrWhiteNits;
    } else if (skcms_TransferFunction_isHLGish(&destTF)) {
        return maxHLGLux / GenericSdrWhiteNits;
#ifdef __ANDROID__
    } else if (RenderThread::isCurrent()) {
        CanvasContext* context = CanvasContext::getActiveContext();
        return context ? context->targetSdrHdrRatio() : 1.f;
#endif
    }
    return 1.f;
}

void DrawGainmapBitmap(SkCanvas* c, const sk_sp<const SkImage>& image, const SkRect& src,
                       const SkRect& dst, const SkSamplingOptions& sampling, const SkPaint* paint,
                       SkCanvas::SrcRectConstraint constraint,
                       const sk_sp<const SkImage>& gainmapImage, const SkGainmapInfo& gainmapInfo) {
    ATRACE_CALL();
#ifdef __ANDROID__
    auto destColorspace = c->imageInfo().refColorSpace();
    float targetSdrHdrRatio = getTargetHdrSdrRatio(destColorspace.get());
    if (targetSdrHdrRatio > 1.f && gainmapImage) {
        SkPaint gainmapPaint = *paint;
        float sX = gainmapImage->width() / (float)image->width();
        float sY = gainmapImage->height() / (float)image->height();
        SkRect gainmapSrc = src;
        // TODO: Tweak rounding?
        gainmapSrc.fLeft *= sX;
        gainmapSrc.fRight *= sX;
        gainmapSrc.fTop *= sY;
        gainmapSrc.fBottom *= sY;
        auto shader =
                SkGainmapShader::Make(image, src, sampling, gainmapImage, gainmapSrc, sampling,
                                      gainmapInfo, dst, targetSdrHdrRatio, destColorspace);
        gainmapPaint.setShader(shader);
        c->drawRect(dst, gainmapPaint);
    } else
#endif
        c->drawImageRect(image.get(), src, dst, sampling, paint, constraint);
}

#ifdef __ANDROID__

static constexpr char gGainmapSKSL[] = R"SKSL(
    uniform shader base;
    uniform shader gainmap;
    uniform colorFilter workingSpaceToLinearSrgb;
    uniform half4 logRatioMin;
    uniform half4 logRatioMax;
    uniform half4 gainmapGamma;
    uniform half4 epsilonSdr;
    uniform half4 epsilonHdr;
    uniform half W;
    uniform int gainmapIsAlpha;
    uniform int gainmapIsRed;
    uniform int singleChannel;
    uniform int noGamma;

    half4 toDest(half4 working) {
        half4 ls = workingSpaceToLinearSrgb.eval(working);
        vec3 dest = fromLinearSrgb(ls.rgb);
        return half4(dest.r, dest.g, dest.b, ls.a);
    }

    half4 main(float2 coord) {
        half4 S = base.eval(coord);
        half4 G = gainmap.eval(coord);
        if (gainmapIsAlpha == 1) {
            G = half4(G.a, G.a, G.a, 1.0);
        }
        if (gainmapIsRed == 1) {
            G = half4(G.r, G.r, G.r, 1.0);
        }
        if (singleChannel == 1) {
            half L;
            if (noGamma == 1) {
                L = mix(logRatioMin.r, logRatioMax.r, G.r);
            } else {
                L = mix(logRatioMin.r, logRatioMax.r, pow(G.r, gainmapGamma.r));
            }
            half3 H = (S.rgb + epsilonSdr.rgb) * exp(L * W) - epsilonHdr.rgb;
            return toDest(half4(H.r, H.g, H.b, S.a));
        } else {
            half3 L;
            if (noGamma == 1) {
                L = mix(logRatioMin.rgb, logRatioMax.rgb, G.rgb);
            } else {
                L = mix(logRatioMin.rgb, logRatioMax.rgb, pow(G.rgb, gainmapGamma.rgb));
            }
            half3 H = (S.rgb + epsilonSdr.rgb) * exp(L * W) - epsilonHdr.rgb;
            return toDest(half4(H.r, H.g, H.b, S.a));
        }
    }
)SKSL";

static sk_sp<SkRuntimeEffect> gainmap_apply_effect() {
    static const SkRuntimeEffect* effect = []() -> SkRuntimeEffect* {
        auto buildResult = SkRuntimeEffect::MakeForShader(SkString(gGainmapSKSL), {});
        if (buildResult.effect) {
            return buildResult.effect.release();
        } else {
            LOG_ALWAYS_FATAL("Failed to build gainmap shader: %s", buildResult.errorText.c_str());
        }
    }();
    SkASSERT(effect);
    return sk_ref_sp(effect);
}

static bool all_channels_equal(const SkColor4f& c) {
    return c.fR == c.fG && c.fR == c.fB;
}

class DeferredGainmapShader {
private:
    sk_sp<SkRuntimeEffect> mShader{gainmap_apply_effect()};
    SkRuntimeShaderBuilder mBuilder{mShader};
    SkGainmapInfo mGainmapInfo;
    std::mutex mUniformGuard;

    void setupChildren(const sk_sp<const SkImage>& baseImage,
                       const sk_sp<const SkImage>& gainmapImage, SkTileMode tileModeX,
                       SkTileMode tileModeY, const SkSamplingOptions& samplingOptions) {
        sk_sp<SkColorSpace> baseColorSpace =
                baseImage->colorSpace() ? baseImage->refColorSpace() : SkColorSpace::MakeSRGB();

        // Determine the color space in which the gainmap math is to be applied.
        sk_sp<SkColorSpace> gainmapMathColorSpace = baseColorSpace->makeLinearGamma();

        // Create a color filter to transform from the base image's color space to the color space
        // in which the gainmap is to be applied.
        auto colorXformSdrToGainmap =
                SkColorFilterPriv::MakeColorSpaceXform(baseColorSpace, gainmapMathColorSpace);

        // The base image shader will convert into the color space in which the gainmap is applied.
        auto baseImageShader = baseImage->makeRawShader(tileModeX, tileModeY, samplingOptions)
                                       ->makeWithColorFilter(colorXformSdrToGainmap);

        // The gainmap image shader will ignore any color space that the gainmap has.
        const SkMatrix gainmapRectToDstRect =
                SkMatrix::RectToRect(SkRect::MakeWH(gainmapImage->width(), gainmapImage->height()),
                                     SkRect::MakeWH(baseImage->width(), baseImage->height()));
        auto gainmapImageShader = gainmapImage->makeRawShader(tileModeX, tileModeY, samplingOptions,
                                                              &gainmapRectToDstRect);

        // Create a color filter to transform from the color space in which the gainmap is applied
        // to the intermediate destination color space.
        auto colorXformGainmapToDst = SkColorFilterPriv::MakeColorSpaceXform(
                gainmapMathColorSpace, SkColorSpace::MakeSRGBLinear());

        mBuilder.child("base") = std::move(baseImageShader);
        mBuilder.child("gainmap") = std::move(gainmapImageShader);
        mBuilder.child("workingSpaceToLinearSrgb") = std::move(colorXformGainmapToDst);
    }

    void setupGenericUniforms(const sk_sp<const SkImage>& gainmapImage,
                              const SkGainmapInfo& gainmapInfo) {
        const SkColor4f logRatioMin({std::log(gainmapInfo.fGainmapRatioMin.fR),
                                     std::log(gainmapInfo.fGainmapRatioMin.fG),
                                     std::log(gainmapInfo.fGainmapRatioMin.fB), 1.f});
        const SkColor4f logRatioMax({std::log(gainmapInfo.fGainmapRatioMax.fR),
                                     std::log(gainmapInfo.fGainmapRatioMax.fG),
                                     std::log(gainmapInfo.fGainmapRatioMax.fB), 1.f});
        const int noGamma = gainmapInfo.fGainmapGamma.fR == 1.f &&
                            gainmapInfo.fGainmapGamma.fG == 1.f &&
                            gainmapInfo.fGainmapGamma.fB == 1.f;
        const uint32_t colorTypeFlags = SkColorTypeChannelFlags(gainmapImage->colorType());
        const int gainmapIsAlpha = colorTypeFlags == kAlpha_SkColorChannelFlag;
        const int gainmapIsRed = colorTypeFlags == kRed_SkColorChannelFlag;
        const int singleChannel = all_channels_equal(gainmapInfo.fGainmapGamma) &&
                                  all_channels_equal(gainmapInfo.fGainmapRatioMin) &&
                                  all_channels_equal(gainmapInfo.fGainmapRatioMax) &&
                                  (colorTypeFlags == kGray_SkColorChannelFlag ||
                                   colorTypeFlags == kAlpha_SkColorChannelFlag ||
                                   colorTypeFlags == kRed_SkColorChannelFlag);
        mBuilder.uniform("logRatioMin") = logRatioMin;
        mBuilder.uniform("logRatioMax") = logRatioMax;
        mBuilder.uniform("gainmapGamma") = gainmapInfo.fGainmapGamma;
        mBuilder.uniform("epsilonSdr") = gainmapInfo.fEpsilonSdr;
        mBuilder.uniform("epsilonHdr") = gainmapInfo.fEpsilonHdr;
        mBuilder.uniform("noGamma") = noGamma;
        mBuilder.uniform("singleChannel") = singleChannel;
        mBuilder.uniform("gainmapIsAlpha") = gainmapIsAlpha;
        mBuilder.uniform("gainmapIsRed") = gainmapIsRed;
    }

    sk_sp<const SkData> build(float targetHdrSdrRatio) {
        sk_sp<const SkData> uniforms;
        {
            // If we are called concurrently from multiple threads, we need to guard the call
            // to writableUniforms() which mutates mUniform. This is otherwise safe because
            // writeableUniforms() will make a copy if it's not unique before mutating
            // This can happen if a BitmapShader is used on multiple canvas', such as a
            // software + hardware canvas, which is otherwise valid as SkShader is "immutable"
            std::lock_guard _lock(mUniformGuard);
            // Compute the weight parameter that will be used to blend between the images.
            float W = 0.f;
            if (targetHdrSdrRatio > mGainmapInfo.fDisplayRatioSdr) {
                if (targetHdrSdrRatio < mGainmapInfo.fDisplayRatioHdr) {
                    W = (std::log(targetHdrSdrRatio) -
                         std::log(mGainmapInfo.fDisplayRatioSdr)) /
                        (std::log(mGainmapInfo.fDisplayRatioHdr) -
                         std::log(mGainmapInfo.fDisplayRatioSdr));
                } else {
                    W = 1.f;
                }
            }
            mBuilder.uniform("W") = W;
            uniforms = mBuilder.uniforms();
        }
        return uniforms;
    }

public:
    explicit DeferredGainmapShader(const sk_sp<const SkImage>& image,
                                   const sk_sp<const SkImage>& gainmapImage,
                                   const SkGainmapInfo& gainmapInfo, SkTileMode tileModeX,
                                   SkTileMode tileModeY, const SkSamplingOptions& sampling) {
        mGainmapInfo = gainmapInfo;
        setupChildren(image, gainmapImage, tileModeX, tileModeY, sampling);
        setupGenericUniforms(gainmapImage, gainmapInfo);
    }

    static sk_sp<SkShader> Make(const sk_sp<const SkImage>& image,
                                const sk_sp<const SkImage>& gainmapImage,
                                const SkGainmapInfo& gainmapInfo, SkTileMode tileModeX,
                                SkTileMode tileModeY, const SkSamplingOptions& sampling) {
        auto deferredHandler = std::make_shared<DeferredGainmapShader>(
                image, gainmapImage, gainmapInfo, tileModeX, tileModeY, sampling);
        auto callback =
                [deferredHandler](const SkRuntimeEffectPriv::UniformsCallbackContext& renderContext)
                -> sk_sp<const SkData> {
            return deferredHandler->build(getTargetHdrSdrRatio(renderContext.fDstColorSpace));
        };
        return SkRuntimeEffectPriv::MakeDeferredShader(deferredHandler->mShader.get(), callback,
                                                       deferredHandler->mBuilder.children());
    }
};

sk_sp<SkShader> MakeGainmapShader(const sk_sp<const SkImage>& image,
                                  const sk_sp<const SkImage>& gainmapImage,
                                  const SkGainmapInfo& gainmapInfo, SkTileMode tileModeX,
                                  SkTileMode tileModeY, const SkSamplingOptions& sampling) {
    return DeferredGainmapShader::Make(image, gainmapImage, gainmapInfo, tileModeX, tileModeY,
                                       sampling);
}

#else  // __ANDROID__

sk_sp<SkShader> MakeGainmapShader(const sk_sp<const SkImage>& image,
                                  const sk_sp<const SkImage>& gainmapImage,
                                  const SkGainmapInfo& gainmapInfo, SkTileMode tileModeX,
                                  SkTileMode tileModeY, const SkSamplingOptions& sampling) {
        return nullptr;
}

#endif  // __ANDROID__

}  // namespace android::uirenderer