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
#include "renderthread/CanvasContext.h"
#endif

namespace android::uirenderer {

using namespace renderthread;

void DrawGainmapBitmap(SkCanvas* c, const sk_sp<const SkImage>& image, const SkRect& src,
                       const SkRect& dst, const SkSamplingOptions& sampling, const SkPaint* paint,
                       SkCanvas::SrcRectConstraint constraint,
                       const sk_sp<const SkImage>& gainmapImage, const SkGainmapInfo& gainmapInfo) {
    ATRACE_CALL();
#ifdef __ANDROID__
    CanvasContext* context = CanvasContext::getActiveContext();
    float targetSdrHdrRatio = context ? context->targetSdrHdrRatio() : 1.f;
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
        // TODO: Temporary workaround for SkGainmapShader::Make not having a const variant
        sk_sp<SkImage> mutImage = sk_ref_sp(const_cast<SkImage*>(image.get()));
        sk_sp<SkImage> mutGainmap = sk_ref_sp(const_cast<SkImage*>(gainmapImage.get()));
        auto shader = SkGainmapShader::Make(mutImage, src, sampling, mutGainmap, gainmapSrc,
                                            sampling, gainmapInfo, dst, targetSdrHdrRatio,
                                            c->imageInfo().refColorSpace());
        gainmapPaint.setShader(shader);
        c->drawRect(dst, gainmapPaint);
    } else
#endif
        c->drawImageRect(image.get(), src, dst, sampling, paint, constraint);
}

}  // namespace android::uirenderer