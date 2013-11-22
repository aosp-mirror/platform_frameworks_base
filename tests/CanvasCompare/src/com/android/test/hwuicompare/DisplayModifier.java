/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.test.hwuicompare;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

public abstract class DisplayModifier {

    // automated tests ignore any combination of operations that don't together return TOTAL_MASK
    protected final static int TOTAL_MASK = 0x1F;

    // if we're filling, ensure we're not also sweeping over stroke parameters
    protected final static int SWEEP_STROKE_WIDTH_BIT = 0x1 << 0;
    protected final static int SWEEP_STROKE_CAP_BIT = 0x1 << 1;
    protected final static int SWEEP_STROKE_JOIN_BIT = 0x1 << 2;

    protected final static int SWEEP_SHADER_BIT = 0x1 << 3; // only allow non-simple shaders to use rectangle drawing
    protected final static int SWEEP_TRANSFORM_BIT = 0x1 << 4; // only sweep over specified transforms

    abstract public void modifyDrawing(Paint paint, Canvas canvas);
    protected int mask() { return 0x0; };

    private static final RectF gRect = new RectF(0, 0, 200, 175);
    private static final float[] gPts = new float[] {
            0, 100, 100, 0, 100, 200, 200, 100
    };

    private static final int NUM_PARALLEL_LINES = 24;
    private static final float[] gTriPts = new float[] {
        75, 0, 130, 130, 130, 130, 0, 130, 0, 130, 75, 0
    };
    private static final float[] gLinePts = new float[NUM_PARALLEL_LINES * 8 + gTriPts.length];
    static {
        int index;
        for (index = 0; index < gTriPts.length; index++) {
            gLinePts[index] = gTriPts[index];
        }
        float val = 0;
        for (int i = 0; i < NUM_PARALLEL_LINES; i++) {
            gLinePts[index + 0] = 150;
            gLinePts[index + 1] = val;
            gLinePts[index + 2] = 300;
            gLinePts[index + 3] = val;
            index += 4;
            val += 8 + (2.0f/NUM_PARALLEL_LINES);
        }
        val = 0;
        for (int i = 0; i < NUM_PARALLEL_LINES; i++) {
            gLinePts[index + 0] = val;
            gLinePts[index + 1] = 150;
            gLinePts[index + 2] = val;
            gLinePts[index + 3] = 300;
            index += 4;
            val += 8 + (2.0f/NUM_PARALLEL_LINES);
        }
    };

    @SuppressWarnings("serial")
    private static final LinkedHashMap<String, LinkedHashMap<String, DisplayModifier>> gMaps = new LinkedHashMap<String, LinkedHashMap<String, DisplayModifier>>() {
        {
            put("aa", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("true", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setAntiAlias(true);
                        }
                    });
                    put("false", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setAntiAlias(false);
                        }
                    });
                }
            });
            put("style", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("fill", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStyle(Paint.Style.FILL);
                        }
                    });
                    put("stroke", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStyle(Paint.Style.STROKE);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_WIDTH_BIT; }
                    });
                    put("fillAndStroke", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStyle(Paint.Style.FILL_AND_STROKE);
                        }

                        @Override
                        protected int mask() { return SWEEP_STROKE_WIDTH_BIT; }
                    });
                }
            });
            put("strokeWidth", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("hair", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeWidth(0);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_WIDTH_BIT; }
                    });
                    put("0.3", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeWidth(0.3f);
                        }
                    });
                    put("1", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeWidth(1);
                        }
                    });
                    put("5", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeWidth(5);
                        }
                    });
                    put("30", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeWidth(30);
                        }
                    });
                }
            });
            put("strokeCap", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("butt", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeCap(Paint.Cap.BUTT);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_CAP_BIT; }
                    });
                    put("round", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeCap(Paint.Cap.ROUND);
                        }
                    });
                    put("square", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeCap(Paint.Cap.SQUARE);
                        }
                    });
                }
            });
            put("strokeJoin", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("bevel", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeJoin(Paint.Join.BEVEL);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_JOIN_BIT; }
                    });
                    put("round", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeJoin(Paint.Join.ROUND);
                        }
                    });
                    put("miter", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setStrokeJoin(Paint.Join.MITER);
                        }
                    });
                    // TODO: add miter0, miter1 etc to test miter distances
                }
            });

            put("transform", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("noTransform", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {}
                        @Override
                        protected int mask() { return SWEEP_TRANSFORM_BIT; };
                    });
                    put("rotate5", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.rotate(5);
                        }
                    });
                    put("rotate45", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.rotate(45);
                        }
                    });
                    put("rotate90", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.rotate(90);
                            canvas.translate(0, -200);
                        }
                    });
                    put("scale2x2", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.scale(2, 2);
                        }
                        @Override
                        protected int mask() { return SWEEP_TRANSFORM_BIT; };
                    });
                    put("rot20scl1x4", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.rotate(20);
                            canvas.scale(1, 4);
                        }
                        @Override
                        protected int mask() { return SWEEP_TRANSFORM_BIT; };
                    });
                }
            });

            put("shader", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("noShader", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {}
                        @Override
                        protected int mask() { return SWEEP_SHADER_BIT; };
                    });
                    put("repeatShader", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mRepeatShader);
                        }
                        @Override
                        protected int mask() { return SWEEP_SHADER_BIT; };
                    });
                    put("translatedShader", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mTranslatedShader);
                        }
                    });
                    put("scaledShader", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mScaledShader);
                        }
                    });
                    put("horGradient", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mHorGradient);
                        }
                    });
                    put("diagGradient", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mDiagGradient);
                        }
                        @Override
                        protected int mask() { return SWEEP_SHADER_BIT; };
                    });
                    put("vertGradient", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setShader(ResourceModifiers.instance().mVertGradient);
                        }
                    });
                }
            });

            // FINAL MAP: DOES ACTUAL DRAWING
            put("drawing", new LinkedHashMap<String, DisplayModifier>() {
                {
                    put("roundRect", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawRoundRect(gRect, 20, 20, paint);
                        }
                    });
                    put("rect", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawRect(gRect, paint);
                        }
                        @Override
                        protected int mask() { return SWEEP_SHADER_BIT | SWEEP_STROKE_CAP_BIT; };
                    });
                    put("circle", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawCircle(100, 100, 75, paint);
                        }
                    });
                    put("oval", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawOval(gRect, paint);
                        }
                    });
                    put("lines", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawLines(gLinePts, paint);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_CAP_BIT; };
                    });
                    put("plusPoints", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawPoints(gPts, paint);
                        }
                    });
                    put("text", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setTextSize(36);
                            canvas.drawText("TEXTTEST", 0, 50, paint);
                        }
                    });
                    put("shadowtext", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            paint.setTextSize(36);
                            paint.setShadowLayer(3.0f, 0.0f, 3.0f, 0xffff00ff);
                            canvas.drawText("TEXTTEST", 0, 50, paint);
                        }
                    });
                    put("bitmapMesh", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawBitmapMesh(ResourceModifiers.instance().mBitmap, 3, 3,
                                    ResourceModifiers.instance().mBitmapVertices, 0, null, 0, null);
                        }
                    });
                    put("arc", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawArc(gRect, 260, 285, false, paint);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_CAP_BIT; };
                    });
                    put("arcFromCenter", new DisplayModifier() {
                        @Override
                        public void modifyDrawing(Paint paint, Canvas canvas) {
                            canvas.drawArc(gRect, 260, 285, true, paint);
                        }
                        @Override
                        protected int mask() { return SWEEP_STROKE_JOIN_BIT; };
                    });
                }
            });
            // WARNING: DON'T PUT MORE MAPS BELOW THIS
        }
    };

    private static LinkedHashMap<String, DisplayModifier> getMapAtIndex(int index) {
        for (LinkedHashMap<String, DisplayModifier> map : gMaps.values()) {
            if (index == 0) {
                return map;
            }
            index--;
        }
        return null;
    }

    // indices instead of iterators for easier bidirectional traversal
    private static final int mIndices[] = new int[gMaps.size()];
    private static final String[] mLastAppliedModifications = new String[gMaps.size()];

    private static boolean stepInternal(boolean forward) {
        int modifierMapIndex = gMaps.size() - 1;
        while (modifierMapIndex >= 0) {
            LinkedHashMap<String, DisplayModifier> map = getMapAtIndex(modifierMapIndex);
            mIndices[modifierMapIndex] += (forward ? 1 : -1);

            if (mIndices[modifierMapIndex] >= 0 && mIndices[modifierMapIndex] < map.size()) {
                break;
            }

            mIndices[modifierMapIndex] = (forward ? 0 : map.size() - 1);
            modifierMapIndex--;
        }
        return modifierMapIndex < 0; // true if resetting
    }

    public static boolean step() {
        boolean ret = false;
        do {
            ret |= stepInternal(true);
        } while (!checkModificationStateMask());
        return ret;
    }

    public static boolean stepBack() {
        boolean ret = false;
        do {
            ret |= stepInternal(false);
        } while (!checkModificationStateMask());
        return ret;
    }

    private static boolean checkModificationStateMask() {
        int operatorMask = 0x0;
        int mapIndex = 0;
        for (LinkedHashMap<String, DisplayModifier> map : gMaps.values()) {
            int displayModifierIndex = mIndices[mapIndex];
            for (Entry<String, DisplayModifier> modifierEntry : map.entrySet()) {
                if (displayModifierIndex == 0) {
                    mLastAppliedModifications[mapIndex] = modifierEntry.getKey();
                    operatorMask |= modifierEntry.getValue().mask();
                    break;
                }
                displayModifierIndex--;
            }
            mapIndex++;
        }
        return operatorMask == TOTAL_MASK;
    }

    public static void apply(Paint paint, Canvas canvas) {
        int mapIndex = 0;
        for (LinkedHashMap<String, DisplayModifier> map : gMaps.values()) {
            int displayModifierIndex = mIndices[mapIndex];
            for (Entry<String, DisplayModifier> modifierEntry : map.entrySet()) {
                if (displayModifierIndex == 0) {
                    mLastAppliedModifications[mapIndex] = modifierEntry.getKey();
                    modifierEntry.getValue().modifyDrawing(paint, canvas);
                    break;
                }
                displayModifierIndex--;
            }
            mapIndex++;
        }
    }

    public static String[] getLastAppliedModifications() {
        return mLastAppliedModifications.clone();
    }

    public static String[][] getStrings() {
        String[][] keys = new String[gMaps.size()][];

        int i = 0;
        for (LinkedHashMap<String, DisplayModifier> map : gMaps.values()) {
            keys[i] = new String[map.size()];
            int j = 0;
            for (String key : map.keySet()) {
                keys[i][j++] = key;
            }
            i++;
        }

        return keys;
    }

    public static void setIndex(int mapIndex, int newIndexValue) {
        mIndices[mapIndex] = newIndexValue;
    }

    public static int[] getIndices() {
        return mIndices;
    }
}
