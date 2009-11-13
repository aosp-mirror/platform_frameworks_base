/*
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "NinePatch"
#define LOG_NDEBUG 1

#include <utils/ResourceTypes.h>
#include <utils/Log.h>

#include "SkBitmap.h"
#include "SkCanvas.h"
#include "SkNinePatch.h"
#include "SkPaint.h"
#include "SkUnPreMultiply.h"

#define USE_TRACE

#ifdef USE_TRACE
    static bool gTrace;
#endif

#include "SkColorPriv.h"

#include <utils/Log.h>

static bool getColor(const SkBitmap& bitmap, int x, int y, SkColor* c) {
    switch (bitmap.getConfig()) {
        case SkBitmap::kARGB_8888_Config:
            *c = SkUnPreMultiply::PMColorToColor(*bitmap.getAddr32(x, y));
            break;
        case SkBitmap::kRGB_565_Config:
            *c = SkPixel16ToPixel32(*bitmap.getAddr16(x, y));
            break;
        case SkBitmap::kARGB_4444_Config:
            *c = SkUnPreMultiply::PMColorToColor(
                                SkPixel4444ToPixel32(*bitmap.getAddr16(x, y)));
            break;
        case SkBitmap::kIndex8_Config: {
            SkColorTable* ctable = bitmap.getColorTable();
            *c = SkUnPreMultiply::PMColorToColor(
                                            (*ctable)[*bitmap.getAddr8(x, y)]);
            break;
        }
        default:
            return false;
    }
    return true;
}

static SkColor modAlpha(SkColor c, int alpha) {
    int scale = alpha + (alpha >> 7);
    int a = SkColorGetA(c) * scale >> 8;
    return SkColorSetA(c, a);
}

static void drawStretchyPatch(SkCanvas* canvas, SkIRect& src, const SkRect& dst,
                              const SkBitmap& bitmap, const SkPaint& paint,
                              SkColor initColor, uint32_t colorHint,
                              bool hasXfer) {
    if (colorHint !=  android::Res_png_9patch::NO_COLOR) {
        ((SkPaint*)&paint)->setColor(modAlpha(colorHint, paint.getAlpha()));
        canvas->drawRect(dst, paint);
        ((SkPaint*)&paint)->setColor(initColor);
    } else if (src.width() == 1 && src.height() == 1) {
        SkColor c;
        if (!getColor(bitmap, src.fLeft, src.fTop, &c)) {
            goto SLOW_CASE;
        }
        if (0 != c || hasXfer) {
            SkColor prev = paint.getColor();
            ((SkPaint*)&paint)->setColor(c);
            canvas->drawRect(dst, paint);
            ((SkPaint*)&paint)->setColor(prev);
        }
    } else {
    SLOW_CASE:
        canvas->drawBitmapRect(bitmap, &src, dst, &paint);
    }
}

SkScalar calculateStretch(SkScalar boundsLimit, SkScalar startingPoint,
                          int srcSpace, int numStrechyPixelsRemaining,
                          int numFixedPixelsRemaining) {
    SkScalar spaceRemaining = boundsLimit - startingPoint;
    SkScalar stretchySpaceRemaining =
                spaceRemaining - SkIntToScalar(numFixedPixelsRemaining);
    return SkScalarMulDiv(srcSpace, stretchySpaceRemaining,
                          numStrechyPixelsRemaining);
}

void NinePatch_Draw(SkCanvas* canvas, const SkRect& bounds,
                       const SkBitmap& bitmap, const android::Res_png_9patch& chunk,
                       const SkPaint* paint, SkRegion** outRegion) {
    if (canvas && canvas->quickReject(bounds, SkCanvas::kBW_EdgeType)) {
        return;
    }

    SkPaint defaultPaint;
    if (NULL == paint) {
        // matches default dither in NinePatchDrawable.java.
        defaultPaint.setDither(true);
        paint = &defaultPaint;
    }
    
    // if our canvas is GL, draw this as a mesh, which will be faster than
    // in parts (which is faster for raster)
    if (canvas && canvas->getViewport(NULL)) {
        SkNinePatch::DrawMesh(canvas, bounds, bitmap,
                              chunk.xDivs, chunk.numXDivs,
                              chunk.yDivs, chunk.numYDivs,
                              paint);
        return;
    }

#ifdef USE_TRACE
    gTrace = true;
#endif

    SkASSERT(canvas || outRegion);

#ifdef USE_TRACE
    if (canvas) {
        const SkMatrix& m = canvas->getTotalMatrix();
        LOGV("ninepatch [%g %g %g] [%g %g %g]\n",
                 SkScalarToFloat(m[0]), SkScalarToFloat(m[1]), SkScalarToFloat(m[2]),
                 SkScalarToFloat(m[3]), SkScalarToFloat(m[4]), SkScalarToFloat(m[5]));
    }
#endif

#ifdef USE_TRACE
    if (gTrace) {
        LOGV("======== ninepatch bounds [%g %g]\n", SkScalarToFloat(bounds.width()), SkScalarToFloat(bounds.height()));
        LOGV("======== ninepatch paint bm [%d,%d]\n", bitmap.width(), bitmap.height());
        LOGV("======== ninepatch xDivs [%d,%d]\n", chunk.xDivs[0], chunk.xDivs[1]);
        LOGV("======== ninepatch yDivs [%d,%d]\n", chunk.yDivs[0], chunk.yDivs[1]);
    }
#endif

    if (bounds.isEmpty() ||
        bitmap.width() == 0 || bitmap.height() == 0 ||
        (paint && paint->getXfermode() == NULL && paint->getAlpha() == 0))
    {
#ifdef USE_TRACE
        if (gTrace) LOGV("======== abort ninepatch draw\n");
#endif
        return;
    }
    
    // should try a quick-reject test before calling lockPixels 

    SkAutoLockPixels alp(bitmap);
    // after the lock, it is valid to check getPixels()
    if (bitmap.getPixels() == NULL)
        return;

    const bool hasXfer = paint->getXfermode() != NULL;
    SkRect      dst;
    SkIRect     src;

    const int32_t x0 = chunk.xDivs[0];
    const int32_t y0 = chunk.yDivs[0];
    const SkColor initColor = ((SkPaint*)paint)->getColor();
    const uint8_t numXDivs = chunk.numXDivs;
    const uint8_t numYDivs = chunk.numYDivs;
    int i;
    int j;
    int colorIndex = 0;
    uint32_t color;
    bool xIsStretchable;
    const bool initialXIsStretchable =  (x0 == 0);
    bool yIsStretchable = (y0 == 0);
    const int bitmapWidth = bitmap.width();
    const int bitmapHeight = bitmap.height();

    SkScalar* dstRights = (SkScalar*) alloca((numXDivs + 1) * sizeof(SkScalar));
    bool dstRightsHaveBeenCached = false;

    int numStretchyXPixelsRemaining = 0;
    for (i = 0; i < numXDivs; i += 2) {
        numStretchyXPixelsRemaining += chunk.xDivs[i + 1] - chunk.xDivs[i];
    }
    int numFixedXPixelsRemaining = bitmapWidth - numStretchyXPixelsRemaining;
    int numStretchyYPixelsRemaining = 0;
    for (i = 0; i < numYDivs; i += 2) {
        numStretchyYPixelsRemaining += chunk.yDivs[i + 1] - chunk.yDivs[i];
    }
    int numFixedYPixelsRemaining = bitmapHeight - numStretchyYPixelsRemaining;

#ifdef USE_TRACE
    LOGV("NinePatch [%d %d] bounds [%g %g %g %g] divs [%d %d]\n",
             bitmap.width(), bitmap.height(),
             SkScalarToFloat(bounds.fLeft), SkScalarToFloat(bounds.fTop),
             SkScalarToFloat(bounds.width()), SkScalarToFloat(bounds.height()),
             numXDivs, numYDivs);
#endif

    src.fTop = 0;
    dst.fTop = bounds.fTop;
    // The first row always starts with the top being at y=0 and the bottom
    // being either yDivs[1] (if yDivs[0]=0) of yDivs[0].  In the former case
    // the first row is stretchable along the Y axis, otherwise it is fixed.
    // The last row always ends with the bottom being bitmap.height and the top
    // being either yDivs[numYDivs-2] (if yDivs[numYDivs-1]=bitmap.height) or
    // yDivs[numYDivs-1]. In the former case the last row is stretchable along
    // the Y axis, otherwise it is fixed.
    //
    // The first and last columns are similarly treated with respect to the X
    // axis.
    //
    // The above is to help explain some of the special casing that goes on the
    // code below.

    // The initial yDiv and whether the first row is considered stretchable or
    // not depends on whether yDiv[0] was zero or not.
    for (j = yIsStretchable ? 1 : 0;
          j <= numYDivs && src.fTop < bitmapHeight;
          j++, yIsStretchable = !yIsStretchable) {
        src.fLeft = 0;
        dst.fLeft = bounds.fLeft;
        if (j == numYDivs) {
            src.fBottom = bitmapHeight;
            dst.fBottom = bounds.fBottom;
        } else {
            src.fBottom = chunk.yDivs[j];
            const int srcYSize = src.fBottom - src.fTop;
            if (yIsStretchable) {
                dst.fBottom = dst.fTop + calculateStretch(bounds.fBottom, dst.fTop,
                                                          srcYSize,
                                                          numStretchyYPixelsRemaining,
                                                          numFixedYPixelsRemaining);
                numStretchyYPixelsRemaining -= srcYSize;
            } else {
                dst.fBottom = dst.fTop + SkIntToScalar(srcYSize);
                numFixedYPixelsRemaining -= srcYSize;
            }
        }

        xIsStretchable = initialXIsStretchable;
        // The initial xDiv and whether the first column is considered
        // stretchable or not depends on whether xDiv[0] was zero or not.
        for (i = xIsStretchable ? 1 : 0;
              i <= numXDivs && src.fLeft < bitmapWidth;
              i++, xIsStretchable = !xIsStretchable) {
            color = chunk.colors[colorIndex++];
            if (i == numXDivs) {
                src.fRight = bitmapWidth;
                dst.fRight = bounds.fRight;
            } else {
                src.fRight = chunk.xDivs[i];
                if (dstRightsHaveBeenCached) {
                    dst.fRight = dstRights[i];
                } else {
                    const int srcXSize = src.fRight - src.fLeft;
                    if (xIsStretchable) {
                        dst.fRight = dst.fLeft + calculateStretch(bounds.fRight, dst.fLeft,
                                                                  srcXSize,
                                                                  numStretchyXPixelsRemaining,
                                                                  numFixedXPixelsRemaining);
                        numStretchyXPixelsRemaining -= srcXSize;
                    } else {
                        dst.fRight = dst.fLeft + SkIntToScalar(srcXSize);
                        numFixedXPixelsRemaining -= srcXSize;
                    }
                    dstRights[i] = dst.fRight;
                }
            }
            // If this horizontal patch is too small to be displayed, leave
            // the destination left edge where it is and go on to the next patch
            // in the source.
            if (src.fLeft >= src.fRight) {
                src.fLeft = src.fRight;
                continue;
            }
            // Make sure that we actually have room to draw any bits
            if (dst.fRight <= dst.fLeft || dst.fBottom <= dst.fTop) {
                goto nextDiv;
            }
            // If this patch is transparent, skip and don't draw.
            if (color == android::Res_png_9patch::TRANSPARENT_COLOR && !hasXfer) {
                if (outRegion) {
                    if (*outRegion == NULL) {
                        *outRegion = new SkRegion();
                    }
                    SkIRect idst;
                    dst.round(&idst);
                    //LOGI("Adding trans rect: (%d,%d)-(%d,%d)\n",
                    //     idst.fLeft, idst.fTop, idst.fRight, idst.fBottom);
                    (*outRegion)->op(idst, SkRegion::kUnion_Op);
                }
                goto nextDiv;
            }
            if (canvas) {
#ifdef USE_TRACE
                LOGV("-- src [%d %d %d %d] dst [%g %g %g %g]\n",
                         src.fLeft, src.fTop, src.width(), src.height(),
                         SkScalarToFloat(dst.fLeft), SkScalarToFloat(dst.fTop),
                         SkScalarToFloat(dst.width()), SkScalarToFloat(dst.height()));
                if (2 == src.width() && SkIntToScalar(5) == dst.width()) {
                    LOGV("--- skip patch\n");
                }
#endif
                drawStretchyPatch(canvas, src, dst, bitmap, *paint, initColor,
                                  color, hasXfer);
            }

nextDiv:
            src.fLeft = src.fRight;
            dst.fLeft = dst.fRight;
        }
        src.fTop = src.fBottom;
        dst.fTop = dst.fBottom;
        dstRightsHaveBeenCached = true;
    }
}
