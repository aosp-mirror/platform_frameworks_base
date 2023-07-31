/*
 * Copyright 2022 The Android Open Source Project
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

#include "Tonemapper.h"

#include <SkRuntimeEffect.h>
#include <log/log.h>
// libshaders only exists on Android devices
#ifdef __ANDROID__
#include <renderthread/CanvasContext.h>
#include <shaders/shaders.h>
#endif

#include "utils/Color.h"

namespace android::uirenderer {

namespace {

// custom tonemapping only exists on Android devices
#ifdef __ANDROID__
class ColorFilterRuntimeEffectBuilder : public SkRuntimeEffectBuilder {
public:
    explicit ColorFilterRuntimeEffectBuilder(sk_sp<SkRuntimeEffect> effect)
            : SkRuntimeEffectBuilder(std::move(effect)) {}

    sk_sp<SkColorFilter> makeColorFilter() {
        return this->effect()->makeColorFilter(this->uniforms());
    }
};

static sk_sp<SkColorFilter> createLinearEffectColorFilter(const shaders::LinearEffect& linearEffect,
                                                          float maxDisplayLuminance,
                                                          float currentDisplayLuminanceNits,
                                                          float maxLuminance) {
    auto shaderString = SkString(shaders::buildLinearEffectSkSL(linearEffect));
    auto [runtimeEffect, error] = SkRuntimeEffect::MakeForColorFilter(std::move(shaderString));
    if (!runtimeEffect) {
        LOG_ALWAYS_FATAL("LinearColorFilter construction error: %s", error.c_str());
    }

    ColorFilterRuntimeEffectBuilder effectBuilder(std::move(runtimeEffect));

    auto colorTransform = android::mat4();
    const auto* context = renderthread::CanvasContext::getActiveContext();
    if (context) {
        const auto ratio = context->targetSdrHdrRatio();
        if (ratio > 1.0f) {
            colorTransform = android::mat4::scale(vec4(ratio, ratio, ratio, 1.f));
        }
    }

    const auto uniforms =
            shaders::buildLinearEffectUniforms(linearEffect, colorTransform, maxDisplayLuminance,
                                               currentDisplayLuminanceNits, maxLuminance);

    for (const auto& uniform : uniforms) {
        effectBuilder.uniform(uniform.name.c_str()).set(uniform.value.data(), uniform.value.size());
    }

    return effectBuilder.makeColorFilter();
}

static ui::Dataspace extractTransfer(ui::Dataspace dataspace) {
    return static_cast<ui::Dataspace>(dataspace & HAL_DATASPACE_TRANSFER_MASK);
}

static bool isHdrDataspace(ui::Dataspace dataspace) {
    const auto transfer = extractTransfer(dataspace);

    return transfer == ui::Dataspace::TRANSFER_ST2084 || transfer == ui::Dataspace::TRANSFER_HLG;
}

static ui::Dataspace getDataspace(const SkImageInfo& image) {
    return static_cast<ui::Dataspace>(
            ColorSpaceToADataSpace(image.colorSpace(), image.colorType()));
}
#endif

}  // namespace

// Given a source and destination image info, and the max content luminance, generate a tonemaping
// shader and tag it on the supplied paint.
void tonemapPaint(const SkImageInfo& source, const SkImageInfo& destination, float maxLuminanceNits,
                  SkPaint& paint) {
// custom tonemapping only exists on Android devices
#ifdef __ANDROID__
    const auto sourceDataspace = getDataspace(source);
    const auto destinationDataspace = getDataspace(destination);

    if (extractTransfer(sourceDataspace) != extractTransfer(destinationDataspace) &&
        (isHdrDataspace(sourceDataspace) || isHdrDataspace(destinationDataspace))) {
        const auto effect = shaders::LinearEffect{
                .inputDataspace = sourceDataspace,
                .outputDataspace = destinationDataspace,
                .undoPremultipliedAlpha = source.alphaType() == kPremul_SkAlphaType,
                .type = shaders::LinearEffect::SkSLType::ColorFilter};
        constexpr float kMaxDisplayBrightnessNits = 1000.f;
        constexpr float kCurrentDisplayBrightnessNits = 500.f;
        sk_sp<SkColorFilter> colorFilter = createLinearEffectColorFilter(
                effect, kMaxDisplayBrightnessNits, kCurrentDisplayBrightnessNits, maxLuminanceNits);

        if (paint.getColorFilter()) {
            paint.setColorFilter(SkColorFilters::Compose(paint.refColorFilter(), colorFilter));
        } else {
            paint.setColorFilter(colorFilter);
        }
    }
#else
    return;
#endif
}

}  // namespace android::uirenderer
