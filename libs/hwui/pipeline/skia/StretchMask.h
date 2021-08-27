/*
 * Copyright (C) 2021 The Android Open Source Project
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
#pragma once

#include "GrRecordingContext.h"
#include <effects/StretchEffect.h>
#include <SkSurface.h>
#include "SkiaDisplayList.h"

namespace android::uirenderer {

/**
 * Helper class used to create/cache an SkSurface instance
 * to create a mask that is used to draw a stretched hole punch
 */
class StretchMask {
 public:
  /**
   * Release the current surface used for the stretch mask
   */
  void clear() {
      mMaskSurface = nullptr;
  }

  /**
   * Reset the dirty flag to re-create the stretch mask on the next draw
   * pass
   */
  void markDirty() {
      mIsDirty = true;
  }

  /**
   * Draws the stretch mask into the given target canvas
   * @param context GrRecordingContext used to create the surface if necessary
   * @param stretch StretchEffect to apply to the mask
   * @param bounds Target bounds to draw into the given canvas
   * @param displayList List of drawing commands to render into the stretch mask
   * @param canvas Target canvas to draw the mask into
   */
  void draw(GrRecordingContext* context,
            const StretchEffect& stretch, const SkRect& bounds,
            skiapipeline::SkiaDisplayList* displayList, SkCanvas* canvas);
private:
  sk_sp<SkSurface> mMaskSurface;
  bool mIsDirty = true;
};

}
