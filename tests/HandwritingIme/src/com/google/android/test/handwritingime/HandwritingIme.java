/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.google.android.test.handwritingime;

import android.annotation.Nullable;
import android.graphics.PointF;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.CursorAnchorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.Random;

public class HandwritingIme extends InputMethodService {

    public static final int HEIGHT_DP = 100;


    private static final int OP_NONE = 0;
    private static final int OP_SELECT = 1;
    private static final int OP_DELETE = 2;
    private static final int OP_DELETE_SPACE = 3;
    private static final int OP_INSERT = 4;

    private Window mInkWindow;
    private InkView mInk;

    static final String TAG = "HandwritingIme";
    private int mRichGestureMode = OP_NONE;
    private Spinner mRichGestureModeSpinner;
    private PointF mRichGestureStartPoint;


    interface HandwritingFinisher {
        void finish();
    }

    interface StylusListener {
        void onStylusEvent(MotionEvent me);
    }

    final class StylusConsumer implements StylusListener {
        @Override
        public void onStylusEvent(MotionEvent me) {
            HandwritingIme.this.onStylusEvent(me);
        }
    }

    final class HandwritingFinisherImpl implements HandwritingFinisher {

        HandwritingFinisherImpl() {}

        @Override
        public void finish() {
            finishStylusHandwriting();
            Log.d(TAG, "HandwritingIme called finishStylusHandwriting() ");
        }
    }

    private void onStylusEvent(@Nullable MotionEvent event) {
        // TODO Hookup recognizer here
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP: {
                if (areRichGesturesEnabled()) {
                    Bundle bundle = new Bundle();
                    bundle.putInt("operation", mRichGestureMode);
                    bundle.putFloat("left", mRichGestureStartPoint.x);
                    bundle.putFloat("top", mRichGestureStartPoint.y);
                    bundle.putFloat("right", event.getX());
                    bundle.putFloat("bottom", event.getY());
                    performPrivateCommand("android.widget.RichGesture", bundle);

                    Log.d(TAG, "Sending RichGesture " + mRichGestureMode + " (Screen) Left: "
                            + mRichGestureStartPoint.x + ", Top: " + mRichGestureStartPoint.y
                            + ", Right: " + event.getX() + ", Bottom: " + event.getY());
                } else {
                    // insert random ASCII char
                    sendKeyChar((char) (56 + new Random().nextInt(66)));
                }
                return;
            }
            case MotionEvent.ACTION_DOWN: {
                if (areRichGesturesEnabled()) {
                    mRichGestureStartPoint = new PointF(event.getX(), event.getY());
                }
                return;
            }
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        final ViewGroup view = new FrameLayout(this);
        final View inner = new View(this);
        final float density = getResources().getDisplayMetrics().density;
        final int height = (int) (HEIGHT_DP * density);
        view.setPadding(0, 0, 0, 0);
        view.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, height));

        view.addView(getRichGestureActionsSpinner());
        inner.setBackgroundColor(getColor(R.color.abc_tint_spinner));

        return view;
    }

    private View getRichGestureActionsSpinner() {
        if (mRichGestureModeSpinner != null) {
            return mRichGestureModeSpinner;
        }
        //get the spinner from the xml.
        mRichGestureModeSpinner = new Spinner(this);
        mRichGestureModeSpinner.setPadding(100, 0, 100, 0);
        mRichGestureModeSpinner.setTooltipText("Handwriting IME mode");
        String[] items =
                new String[] { "Handwriting IME - Rich gesture disabled", "Rich gesture SELECT",
                        "Rich gesture DELETE", "Rich gesture DELETE SPACE",
                        "Rich gesture INSERT" };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRichGestureModeSpinner.setAdapter(adapter);
        mRichGestureModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mRichGestureMode = position;
                Log.d(TAG, "Setting RichGesture Mode " + mRichGestureMode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mRichGestureMode = OP_NONE;
            }
        });
        return mRichGestureModeSpinner;
    }

    public void onPrepareStylusHandwriting() {
        Log.d(TAG, "onPrepareStylusHandwriting ");
        if (mInk == null) {
            mInk = new InkView(this, new HandwritingFinisherImpl(), new StylusConsumer());
        }
    }

    @Override
    public boolean onStartStylusHandwriting() {
        Log.d(TAG, "onStartStylusHandwriting ");
        Toast.makeText(this, "START HW", Toast.LENGTH_SHORT).show();
        mInkWindow = getStylusHandwritingWindow();
        mInkWindow.setContentView(mInk, mInk.getLayoutParams());
        return true;
    }

    @Override
    public void onFinishStylusHandwriting() {
        Log.d(TAG, "onFinishStylusHandwriting ");
        Toast.makeText(this, "Finish HW", Toast.LENGTH_SHORT).show();
        // Free-up
        ((ViewGroup) mInk.getParent()).removeView(mInk);
        mInk = null;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    boolean performPrivateCommand(String action, Bundle bundle) {
        if (!getCurrentInputStarted()) {
            Log.e(TAG, "Input hasnt started, can't performPrivateCommand");
            return false;
        }

        return getCurrentInputConnection().performPrivateCommand(action, bundle);
    }

    private boolean areRichGesturesEnabled() {
        return mRichGestureMode != OP_NONE;
    }
}
