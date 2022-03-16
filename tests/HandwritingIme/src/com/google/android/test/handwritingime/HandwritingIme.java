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
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

public class HandwritingIme extends InputMethodService {

    public static final int HEIGHT_DP = 100;

    private Window mInkWindow;
    private InkView mInk;

    static final String TAG = "HandwritingIme";

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
        if (event.getAction() == MotionEvent.ACTION_UP) {
            sendKeyChar((char) (56 + new Random().nextInt(66)));
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
        TextView text = new TextView(this);
        text.setText("Handwriting IME");
        text.setTextSize(13f);
        text.setTextColor(getColor(android.R.color.white));
        text.setGravity(Gravity.CENTER);
        text.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, height));
        view.addView(text);
        inner.setBackgroundColor(0xff0110fe); // blue

        return view;
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
}
