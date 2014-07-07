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
 */
public final class BlendComposite implements Composite {
    public enum BlendingMode {
        NORMAL,
        AVERAGE,
        MULTIPLY,
        SCREEN,
        DARKEN,
        LIGHTEN,
        OVERLAY,
        HARD_LIGHT,
        SOFT_LIGHT,
        DIFFERENCE,
        NEGATION,
        EXCLUSION,
        COLOR_DODGE,
        INVERSE_COLOR_DODGE,
        SOFT_DODGE,
        COLOR_BURN,
        INVERSE_COLOR_BURN,
        SOFT_BURN,
        REFLECT,
        GLOW,
        FREEZE,
        HEAT,
        ADD,
        SUBTRACT,
        STAMP,
        RED,
        GREEN,
        BLUE,
        HUE,
        SATURATION,
        COLOR,
        LUMINOSITY
    }

    public static final BlendComposite Normal = new BlendComposite(BlendingMode.NORMAL);
    public static final BlendComposite Average = new BlendComposite(BlendingMode.AVERAGE);
    public static final BlendComposite Multiply = new BlendComposite(BlendingMode.MULTIPLY);
    public static final BlendComposite Screen = new BlendComposite(BlendingMode.SCREEN);
    public static final BlendComposite Darken = new BlendComposite(BlendingMode.DARKEN);
    public static final BlendComposite Lighten = new BlendComposite(BlendingMode.LIGHTEN);
    public static final BlendComposite Overlay = new BlendComposite(BlendingMode.OVERLAY);
    public static final BlendComposite HardLight = new BlendComposite(BlendingMode.HARD_LIGHT);
    public static final BlendComposite SoftLight = new BlendComposite(BlendingMode.SOFT_LIGHT);
    public static final BlendComposite Difference = new BlendComposite(BlendingMode.DIFFERENCE);
    public static final BlendComposite Negation = new BlendComposite(BlendingMode.NEGATION);
    public static final BlendComposite Exclusion = new BlendComposite(BlendingMode.EXCLUSION);
    public static final BlendComposite ColorDodge = new BlendComposite(BlendingMode.COLOR_DODGE);
    public static final BlendComposite InverseColorDodge = new BlendComposite(BlendingMode.INVERSE_COLOR_DODGE);
    public static final BlendComposite SoftDodge = new BlendComposite(BlendingMode.SOFT_DODGE);
    public static final BlendComposite ColorBurn = new BlendComposite(BlendingMode.COLOR_BURN);
    public static final BlendComposite InverseColorBurn = new BlendComposite(BlendingMode.INVERSE_COLOR_BURN);
    public static final BlendComposite SoftBurn = new BlendComposite(BlendingMode.SOFT_BURN);
    public static final BlendComposite Reflect = new BlendComposite(BlendingMode.REFLECT);
    public static final BlendComposite Glow = new BlendComposite(BlendingMode.GLOW);
    public static final BlendComposite Freeze = new BlendComposite(BlendingMode.FREEZE);
    public static final BlendComposite Heat = new BlendComposite(BlendingMode.HEAT);
    public static final BlendComposite Add = new BlendComposite(BlendingMode.ADD);
    public static final BlendComposite Subtract = new BlendComposite(BlendingMode.SUBTRACT);
    public static final BlendComposite Stamp = new BlendComposite(BlendingMode.STAMP);
    public static final BlendComposite Red = new BlendComposite(BlendingMode.RED);
    public static final BlendComposite Green = new BlendComposite(BlendingMode.GREEN);
    public static final BlendComposite Blue = new BlendComposite(BlendingMode.BLUE);
    public static final BlendComposite Hue = new BlendComposite(BlendingMode.HUE);
    public static final BlendComposite Saturation = new BlendComposite(BlendingMode.SATURATION);
    public static final BlendComposite Color = new BlendComposite(BlendingMode.COLOR);
    public static final BlendComposite Luminosity = new BlendComposite(BlendingMode.LUMINOSITY);

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
        return new BlendComposite(mode);
    }

    public static BlendComposite getInstance(BlendingMode mode, float alpha) {
        return new BlendComposite(mode, alpha);
    }

    public BlendComposite derive(BlendingMode mode) {
        return this.mode == mode ? this : new BlendComposite(mode, getAlpha());
    }

    public BlendComposite derive(float alpha) {
        return this.alpha == alpha ? this : new BlendComposite(getMode(), alpha);
    }

    public float getAlpha() {
        return alpha;
    }

    public BlendingMode getMode() {
        return mode;
    }

    private void setAlpha(float alpha) {
        if (alpha < 0.0f || alpha > 1.0f) {
            throw new IllegalArgumentException(
                    "alpha must be comprised between 0.0f and 1.0f");
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

        if (mode != bc.mode) {
            return false;
        }

        return alpha == bc.alpha;
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

        private static void RGBtoHSL(int r, int g, int b, float[] hsl) {
            float var_R = (r / 255f);
            float var_G = (g / 255f);
            float var_B = (b / 255f);

            float var_Min;
            float var_Max;
            float del_Max;

            if (var_R > var_G) {
                var_Min = var_G;
                var_Max = var_R;
            } else {
                var_Min = var_R;
                var_Max = var_G;
            }
            if (var_B > var_Max) {
                var_Max = var_B;
            }
            if (var_B < var_Min) {
                var_Min = var_B;
            }

            del_Max = var_Max - var_Min;

            float H, S, L;
            L = (var_Max + var_Min) / 2f;

            if (del_Max - 0.01f <= 0.0f) {
                H = 0;
                S = 0;
            } else {
                if (L < 0.5f) {
                    S = del_Max / (var_Max + var_Min);
                } else {
                    S = del_Max / (2 - var_Max - var_Min);
                }

                float del_R = (((var_Max - var_R) / 6f) + (del_Max / 2f)) / del_Max;
                float del_G = (((var_Max - var_G) / 6f) + (del_Max / 2f)) / del_Max;
                float del_B = (((var_Max - var_B) / 6f) + (del_Max / 2f)) / del_Max;

                if (var_R == var_Max) {
                    H = del_B - del_G;
                } else if (var_G == var_Max) {
                    H = (1 / 3f) + del_R - del_B;
                } else {
                    H = (2 / 3f) + del_G - del_R;
                }
                if (H < 0) {
                    H += 1;
                }
                if (H > 1) {
                    H -= 1;
                }
            }

            hsl[0] = H;
            hsl[1] = S;
            hsl[2] = L;
        }

        private static void HSLtoRGB(float h, float s, float l, int[] rgb) {
            int R, G, B;

            if (s - 0.01f <= 0.0f) {
                R = (int) (l * 255.0f);
                G = (int) (l * 255.0f);
                B = (int) (l * 255.0f);
            } else {
                float var_1, var_2;
                if (l < 0.5f) {
                    var_2 = l * (1 + s);
                } else {
                    var_2 = (l + s) - (s * l);
                }
                var_1 = 2 * l - var_2;

                R = (int) (255.0f * hue2RGB(var_1, var_2, h + (1.0f / 3.0f)));
                G = (int) (255.0f * hue2RGB(var_1, var_2, h));
                B = (int) (255.0f * hue2RGB(var_1, var_2, h - (1.0f / 3.0f)));
            }

            rgb[0] = R;
            rgb[1] = G;
            rgb[2] = B;
        }

        private static float hue2RGB(float v1, float v2, float vH) {
            if (vH < 0.0f) {
                vH += 1.0f;
            }
            if (vH > 1.0f) {
                vH -= 1.0f;
            }
            if ((6.0f * vH) < 1.0f) {
                return (v1 + (v2 - v1) * 6.0f * vH);
            }
            if ((2.0f * vH) < 1.0f) {
                return (v2);
            }
            if ((3.0f * vH) < 2.0f) {
                return (v1 + (v2 - v1) * ((2.0f / 3.0f) - vH) * 6.0f);
            }
            return (v1);
        }

        public static Blender getBlenderFor(BlendComposite composite) {
            switch (composite.getMode()) {
                case NORMAL:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            System.arraycopy(src, 0, result, 0, 4);
                            return result;
                        }
                    };
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
                case AVERAGE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = (src[i] + dst[i]) >> 1;
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case BLUE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            System.arraycopy(dst, 0, result, 0, 3);
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case COLOR:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            HSLtoRGB(srcHSL[0], srcHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3]);

                            return result;
                        }
                    };
                case COLOR_BURN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = src[i] == 0 ? 0 :
                                    Math.max(0, 255 - (((255 - dst[i]) << 8) / src[i]));
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case COLOR_DODGE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = src[i] == 255 ? 255 :
                                    Math.min((dst[i] << 8) / (255 - src[i]), 255);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
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
                case DIFFERENCE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = dst[i] + src[i] - (dst[i] * src[i] >> 7);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case EXCLUSION:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = dst[i] + src[i] - (dst[i] * src[i] >> 7);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case FREEZE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = src[i] == 0 ? 0 :
                                    Math.max(0, 255 - (255 - dst[i]) * (255 - dst[i]) / src[i]);
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case GLOW:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            for (int i = 0; i < 3; i++) {
                                result[i] = dst[i] == 255 ? 255 :
                                    Math.min(255, src[i] * src[i] / (255 - dst[i]));
                            }
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case GREEN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0],
                                dst[1],
                                src[2],
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case HARD_LIGHT:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                src[0] < 128 ? dst[0] * src[0] >> 7 :
                                    255 - ((255 - src[0]) * (255 - dst[0]) >> 7),
                                src[1] < 128 ? dst[1] * src[1] >> 7 :
                                    255 - ((255 - src[1]) * (255 - dst[1]) >> 7),
                                src[2] < 128 ? dst[2] * src[2] >> 7 :
                                    255 - ((255 - src[2]) * (255 - dst[2]) >> 7),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case HEAT:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0] == 0 ? 0 : Math.max(0, 255 - (255 - src[0]) * (255 - src[0]) / dst[0]),
                                dst[1] == 0 ? 0 : Math.max(0, 255 - (255 - src[1]) * (255 - src[1]) / dst[1]),
                                dst[2] == 0 ? 0 : Math.max(0, 255 - (255 - src[2]) * (255 - src[2]) / dst[2]),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case HUE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            HSLtoRGB(srcHSL[0], dstHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3]);

                            return result;
                        }
                    };
                case INVERSE_COLOR_BURN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0] == 0 ? 0 :
                                    Math.max(0, 255 - (((255 - src[0]) << 8) / dst[0])),
                                dst[1] == 0 ? 0 :
                                    Math.max(0, 255 - (((255 - src[1]) << 8) / dst[1])),
                                dst[2] == 0 ? 0 :
                                    Math.max(0, 255 - (((255 - src[2]) << 8) / dst[2])),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case INVERSE_COLOR_DODGE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0] == 255 ? 255 :
                                    Math.min((src[0] << 8) / (255 - dst[0]), 255),
                                dst[1] == 255 ? 255 :
                                    Math.min((src[1] << 8) / (255 - dst[1]), 255),
                                dst[2] == 255 ? 255 :
                                    Math.min((src[2] << 8) / (255 - dst[2]), 255),
                                Math.min(255, src[3] + dst[3])
                            };
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
                case LUMINOSITY:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            HSLtoRGB(dstHSL[0], dstHSL[1], srcHSL[2], result);
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
                            result[3] = Math.min(255, src[3] + dst[3]);
                            return result;
                        }
                    };
                case NEGATION:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                255 - Math.abs(255 - dst[0] - src[0]),
                                255 - Math.abs(255 - dst[1] - src[1]),
                                255 - Math.abs(255 - dst[2] - src[2]),
                                Math.min(255, src[3] + dst[3])
                            };
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
                case RED:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                src[0],
                                dst[1],
                                dst[2],
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case REFLECT:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                src[0] == 255 ? 255 : Math.min(255, dst[0] * dst[0] / (255 - src[0])),
                                src[1] == 255 ? 255 : Math.min(255, dst[1] * dst[1] / (255 - src[1])),
                                src[2] == 255 ? 255 : Math.min(255, dst[2] * dst[2] / (255 - src[2])),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case SATURATION:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            float[] srcHSL = new float[3];
                            RGBtoHSL(src[0], src[1], src[2], srcHSL);
                            float[] dstHSL = new float[3];
                            RGBtoHSL(dst[0], dst[1], dst[2], dstHSL);

                            HSLtoRGB(dstHSL[0], srcHSL[1], dstHSL[2], result);
                            result[3] = Math.min(255, src[3] + dst[3]);

                            return result;
                        }
                    };
                case SCREEN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                255 - ((255 - src[0]) * (255 - dst[0]) >> 8),
                                255 - ((255 - src[1]) * (255 - dst[1]) >> 8),
                                255 - ((255 - src[2]) * (255 - dst[2]) >> 8),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case SOFT_BURN:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0] + src[0] < 256 ?
	                                (dst[0] == 255 ? 255 :
                                        Math.min(255, (src[0] << 7) / (255 - dst[0]))) :
                                            Math.max(0, 255 - (((255 - dst[0]) << 7) / src[0])),
                                dst[1] + src[1] < 256 ?
	                                (dst[1] == 255 ? 255 :
                                        Math.min(255, (src[1] << 7) / (255 - dst[1]))) :
                                            Math.max(0, 255 - (((255 - dst[1]) << 7) / src[1])),
                                dst[2] + src[2] < 256 ?
	                                (dst[2] == 255 ? 255 :
                                        Math.min(255, (src[2] << 7) / (255 - dst[2]))) :
                                            Math.max(0, 255 - (((255 - dst[2]) << 7) / src[2])),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case SOFT_DODGE:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                dst[0] + src[0] < 256 ?
                                    (src[0] == 255 ? 255 :
                                        Math.min(255, (dst[0] << 7) / (255 - src[0]))) :
                                            Math.max(0, 255 - (((255 - src[0]) << 7) / dst[0])),
                                dst[1] + src[1] < 256 ?
                                    (src[1] == 255 ? 255 :
                                        Math.min(255, (dst[1] << 7) / (255 - src[1]))) :
                                            Math.max(0, 255 - (((255 - src[1]) << 7) / dst[1])),
                                dst[2] + src[2] < 256 ?
                                    (src[2] == 255 ? 255 :
                                        Math.min(255, (dst[2] << 7) / (255 - src[2]))) :
                                            Math.max(0, 255 - (((255 - src[2]) << 7) / dst[2])),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case SOFT_LIGHT:
                    break;
                case STAMP:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                Math.max(0, Math.min(255, dst[0] + 2 * src[0] - 256)),
                                Math.max(0, Math.min(255, dst[1] + 2 * src[1] - 256)),
                                Math.max(0, Math.min(255, dst[2] + 2 * src[2] - 256)),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
                case SUBTRACT:
                    return new Blender() {
                        @Override
                        public int[] blend(int[] src, int[] dst, int[] result) {
                            return new int[] {
                                Math.max(0, src[0] + dst[0] - 256),
                                Math.max(0, src[1] + dst[1] - 256),
                                Math.max(0, src[2] + dst[2] - 256),
                                Math.min(255, src[3] + dst[3])
                            };
                        }
                    };
            }
            throw new IllegalArgumentException("Blender not implement for " +
                                               composite.getMode().name());
        }
    }
}
