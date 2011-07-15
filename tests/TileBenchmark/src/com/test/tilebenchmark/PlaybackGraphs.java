/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.test.tilebenchmark;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;

public class PlaybackGraphs {
    private static final int BAR_WIDTH = PlaybackView.TILEX * 3;
    private static final float CANVAS_SCALE = 0.2f;
    private static final double IDEAL_FRAMES = 60;
    private static final int LABELOFFSET = 100;
    private static Paint whiteLabels;

    private static double viewportCoverage(int l, int b, int r, int t,
            int tileIndexX,
            int tileIndexY) {
        if (tileIndexX * PlaybackView.TILEX < r
                && (tileIndexX + 1) * PlaybackView.TILEX >= l
                && tileIndexY * PlaybackView.TILEY < t
                && (tileIndexY + 1) * PlaybackView.TILEY >= b) {
            return 1.0f;
        }
        return 0.0f;
    }

    private interface MetricGen {
        public double getValue(TileData[] frame);

        public double getMax();

        public int getLabelId();
    };

    private static MetricGen[] Metrics = new MetricGen[] {
            new MetricGen() {
                // framerate graph
                @Override
                public double getValue(TileData[] frame) {
                    int renderTimeUS = frame[0].level;
                    return 1.0e6f / renderTimeUS;
                }

                @Override
                public double getMax() {
                    return IDEAL_FRAMES;
                }

                @Override
                public int getLabelId() {
                    return R.string.frames_per_second;
                }
            }, new MetricGen() {
                // coverage graph
                @Override
                public double getValue(TileData[] frame) {
                    int l = frame[0].x, b = frame[0].y;
                    int r = frame[1].x, t = frame[1].y;
                    double total = 0, totalCount = 0;
                    for (int tileID = 2; tileID < frame.length; tileID++) {
                        TileData data = frame[tileID];
                        double coverage = viewportCoverage(l, b, r, t, data.x,
                                data.y);
                        total += coverage * (data.isReady ? 1 : 0);
                        totalCount += coverage;
                    }
                    if (totalCount == 0) {
                        return -1;
                    }
                    return total / totalCount;
                }

                @Override
                public double getMax() {
                    return 1;
                }

                @Override
                public int getLabelId() {
                    return R.string.viewport_coverage;
                }
            }
    };

    private interface StatGen {
        public double getValue(double sortedValues[]);

        public int getLabelId();
    }

    public static double getPercentile(double sortedValues[], double ratioAbove) {
        double index = ratioAbove * (sortedValues.length - 1);
        int intIndex = (int) Math.floor(index);
        if (index == intIndex) {
            return sortedValues[intIndex];
        }
        double alpha = index - intIndex;
        return sortedValues[intIndex] * (1 - alpha)
                + sortedValues[intIndex + 1] * (alpha);
    }

    private static StatGen[] Stats = new StatGen[] {
            new StatGen() {
                @Override
                public double getValue(double[] sortedValues) {
                    return getPercentile(sortedValues, 0.25);
                }

                @Override
                public int getLabelId() {
                    return R.string.percentile_25;
                }
            }, new StatGen() {
                @Override
                public double getValue(double[] sortedValues) {
                    return getPercentile(sortedValues, 0.5);
                }

                @Override
                public int getLabelId() {
                    return R.string.percentile_50;
                }
            }, new StatGen() {
                @Override
                public double getValue(double[] sortedValues) {
                    return getPercentile(sortedValues, 0.75);
                }

                @Override
                public int getLabelId() {
                    return R.string.percentile_75;
                }
            },
    };

    public PlaybackGraphs() {
        whiteLabels = new Paint();
        whiteLabels.setColor(Color.WHITE);
        whiteLabels.setTextSize(PlaybackView.TILEY / 3);
    }

    private ArrayList<ShapeDrawable> mShapes = new ArrayList<ShapeDrawable>();
    private double[][] mStats = new double[Metrics.length][Stats.length];

    public void setData(TileData[][] tileProfilingData) {
        mShapes.clear();
        double metricValues[] = new double[tileProfilingData.length];

        if (tileProfilingData.length == 0) {
            return;
        }

        for (int metricIndex = 0; metricIndex < Metrics.length; metricIndex++) {
            // create graph out of rectangles, one per frame
            int lastBar = 0;
            for (int frameIndex = 0; frameIndex < tileProfilingData.length; frameIndex++) {
                TileData frame[] = tileProfilingData[frameIndex];
                int newBar = (frame[0].y + frame[1].y) / 2;

                MetricGen s = Metrics[metricIndex];
                double absoluteValue = s.getValue(frame);
                double relativeValue = absoluteValue / s.getMax();
                int rightPos = (int) (-BAR_WIDTH * metricIndex);
                int leftPos = (int) (-BAR_WIDTH * (metricIndex + relativeValue));

                ShapeDrawable graphBar = new ShapeDrawable();
                graphBar.getPaint().setColor(Color.BLUE);
                graphBar.setBounds(leftPos, lastBar, rightPos, newBar);

                mShapes.add(graphBar);
                metricValues[frameIndex] = absoluteValue;
                lastBar = newBar;
            }

            // store aggregate statistics per metric (median, and similar)
            Arrays.sort(metricValues);
            for (int statIndex = 0; statIndex < Stats.length; statIndex++) {
                mStats[metricIndex][statIndex] = Stats[statIndex]
                        .getValue(metricValues);
            }
        }
    }

    public void drawVerticalShiftedShapes(Canvas canvas,
            ArrayList<ShapeDrawable> shapes) {
        // Shapes drawn here are drawn relative to the viewRect
        Rect viewRect = shapes.get(shapes.size() - 1).getBounds();
        canvas.translate(0, 5 * PlaybackView.TILEY - viewRect.top);

        for (ShapeDrawable shape : mShapes) {
            shape.draw(canvas);
        }
        for (ShapeDrawable shape : shapes) {
            shape.draw(canvas);
        }
    }

    public void draw(Canvas canvas, ArrayList<ShapeDrawable> shapes,
            String[] strings, Resources resources) {
        canvas.scale(CANVAS_SCALE, CANVAS_SCALE);

        canvas.translate(BAR_WIDTH * Metrics.length, 0);

        canvas.save();
        drawVerticalShiftedShapes(canvas, shapes);
        canvas.restore();

        for (int metricIndex = 0; metricIndex < Metrics.length; metricIndex++) {
            String label = resources.getString(
                    Metrics[metricIndex].getLabelId());
            int xPos = (metricIndex + 1) * -BAR_WIDTH;
            int yPos = LABELOFFSET;
            canvas.drawText(label, xPos, yPos, whiteLabels);
            for (int statIndex = 0; statIndex < Stats.length; statIndex++) {
                label = resources.getString(R.string.format_stat, mStats[metricIndex][statIndex]);
                yPos = LABELOFFSET + (1 + statIndex) * PlaybackView.TILEY / 2;
                canvas.drawText(label, xPos, yPos, whiteLabels);
            }
        }
        for (int stringIndex = 0; stringIndex < strings.length; stringIndex++) {
            int yPos = LABELOFFSET + stringIndex * PlaybackView.TILEY / 2;
            canvas.drawText(strings[stringIndex], 0, yPos, whiteLabels);
        }
    }

    public Bundle getStatBundle(Resources resources) {
        Bundle b = new Bundle();

        for (int metricIndex = 0; metricIndex < Metrics.length; metricIndex++) {
            for (int statIndex = 0; statIndex < Stats.length; statIndex++) {
                String metricLabel = resources.getString(
                        Metrics[metricIndex].getLabelId());
                String statLabel = resources.getString(
                        Stats[statIndex].getLabelId());
                double value = mStats[metricIndex][statIndex];
                b.putDouble(metricLabel + " " + statLabel, value);
            }
        }

        return b;
    }
}
