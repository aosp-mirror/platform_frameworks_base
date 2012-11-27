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

import java.util.ArrayList;

import com.android.test.hwuicompare.R;

import android.os.Bundle;
import android.os.Trace;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

public class AutomaticActivity extends CompareActivity {
    private static final String LOG_TAG = "AutomaticActivity";
    private static final float ERROR_DISPLAY_THRESHOLD = 0.01f;
    protected static final boolean DRAW_BITMAPS = false;

    private ImageView mSoftwareImageView = null;
    private ImageView mHardwareImageView = null;

    private static final float[] ERROR_CUTOFFS = {0, 0.005f, 0.01f, 0.02f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f};
    private float[] mErrorRates = new float[ERROR_CUTOFFS.length];
    private float mTotalTests = 0;
    private float mTotalError = 0;

    public abstract static class TestCallback {
        abstract void report(String name, float value);
        void complete() {}
    }

    private ArrayList<TestCallback> mTestCallbacks = new ArrayList<TestCallback>();

    Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            loadBitmaps();
            if (mSoftwareBitmap == null || mHardwareBitmap == null) {
                Log.e(LOG_TAG, "bitmap is null");
                return;
            }

            if (DRAW_BITMAPS) {
                mSoftwareImageView.setImageBitmap(mSoftwareBitmap);
                mHardwareImageView.setImageBitmap(mHardwareBitmap);
            }

            Trace.traceBegin(Trace.TRACE_TAG_ALWAYS, "calculateError");
            float error = mErrorCalculator.calcErrorRS(mSoftwareBitmap, mHardwareBitmap);
            Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);

            if (error > ERROR_DISPLAY_THRESHOLD) {
                String modname = "";
                for (String s : DisplayModifier.getLastAppliedModifications()) {
                    modname = modname.concat(s + ".");
                }
                Log.d(LOG_TAG, String.format("error for %s was %2.9f", modname, error));
            }
            for (int i = 0; i < ERROR_CUTOFFS.length; i++) {
                if (error <= ERROR_CUTOFFS[i]) break;
                mErrorRates[i]++;
            }
            mTotalError += error;
            mTotalTests++;

            if (DisplayModifier.step()) {
                for (TestCallback c : mTestCallbacks) {
                    c.report("averageError", (mTotalError / mTotalTests));
                    for (int i = 1; i < ERROR_CUTOFFS.length; i++) {
                        c.report(String.format("error over %1.3f", ERROR_CUTOFFS[i]),
                                mErrorRates[i]/mTotalTests);
                    }
                    c.complete();
                }

                Toast.makeText(getApplicationContext(), "done!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                mHardwareView.invalidate();
                if (DRAW_BITMAPS) {
                    mSoftwareImageView.invalidate();
                    mHardwareImageView.invalidate();
                }
            }
            mHandler.removeCallbacks(mRunnable);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRunnable);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.automatic_layout);

        mSoftwareImageView = (ImageView) findViewById(R.id.software_image_view);
        mHardwareImageView = (ImageView) findViewById(R.id.hardware_image_view);

        onCreateCommon(mRunnable);
        mTestCallbacks.add(new TestCallback() {
            void report(String name, float value) {
                Log.d(LOG_TAG, name + " " + value);
            };
        });
    }

    @Override
    protected boolean forceRecreateBitmaps() {
        // disable, unless needed for drawing into imageviews
        return DRAW_BITMAPS;
    }

    // FOR TESTING
    public void setCallback(TestCallback c) {
        mTestCallbacks.add(c);
    }
}
