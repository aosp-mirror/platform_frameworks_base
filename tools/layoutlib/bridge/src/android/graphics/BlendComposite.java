/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/*
 * (non-Javadoc)
 * The class is adapted from a demo tool for Blending Modes written by
 * Romain Guy (romainguy@android.com). The tool is available at
 * http://www.curious-creature.org/2006/09/20/new-blendings-modes-for-java2d/
 *
 * This class has been adapted for applying color filters. When applying color filters, the src
 * image should not extend beyond the dest image, but in our implementation of the filters, it does.
 * To compensate for the effect, we recompute the alpha value of the src image before applying
 * the color filter as it should have been applied.
 */
public final class BlendComposite implements Composite {
    public enum BlendingMode {
        MULTIPLY(),
        SCREEN(),
        DARKEN(),
        LIGHTEN(),
        OVERLAY(),
        ADD();

        private final BlendComposite mComposite;

        BlendingMode() {
            mComposite = new BlendComposite(this);
        }

        BlendComposite getBlendComposite() {
            return mComposite;
        }
    }

    private float alpha;
    private BlendingMode mode;

    private BlendComposite(BlendingMode mode) {
        this(mode, 1.0f);
    }

    private BlendComposite(BlendingMode mode, float alpha) {
        this.mode = mode;
        setAlpha(alpha);
    }

    public static BlendComposite getInstance(BlendingMode mode) {
        return mode.getBlendComposite();
    }

    public static BlendComposite getInstance(BlendingMode mode, float alpha) {
        if (alpha > 0.9999f) {
            return getInstance(mode);
        }
        return new BlendComposite(mode, alpha);
    }

    public float getAlpha() {
        return alpha;
    }

    public BlendingMode getMode() {
        return mode;
    }

    private void setAlpha(float alpha) {
        if (alpha < 0.0f || alpha > 1.0f) {
            assert false : "alpha must be comprised between 0.0f and 1.0f";
            alpha = Math.min(alpha, 1.0f);
            alpha = Math.max(alpha, 0.0f);
        }

        this.alpha = alpha;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(alpha) * 31 + mode.ordinal();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BlendComposite)) {
            return false;
        }

        BlendComposite bc = (BlendComposite) obj;

        return mode == bc.mode && alpha == bc.alpha;
    }

    public CompositeContext createContext(ColorModel srcColorModel,
                                          ColorModel dstColorModel,
                                          RenderingHints hints) {
        return new BlendingContext(this);
    }

    private static final class BlendingContext implements CompositeContext {
        private final Blender blender;
        private final BlendComposite composite;

        private BlendingContext(BlendComposite composite) {
            this.composite = composite;
            this.blender = Blender.getBlenderFor(composite);
        }

        public void dispose() {
        }

        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            if (src.getSampleModel().getDataType() != DataBuffer.TYPE_INT ||
                dstIn.getSampleModel().getDataType() != DataBuffer.TYPE_INT ||
                dstOut.getSampleModel().getDataType() != DataBuffer.TYPE_INT) {
                throw new IllegalStateException(
                        "Source and destination must store pixels as INT.");
            }

            int width = Math.min(src.getWidth(), dstIn.getWidth());
            int height = Math.min(src.getHeight(), dstIn.getHeight());

            float alpha = composite.getAlpha();

            int[] srcPixel = new int[4];
            int[] dstPixel = new int[4];
            int[] result = new int[4];
            int[] srcPixels = new int[width];
            int[] dstPixels = new int[width];

            for (int y = 0; y < height; y++) {
                dstIn.getDataElements(0, y, width, 1, dstPixels);
                if (alpha != 0) {
                    src.getDataElements(0, y, width, 1, srcPixels);
                    for (int x = 0; x < width; x++) {
                        // pixels are stored as INT_ARGB
                        // our arrays are [R, G, B, A]
                        int pixel = srcPixels[x];
                        srcPixel[0] = (pixel >> 16) & 0xFF;
                        srcPixel[1] = (pixel >>  8) & 0xFF;
                        srcPixel[2] = (pixel      ) & 0xFF;
                        srcPixel[3] = (pixel >> 24) & 0xFF;

                        pixel = dstPixels[x];
                        dstPixel[0] = (pixel >> 16) & 0xFF;
                        dstPixel[1] = (pixel >>  8) & 0xFF;
                        dstPixel[2] = (pixel      ) & 0xFF;
                        dstPixel[3] = (pixel >> 24) & 0xFF;

                        // ---- Modified from original ----
                        // recompute src pixel for transparency.
                        srcPixel[3] *= dstPixel[3] / 0xFF;
                        // ---- Modification ends ----

                        result = blender.blend(srcPixel, dstPixel, result);

                        // mixes the result with the opacity
                        if (alpha == 1) {
                            dstPixels[x] = (result[3] & 0xFF) << 24 |
                                           (result[0] & 0xFF) << 16 |
                                           (result[1] & 0xFF) <<  8 |
                                           result[2] & 0xFF;
                        } else {
                            dstPixels[x] =
                                    ((int) (dstPixel[3] + (result[3] - dstPixel[3]) * alpha) & 0xFF) << 24 |
                                    ((int) (dstPixel[0] + (result[0] - dstPixel[0]) * alpha) & 0xFF) << 16 |
                                    ((int) (dstPixel[1] + (result[1] - dstPixel[1]) * alpha) & 0xFF) <<  8 |
                                    (int) (dstPixel[2] + (result[2] - dstPixel[2]) * alpha) & 0xFF;
                        }

                    }
            }
                dstOut.setDataElements(0, y, width, 1, dstPixels);
            }
        }
    }

    private static abstract class Blender {
        public abstract int[] blend(int[] src, int[] dst, int[] result);

        public static Blender getBlenderFor(BlendComposite composite) {
            switch (composite.getMode()) {
                case ADD:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 4; i++) {
                                result[i] = Math.min(255, src[i] + dst[i]);
                            }
                            return result;
                        }
                    };
                case DARKEN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = Math.min(src[i], dst[i]);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case LIGHTEN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = Math.max(src[i], dst[i]);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case MULTIPLY:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = (src[i] * dst[i]) >> 8;
                            }
                            result[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
                            return result;
                        }
                    };
                case OVERLAY:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = dst[i] < 128 ? dst[i] * src[i] >> 7 :
                                    255 - ((255 - dst[i]) * (255 - src[i]) >> 7);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case SCREEN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            result[0] = 255 - ((255 - src[0]) * (255 - dst[0]) >> 8);
                            result[1] = 255 - ((255 - src[1]) * (255 - dst[1]) >> 8);
                            result[2] = 255 - ((255 - src[2]) * (255 - dst[2]) >> 8);
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                default:
                    assert false : "Blender not implement for " + composite.getMode().name();

                    // Ignore the blend
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            result[0] = dst[0];
                            result[1] = dst[1];
                            result[2] = dst[2];
                            result[3] = dst[3];
                            return result;
                        }
                    };
            }
        }
    }
}
