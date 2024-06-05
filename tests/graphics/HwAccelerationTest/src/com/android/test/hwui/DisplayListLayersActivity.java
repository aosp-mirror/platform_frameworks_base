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


package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import static android.view.View.LAYER_TYPE_HARDWARE;
import static android.view.View.LAYER_TYPE_SOFTWARE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

@SuppressWarnings({"UnusedDeclaration"})
public class DisplayListLayersActivity extends Activity {
    private static final int VERTICAL_MARGIN = 12;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = createContainer();
        addChild(root, new LayerView(this, 0xffff0000, LAYER_TYPE_HARDWARE, "hardware"),
                WRAP_CONTENT, WRAP_CONTENT);
        addChild(root, new LayerView(this, 0xff0000ff, LAYER_TYPE_SOFTWARE, "software"),
                WRAP_CONTENT, WRAP_CONTENT);
        addChild(root, createButton(root), WRAP_CONTENT, WRAP_CONTENT);

        setContentView(root);
    }

    private Button createButton(final LinearLayout root) {
        Button button = new Button(this);
        button.setText("Invalidate");
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < root.getChildCount(); i++) {
                    View child = root.getChildAt(i);
                    if (child != v) {
                        child.invalidate();
                    }
                }
            }
        });

        return button;
    }

    private void addChild(LinearLayout root, View child, int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(0, dipToPx(VERTICAL_MARGIN), 0, 0);
        root.addView(child, params);
    }

    private int dipToPx(int size) {
        return (int) (getResources().getDisplayMetrics().density * size + 0.5f);
    }

    private LinearLayout createContainer() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private class LayerView extends View {
        private static final String LOG_TAG = "LayerView";
        private final Paint mPaint = new Paint();

        private final String mTag;

        LayerView(Context context, int color, int layerType, String tag) {
            super(context);

            mTag = tag;

            mPaint.setColor(color);
            setLayerType(layerType, null);
        }

        private void log(String tag) {
            Log.d(LOG_TAG, mTag + ": " + tag);
        }

        @Override
        public void invalidate() {
            log("invalidate");
            super.invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            log("draw");
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec) / 3,
                    MeasureSpec.getSize(heightMeasureSpec) / 3);
        }
    }
}
