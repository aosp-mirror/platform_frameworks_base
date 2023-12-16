/*
 * Copyright 2022 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class SurfaceViewAlphaActivity extends Activity implements Callback {
    SurfaceView mSurfaceView;

    private enum ZOrder {
        ABOVE,
        BELOW
    }

    private float mAlpha = 127f / 255f;
    private ZOrder mZOrder = ZOrder.BELOW;


    private String getAlphaText() {
        return "Alpha: " + mAlpha;
    }

    private void toggleZOrder() {
        if (ZOrder.ABOVE.equals(mZOrder)) {
            mZOrder = ZOrder.BELOW;
        } else {
            mZOrder = ZOrder.ABOVE;
        }
    }

    // Overlaps a blue view on the left, then the SurfaceView in the center, then a blue view on the
    // right.
    private void overlapViews(SurfaceView view, LinearLayout parent) {
        float density = getResources().getDisplayMetrics().density;
        int surfaceViewSize = (int) (200 * density);
        int blueViewSize = (int) (surfaceViewSize * 2 / 3f);
        int totalSize = (int) (surfaceViewSize * 5 / 3f);

        RelativeLayout overlapLayout = new RelativeLayout(this);

        RelativeLayout.LayoutParams leftViewLayoutParams = new RelativeLayout.LayoutParams(
                blueViewSize, surfaceViewSize);
        leftViewLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);

        View leftBlueView = new View(this);
        leftBlueView.setBackgroundColor(Color.BLUE);
        overlapLayout.addView(leftBlueView, leftViewLayoutParams);

        RelativeLayout.LayoutParams sVLayoutParams = new RelativeLayout.LayoutParams(
                surfaceViewSize, surfaceViewSize);
        sVLayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        overlapLayout.addView(view, sVLayoutParams);

        RelativeLayout.LayoutParams rightViewLayoutParams = new RelativeLayout.LayoutParams(
                blueViewSize, surfaceViewSize);
        rightViewLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);

        View rightBlueView = new View(this);
        rightBlueView.setBackgroundColor(Color.BLUE);
        overlapLayout.addView(rightBlueView, rightViewLayoutParams);

        parent.addView(overlapLayout, new LinearLayout.LayoutParams(
                totalSize, surfaceViewSize));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.setAlpha(mAlpha);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView alphaText = new TextView(this);
        alphaText.setText(getAlphaText());

        SeekBar alphaToggle = new SeekBar(this);
        alphaToggle.setMin(0);
        alphaToggle.setMax(255);
        alphaToggle.setProgress(Math.round(mAlpha * 255));
        alphaToggle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mAlpha = progress / 255f;
                alphaText.setText(getAlphaText());
                mSurfaceView.setAlpha(mAlpha);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

        content.addView(alphaText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        content.addView(alphaToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button button = new Button(this);
        button.setText("Z " + mZOrder.toString());
        button.setOnClickListener(v -> {
            toggleZOrder();
            mSurfaceView.setZOrderOnTop(ZOrder.ABOVE.equals(mZOrder));
            button.setText("Z " + mZOrder.toString());
        });

        content.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        overlapViews(mSurfaceView, content);

        setContentView(content);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(Color.RED);
        holder.unlockCanvasAndPost(canvas);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }
}
