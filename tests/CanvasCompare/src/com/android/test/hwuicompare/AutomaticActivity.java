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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.TreeSet;

import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.os.Environment;
import android.os.Trace;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

public class AutomaticActivity extends CompareActivity {
    private static final String LOG_TAG = "AutomaticActivity";
    private static final float ERROR_DISPLAY_THRESHOLD = 0.01f;
    protected static final boolean DRAW_BITMAPS = false;

    /**
     * Threshold of error change required to consider a test regressed/improved
     */
    private static final float ERROR_CHANGE_THRESHOLD = 0.001f;

    private static final float[] ERROR_CUTOFFS = {
            0, 0.005f, 0.01f, 0.02f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f
    };

    private final float[] mErrorRates = new float[ERROR_CUTOFFS.length];
    private float mTotalTests = 0;
    private float mTotalError = 0;
    private int mTestsRegressed = 0;
    private int mTestsImproved = 0;

    private ImageView mSoftwareImageView = null;
    private ImageView mHardwareImageView = null;


    public abstract static class FinalCallback {
        abstract void report(String name, float value);
        void complete() {};
    }

    private final ArrayList<FinalCallback> mFinalCallbacks = new ArrayList<FinalCallback>();

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

            final String[] modifierNames = DisplayModifier.getLastAppliedModifications();
            handleError(modifierNames, error);

            if (DisplayModifier.step()) {
                finishTest();
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
        beginTest();
    }

    private static class TestResult {
        TestResult(String label, float error) {
            mLabel = label;
            mTotalError = error;
            mCount = 1;
        }
        public void addInto(float error) {
            mTotalError += error;
            mCount++;
        }
        public float getAverage() {
            return mTotalError / mCount;
        }
        final String mLabel;
        float mTotalError;
        int mCount;
    }

    JSONObject mOutputJson = null;
    JSONObject mInputJson = null;
    final HashMap<String, TestResult> mModifierResults = new HashMap<String, TestResult>();
    final HashMap<String, TestResult> mIndividualResults = new HashMap<String, TestResult>();
    final HashMap<String, TestResult> mModifierDiffResults = new HashMap<String, TestResult>();
    final HashMap<String, TestResult> mIndividualDiffResults = new HashMap<String, TestResult>();
    private void beginTest() {
        mFinalCallbacks.add(new FinalCallback() {
            @Override
            void report(String name, float value) {
                Log.d(LOG_TAG, name + " " + value);
            };
        });

        File inputFile = new File(Environment.getExternalStorageDirectory(),
                "CanvasCompareInput.json");
        if (inputFile.exists() && inputFile.canRead() && inputFile.length() > 0) {
            try {
                FileInputStream inputStream = new FileInputStream(inputFile);
                Log.d(LOG_TAG, "Parsing input file...");
                StringBuffer content = new StringBuffer((int)inputFile.length());
                byte[] buffer = new byte[1024];
                while (inputStream.read(buffer) != -1) {
                    content.append(new String(buffer));
                }
                mInputJson = new JSONObject(content.toString());
                inputStream.close();
                Log.d(LOG_TAG, "Parsed input file with " + mInputJson.length() + " entries");
            } catch (JSONException e) {
                Log.e(LOG_TAG, "error parsing input json", e);
            } catch (IOException e) {
                Log.e(LOG_TAG, "error reading input json from sd", e);
            }
        }

        mOutputJson = new JSONObject();
    }

    private static void logTestResultHash(String label, HashMap<String, TestResult> map) {
        Log.d(LOG_TAG, "---------------");
        Log.d(LOG_TAG, label + ":");
        Log.d(LOG_TAG, "---------------");
        TreeSet<TestResult> set = new TreeSet<TestResult>(new Comparator<TestResult>() {
            @Override
            public int compare(TestResult lhs, TestResult rhs) {
                if (lhs == rhs) return 0; // don't need to worry about complex equality

                int cmp = Float.compare(lhs.getAverage(), rhs.getAverage());
                if (cmp != 0) {
                    return cmp;
                }
                return lhs.mLabel.compareTo(rhs.mLabel);
            }
        });

        for (TestResult t : map.values()) {
            set.add(t);
        }

        for (TestResult t : set.descendingSet()) {
            if (Math.abs(t.getAverage()) > ERROR_DISPLAY_THRESHOLD) {
                Log.d(LOG_TAG, String.format("%2.4f : %s", t.getAverage(), t.mLabel));
            }
        }
        Log.d(LOG_TAG, "");
    }

    private void finishTest() {
        for (FinalCallback c : mFinalCallbacks) {
            c.report("averageError", (mTotalError / mTotalTests));
            for (int i = 1; i < ERROR_CUTOFFS.length; i++) {
                c.report(String.format("tests with error over %1.3f", ERROR_CUTOFFS[i]),
                        mErrorRates[i]);
            }
            if (mInputJson != null) {
                c.report("tests regressed", mTestsRegressed);
                c.report("tests improved", mTestsImproved);
            }
            c.complete();
        }

        try {
            if (mOutputJson != null) {
                String outputString = mOutputJson.toString(4);
                File outputFile = new File(Environment.getExternalStorageDirectory(),
                        "CanvasCompareOutput.json");
                FileOutputStream outputStream = new FileOutputStream(outputFile);
                outputStream.write(outputString.getBytes());
                outputStream.close();
                Log.d(LOG_TAG, "Saved output file with " + mOutputJson.length() + " entries");
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "error during JSON stringify", e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "error storing JSON output on sd", e);
        }

        logTestResultHash("Modifier change vs previous", mModifierDiffResults);
        logTestResultHash("Invidual test change vs previous", mIndividualDiffResults);
        logTestResultHash("Modifier average test results", mModifierResults);
        logTestResultHash("Individual test results", mIndividualResults);

        Toast.makeText(getApplicationContext(), "done!", Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * Inserts the error value into all TestResult objects, associated with each of its modifiers
     */
    private static void addForAllModifiers(String fullName, float error, String[] modifierNames,
            HashMap<String, TestResult> modifierResults) {
        for (String modifierName : modifierNames) {
            TestResult r = modifierResults.get(fullName);
            if (r == null) {
                modifierResults.put(modifierName, new TestResult(modifierName, error));
            } else {
                r.addInto(error);
            }
        }
    }

    private void handleError(final String[] modifierNames, final float error) {
        String fullName = "";
        for (String s : modifierNames) {
            fullName = fullName.concat("." + s);
        }
        fullName = fullName.substring(1);

        float deltaError = 0;
        if (mInputJson != null) {
            try {
                deltaError = error - (float)mInputJson.getDouble(fullName);
            } catch (JSONException e) {
                Log.w(LOG_TAG, "Warning: unable to read from input json", e);
            }
            if (deltaError > ERROR_CHANGE_THRESHOLD) mTestsRegressed++;
            if (deltaError < -ERROR_CHANGE_THRESHOLD) mTestsImproved++;
            mIndividualDiffResults.put(fullName, new TestResult(fullName, deltaError));
            addForAllModifiers(fullName, deltaError, modifierNames, mModifierDiffResults);
        }

        mIndividualResults.put(fullName, new TestResult(fullName, error));
        addForAllModifiers(fullName, error, modifierNames, mModifierResults);

        try {
            if (mOutputJson != null) {
                mOutputJson.put(fullName, error);
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "exception during JSON recording", e);
            mOutputJson = null;
        }

        for (int i = 0; i < ERROR_CUTOFFS.length; i++) {
            if (error <= ERROR_CUTOFFS[i]) break;
            mErrorRates[i]++;
        }
        mTotalError += error;
        mTotalTests++;
    }

    @Override
    protected boolean forceRecreateBitmaps() {
        // disable, unless needed for drawing into imageviews
        return DRAW_BITMAPS;
    }

    // FOR TESTING
    public void setFinalCallback(FinalCallback c) {
        mFinalCallbacks.add(c);
    }
}
