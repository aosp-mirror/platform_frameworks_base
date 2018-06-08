/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <SkSurface.h>
#include "Matrix.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * An offscreen rendering target used to contain the contents a RenderNode.
 */
struct SkiaLayer {
    sk_sp<SkSurface> layerSurface;
    Matrix4 inverseTransformInWindow;
    bool hasRenderedSinceRepaint = false;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
