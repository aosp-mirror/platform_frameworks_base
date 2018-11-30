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

static inline int NumDistinctRects(const SkCanvas::Lattice& lattice) {
    int xRects;
    if (lattice.fXCount > 0) {
        xRects = (0 == lattice.fXDivs[0]) ? lattice.fXCount : lattice.fXCount + 1;
    } else {
        xRects = 1;
    }

    int yRects;
    if (lattice.fYCount > 0) {
        yRects = (0 == lattice.fYDivs[0]) ? lattice.fYCount : lattice.fYCount + 1;
    } else {
        yRects = 1;
    }
    return xRects * yRects;
}

static inline void SetLatticeFlags(SkCanvas::Lattice* lattice, SkCanvas::Lattice::RectType* flags,
                                   int numFlags, const Res_png_9patch& chunk, SkColor* colors) {
    lattice->fRectTypes = flags;
    lattice->fColors = colors;
    sk_bzero(flags, numFlags * sizeof(SkCanvas::Lattice::RectType));
    sk_bzero(colors, numFlags * sizeof(SkColor));

    bool needPadRow = lattice->fYCount > 0 && 0 == lattice->fYDivs[0];
    bool needPadCol = lattice->fXCount > 0 && 0 == lattice->fXDivs[0];

    int yCount = lattice->fYCount;
    if (needPadRow) {
        // Skip flags for the degenerate first row of rects.
        flags += lattice->fXCount + 1;
        colors += lattice->fXCount + 1;
        yCount--;
    }

    int i = 0;
    bool setFlags = false;
    for (int y = 0; y < yCount + 1; y++) {
        for (int x = 0; x < lattice->fXCount + 1; x++) {
            if (0 == x && needPadCol) {
                // First rect of each column is degenerate, skip the flag.
                flags++;
                colors++;
                continue;
            }

            uint32_t currentColor = chunk.getColors()[i++];
            if (Res_png_9patch::TRANSPARENT_COLOR == currentColor) {
                *flags = SkCanvas::Lattice::kTransparent;
                setFlags = true;
            } else if (Res_png_9patch::NO_COLOR != currentColor) {
                *flags = SkCanvas::Lattice::kFixedColor;
                *colors = currentColor;
                setFlags = true;
            }

            flags++;
            colors++;
        }
    }

    if (!setFlags) {
        lattice->fRectTypes = nullptr;
        lattice->fColors = nullptr;
    }
}

}  // namespace NinePatchUtils
}  // namespace android
