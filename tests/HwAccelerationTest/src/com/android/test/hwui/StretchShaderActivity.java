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

package com.android.test.hwui;

import android.annotation.Nullable;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class StretchShaderActivity extends Activity {

    private static final float MAX_STRETCH_INTENSITY = 1.5f;
    private static final float STRETCH_AFFECTED_DISTANCE = 1.0f;

    private float mScrollX = 0f;
    private float mScrollY = 0f;

    private float mMaxStretchIntensity = MAX_STRETCH_INTENSITY;
    private float mStretchAffectedDistance = STRETCH_AFFECTED_DISTANCE;

    private float mOverscrollX = 25f;
    private float mOverscrollY = 25f;

    private RuntimeShader mRuntimeShader;
    private ImageView mImageView;
    private ImageView mTestImageView;

    private Bitmap mBitmap;

    private StretchDrawable mStretchDrawable = new StretchDrawable();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        mBitmap = ((BitmapDrawable) getDrawable(R.drawable.sunset1)).getBitmap();
        mRuntimeShader = new RuntimeShader(SKSL, false);

        BitmapShader bitmapShader = new BitmapShader(mBitmap, Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP);
        mRuntimeShader.setInputShader("uContentTexture", bitmapShader);

        mImageView = new ImageView(this);

        mImageView.setRenderEffect(RenderEffect.createShaderEffect(mRuntimeShader));
        mImageView.setImageDrawable(new ColorDrawable(Color.CYAN));

        TextView overscrollXText = new TextView(this);
        overscrollXText.setText("Overscroll X");

        SeekBar overscrollXBar = new SeekBar(this);
        overscrollXBar.setProgress(0);
        overscrollXBar.setMin(-50);
        overscrollXBar.setMax(50);
        overscrollXBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mOverscrollX = progress;
                overscrollXText.setText("Overscroll X: " + mOverscrollX);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        TextView overscrollYText = new TextView(this);
        overscrollYText.setText("Overscroll Y");

        SeekBar overscrollYBar = new SeekBar(this);
        overscrollYBar.setProgress(0);
        overscrollYBar.setMin(-50);
        overscrollYBar.setMax(50);
        overscrollYBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mOverscrollY = progress;
                overscrollYText.setText("Overscroll Y: " + mOverscrollY);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        TextView scrollXText = new TextView(this);
        scrollXText.setText("Scroll X");
        SeekBar scrollXSeekBar = new SeekBar(this);
        scrollXSeekBar.setMin(0);
        scrollXSeekBar.setMax(100);
        scrollXSeekBar.setProgress(0);
        scrollXSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mScrollX = (progress / 100f);
                scrollXText.setText("Scroll X: " + mScrollY);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        TextView scrollYText = new TextView(this);
        scrollYText.setText("Scroll Y");
        SeekBar scrollYSeekBar = new SeekBar(this);
        scrollYSeekBar.setMin(0);
        scrollYSeekBar.setMax(100);
        scrollYSeekBar.setProgress(0);
        scrollYSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mScrollY = (progress / 100f);
                scrollYText.setText("Scroll Y: " + mScrollY);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        TextView stretchIntensityText = new TextView(this);
        int stretchProgress = (int) (mMaxStretchIntensity * 100);
        stretchIntensityText.setText("StretchIntensity: " + mMaxStretchIntensity);
        SeekBar stretchIntensitySeekbar = new SeekBar(this);
        stretchIntensitySeekbar.setProgress(stretchProgress);
        stretchIntensitySeekbar.setMin(1);
        stretchIntensitySeekbar.setMax((int) (MAX_STRETCH_INTENSITY * 100));
        stretchIntensitySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mMaxStretchIntensity = progress / 100f;
                stretchIntensityText.setText("StretchIntensity: " + mMaxStretchIntensity);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        TextView stretchDistanceText = new TextView(this);
        stretchDistanceText.setText("StretchDistance");
        SeekBar stretchDistanceSeekbar = new SeekBar(this);
        stretchDistanceSeekbar.setMin(0);
        stretchDistanceSeekbar.setProgress((int) (mStretchAffectedDistance * 100));
        stretchDistanceSeekbar.setMax(100);
        stretchDistanceSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mStretchAffectedDistance = progress / 100f;
                stretchDistanceText.setText("StretchDistance: " + mStretchAffectedDistance);
                updateShader();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });


        linearLayout.addView(mImageView,
                new LinearLayout.LayoutParams(
                        mBitmap.getWidth(),
                        mBitmap.getHeight())
        );

        linearLayout.addView(overscrollXText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
        linearLayout.addView(overscrollXBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        linearLayout.addView(overscrollYText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
        linearLayout.addView(overscrollYBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        linearLayout.addView(scrollXText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

        linearLayout.addView(scrollXSeekBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

        linearLayout.addView(scrollYText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

        linearLayout.addView(scrollYSeekBar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

        linearLayout.addView(stretchIntensityText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        linearLayout.addView(stretchIntensitySeekbar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        linearLayout.addView(stretchDistanceText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

        linearLayout.addView(stretchDistanceSeekbar,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

        ImageView test = new ImageView(this);
        mStretchDrawable.setBitmap(mBitmap);
        test.setImageDrawable(mStretchDrawable);

        mTestImageView = test;
        linearLayout.addView(test,
                new LinearLayout.LayoutParams(mBitmap.getWidth(), mBitmap.getHeight()));

        setContentView(linearLayout);

    }

    @Override
    protected void onResume() {
        super.onResume();
        mImageView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        updateShader();
                        mImageView.getViewTreeObserver().removeOnPreDrawListener(this);
                        return false;
                    }
                });
    }

    private void updateShader() {
        final float width = mImageView.getWidth();
        final float height = mImageView.getHeight();
        final float distanceNotStretched = mStretchAffectedDistance;
        final float normOverScrollDistX = mOverscrollX / width;
        final float normOverScrollDistY = mOverscrollY / height;
        final float distanceStretchedX =
                mStretchAffectedDistance
                        / (1 + Math.abs(normOverScrollDistX) * mMaxStretchIntensity);
        final float distanceStretchedY =
                mStretchAffectedDistance
                        / (1 + Math.abs(normOverScrollDistY) * mMaxStretchIntensity);
        final float diffX = distanceStretchedX - distanceNotStretched;
        final float diffY = distanceStretchedY - distanceNotStretched;
        float uScrollX = mScrollX;
        float uScrollY = mScrollY;

        mRuntimeShader.setUniform("uMaxStretchIntensity", mMaxStretchIntensity);
        mRuntimeShader.setUniform("uStretchAffectedDist", mStretchAffectedDistance);
        mRuntimeShader.setUniform("uDistanceStretchedX", distanceStretchedX);
        mRuntimeShader.setUniform("uDistanceStretchedY", distanceStretchedY);
        mRuntimeShader.setUniform("uDistDiffX", diffX);
        mRuntimeShader.setUniform("uDistDiffY", diffY);
        mRuntimeShader.setUniform("uOverscrollX", normOverScrollDistX);
        mRuntimeShader.setUniform("uOverscrollY", normOverScrollDistY);
        mRuntimeShader.setUniform("uScrollX", uScrollX);
        mRuntimeShader.setUniform("uScrollY", uScrollY);
        mRuntimeShader.setUniform("viewportWidth", width);
        mRuntimeShader.setUniform("viewportHeight", height);

        mImageView.setRenderEffect(RenderEffect.createShaderEffect(mRuntimeShader));

        mStretchDrawable.setStretchDistance(mStretchAffectedDistance);
        mStretchDrawable.setOverscrollX(normOverScrollDistX);
        mStretchDrawable.setOverscrollY(normOverScrollDistY);
    }

    private static class StretchDrawable extends Drawable {

        private float mStretchDistance = 0;
        private float mOverScrollX = 0f;
        private float mOverScrollY = 0f;
        private Bitmap mBitmap = null;

        public void setStretchDistance(float stretchDistance) {
            mStretchDistance = stretchDistance;
            invalidateSelf();
        }

        public void setOverscrollX(float overscrollX) {
            mOverScrollX = overscrollX;
            invalidateSelf();
        }

        public void setOverscrollY(float overscrollY) {
            mOverScrollY = overscrollY;
            invalidateSelf();
        }

        public void setBitmap(Bitmap bitmap) {
            mBitmap = bitmap;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (mStretchDistance > 0 && canvas instanceof RecordingCanvas) {
                Rect bounds = getBounds();
                ((RecordingCanvas) canvas).mNode.stretch(
                        0,
                        0,
                        bounds.width(),
                        bounds.height(),
                        mOverScrollX,
                        mOverScrollY,
                        mStretchDistance
                );
            }
            if (mBitmap != null) {
                canvas.drawBitmap(mBitmap, 0f, 0f, null);
            }
        }

        @Override
        public void setAlpha(int alpha) {

        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {

        }

        @Override
        public int getOpacity() {
            return 0;
        }
    }

    private static final String SKSL = "in shader uContentTexture;\n"
            + "uniform float uMaxStretchIntensity; // multiplier to apply to scale effect\n"
            + "uniform float uStretchAffectedDist; // Maximum percentage to stretch beyond bounds"
            + " of target\n"
            + "\n"
            + "// Distance stretched as a function of the normalized overscroll times scale "
            + "intensity\n"
            + "uniform float uDistanceStretchedX;\n"
            + "uniform float uDistanceStretchedY;\n"
            + "uniform float uDistDiffX;\n"
            + "uniform float uDistDiffY; // Difference between the peak stretch amount and "
            + "overscroll amount normalized\n"
            + "uniform float uScrollX; // Horizontal offset represented as a ratio of pixels "
            + "divided by the target width\n"
            + "uniform float uScrollY; // Vertical offset represented as a ratio of pixels "
            + "divided by the target height\n"
            + "uniform float uOverscrollX; // Normalized overscroll amount in the horizontal "
            + "direction\n"
            + "uniform float uOverscrollY; // Normalized overscroll amount in the vertical "
            + "direction\n"
            + "\n"
            + "uniform float viewportWidth; // target height in pixels\n"
            + "uniform float viewportHeight; // target width in pixels\n"
            + "\n"
            + "vec4 main(vec2 coord) {\n"
            + "\n"
            + "    // Normalize SKSL pixel coordinate into a unit vector\n"
            + "    vec2 uv = vec2(coord.x / viewportWidth, coord.y / viewportHeight);\n"
            + "    float inU = uv.x;\n"
            + "    float inV = uv.y;\n"
            + "    float outU;\n"
            + "    float outV;\n"
            + "    float stretchIntensity;\n"
            + "\n"
            + "    // Add the normalized scroll position within scrolling list\n"
            + "    inU += uScrollX;\n"
            + "    inV += uScrollY;\n"
            + "\n"
            + "    outU = inU;\n"
            + "    outV = inV;\n"
            + "    if (uOverscrollX > 0) {\n"
            + "        if (inU <= uStretchAffectedDist) {\n"
            + "            inU = uStretchAffectedDist - inU;\n"
            + "            float posBasedVariation = smoothstep(0., uStretchAffectedDist, inU);\n"
            + "            stretchIntensity = uMaxStretchIntensity * uOverscrollX * "
            + "posBasedVariation;\n"
            + "            outU = uDistanceStretchedX - (inU / (1. + stretchIntensity));\n"
            + "        } else {\n"
            + "            outU = uDistDiffX + inU;\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "     if (uOverscrollX < 0) {\n"
            + "            float stretchAffectedDist = 1. - uStretchAffectedDist;\n"
            + "            if (inU >= stretchAffectedDist) {\n"
            + "                inU = inU - stretchAffectedDist;\n"
            + "                float posBasedVariation = (smoothstep(0., uStretchAffectedDist, "
            + "inU));\n"
            + "                stretchIntensity = uMaxStretchIntensity * (-uOverscrollX) * "
            + "posBasedVariation;\n"
            + "                outU = 1 - (uDistanceStretchedX - (inU / (1. + stretchIntensity)))"
            + ";\n"
            + "            } else if (inU < stretchAffectedDist) {\n"
            + "                outU = -uDistDiffX + inU;\n"
            + "            }\n"
            + "        }\n"
            + "\n"
            + "    if (uOverscrollY > 0) {\n"
            + "        if (inV <= uStretchAffectedDist) {\n"
            + "            inV = uStretchAffectedDist - inV;\n"
            + "            float posBasedVariation = smoothstep(0., uStretchAffectedDist, inV);\n"
            + "            stretchIntensity = uMaxStretchIntensity * uOverscrollY * "
            + "posBasedVariation;\n"
            + "            outV = uDistanceStretchedY - (inV / (1. + stretchIntensity));\n"
            + "        } else if (inV >= uStretchAffectedDist) {\n"
            + "            outV = uDistDiffY + inV;\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    if (uOverscrollY < 0) {\n"
            + "        float stretchAffectedDist = 1. - uStretchAffectedDist;\n"
            + "        if (inV >= stretchAffectedDist) {\n"
            + "            inV = inV - stretchAffectedDist;\n"
            + "            float posBasedVariation = (smoothstep(0., uStretchAffectedDist, inV));\n"
            + "            stretchIntensity = uMaxStretchIntensity * (-uOverscrollY) * "
            + "posBasedVariation;\n"
            + "            outV = 1 - (uDistanceStretchedY - (inV / (1. + stretchIntensity)));\n"
            + "        } else if (inV < stretchAffectedDist) {\n"
            + "            outV = -uDistDiffY + inV;\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    uv.x = outU;\n"
            + "    uv.y = outV;\n"
            + "    coord.x = uv.x * viewportWidth;\n"
            + "    coord.y = uv.y * viewportHeight;\n"
            + "    return sample(uContentTexture, coord);\n"
            + "}";
}
