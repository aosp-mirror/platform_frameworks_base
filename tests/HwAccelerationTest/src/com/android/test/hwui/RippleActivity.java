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

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RuntimeShader;
import android.os.Bundle;
import android.os.Trace;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

import java.util.ArrayList;

public class RippleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(new RippleView(this),
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setContentView(layout);
    }

    static class RippleView extends View {
        static final int DURATION = 1000;
        static final int MAX_RADIUS = 250;
        private final int mColor = Color.RED;

        private boolean mToggle = false;
        ArrayList<RenderNodeAnimator> mRunningAnimations = new ArrayList<RenderNodeAnimator>();

        CanvasProperty<Float> mX;
        CanvasProperty<Float> mY;
        CanvasProperty<Float> mRadius;
        CanvasProperty<Float> mProgress;
        CanvasProperty<Float> mNoisePhase;
        CanvasProperty<Paint> mPaint;
        RuntimeShader mRuntimeShader;

        static final String sSkSL = ""
                + "uniform float2 in_origin;"
                + "uniform float in_progress;\n"
                + "uniform float in_maxRadius;\n"
                + "uniform shader in_paintColor;\n"
                + "float dist2(float2 p0, float2 pf) { return sqrt((pf.x - p0.x) * (pf.x - p0.x) + "
                + "(pf.y - p0.y) * (pf.y - p0.y)); }\n"
                + "float mod2(float a, float b) { return a - (b * floor(a / b)); }\n"
                + "float rand(float2 src) { return fract(sin(dot(src.xy, float2(12.9898, 78.233)))"
                + " * 43758.5453123); }\n"
                + "float4 main(float2 p)\n"
                + "{\n"
                + "    float fraction = in_progress;\n"
                + "    float2 fragCoord = p;//sk_FragCoord.xy;\n"
                + "    float maxDist = in_maxRadius;\n"
                + "    float fragDist = dist2(in_origin, fragCoord.xy);\n"
                + "    float circleRadius = maxDist * fraction;\n"
                + "    float colorVal = (fragDist - circleRadius) / maxDist;\n"
                + "    float d = fragDist < circleRadius \n"
                + "        ? 1. - abs(colorVal * 2. * smoothstep(0., 1., fraction)) \n"
                + "        : 1. - abs(colorVal * 3.);\n"
                + "    d = smoothstep(0., 1., d);\n"
                + "    float divider = 2.;\n"
                + "    float x = floor(fragCoord.x / divider);\n"
                + "    float y = floor(fragCoord.y / divider);\n"
                + "    float density = .95;\n"
                + "    d = rand(float2(x, y)) > density ? d : d * .2;\n"
                + "    d = d * rand(float2(fraction, x * y));\n"
                + "    float alpha = 1. - pow(fraction, 3.);\n"
                + "    return float4(sample(in_paintColor, p).rgb, d * alpha);\n"
                + "}";

        RippleView(Context c) {
            super(c);
            setClickable(true);

            mX = CanvasProperty.createFloat(200.0f);
            mY = CanvasProperty.createFloat(200.0f);
            mRadius = CanvasProperty.createFloat(150.0f);
            mProgress = CanvasProperty.createFloat(0.0f);
            mNoisePhase = CanvasProperty.createFloat(0.0f);

            Paint p = new Paint();
            p.setAntiAlias(true);
            p.setColor(mColor);
            mPaint = CanvasProperty.createPaint(p);

            mRuntimeShader = new RuntimeShader(sSkSL, false);
            mRuntimeShader.setUniform("in_maxRadius", MAX_RADIUS);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (canvas.isHardwareAccelerated()) {
                RecordingCanvas recordingCanvas = (RecordingCanvas) canvas;
                recordingCanvas.drawRipple(mX, mY, mRadius, mPaint, mProgress, mNoisePhase,
                        mColor, mRuntimeShader);
            }
        }

        @Override
        public boolean performClick() {
            for (int i = 0; i < mRunningAnimations.size(); i++) {
                mRunningAnimations.get(i).cancel();
            }
            mRunningAnimations.clear();

            mToggle = !mToggle;

            mRunningAnimations.add(new RenderNodeAnimator(
                    mX, mToggle ? 400.0f : 200.0f));

            mRunningAnimations.add(new RenderNodeAnimator(
                    mY, mToggle ? 600.0f : 200.0f));

            mRunningAnimations.add(new RenderNodeAnimator(
                    mRadius, mToggle ? MAX_RADIUS : 150.0f));

            mRunningAnimations.add(new RenderNodeAnimator(
                    mProgress, mToggle ? 1.0f : 0.0f));

            mRunningAnimations.add(new RenderNodeAnimator(
                    mNoisePhase, DURATION));

            mRunningAnimations.add(new RenderNodeAnimator(
                    mPaint, RenderNodeAnimator.PAINT_ALPHA, 64.0f));

            // Will be "chained" to run after the above
            mRunningAnimations.add(new RenderNodeAnimator(
                    mPaint, RenderNodeAnimator.PAINT_ALPHA, 255.0f));

            for (int i = 0; i < mRunningAnimations.size(); i++) {
                RenderNodeAnimator anim = mRunningAnimations.get(i);
                anim.setDuration(DURATION);
                anim.setTarget(this);
                if (i == (mRunningAnimations.size() - 1)) {
                    // "chain" test
                    anim.setStartValue(64.0f);
                    anim.setStartDelay(anim.getDuration());
                }
                anim.start();
            }

            if (mToggle) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "pretendBusy");
                        try {
                            Thread.sleep(DURATION);
                        } catch (InterruptedException e) {
                        }
                        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                    }
                });
            }
            return true;
        }
    }
}
