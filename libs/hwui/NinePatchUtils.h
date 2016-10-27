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

namespace android {
namespace NinePatchUtils {

static inline void SetLatticeDivs(SkCanvas::Lattice* lattice, const Res_png_9patch& chunk,
        int width, int height) {
    lattice->fXCount = chunk.numXDivs;
    lattice->fYCount = chunk.numYDivs;
    lattice->fXDivs = chunk.getXDivs();
    lattice->fYDivs = chunk.getYDivs();

    // We'll often see ninepatches where the last div is equal to the width or height.
    // This doesn't provide any additional information and is not supported by Skia.
    if (lattice->fXCount > 0 && width == lattice->fXDivs[lattice->fXCount - 1]) {
        lattice->fXCount--;
    }
    if (lattice->fYCount > 0 && height == lattice->fYDivs[lattice->fYCount - 1]) {
        lattice->fYCount--;
    }
}

}; // namespace NinePatchUtils
}; // namespace android
