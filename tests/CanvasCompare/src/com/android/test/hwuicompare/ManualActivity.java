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

import com.android.test.hwuicompare.R;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

public class ManualActivity extends CompareActivity {
    private static final String LOG_TAG = "ManualActivity";
    private ImageView mCompareImageView;
    private Bitmap mCompareBitmap;
    private TextView mErrorTextView;
    private MainView mSoftwareView;

    private static final int COMPARE_VIEW_UNINITIALIZED = -1;
    private static final int COMPARE_VIEW_HARDWARE = 0;
    private static final int COMPARE_VIEW_SOFTWARE = 1;
    private static final int COMPARE_VIEW_HEATMAP = 2; // TODO: add more like this? any ideas?

    private int mCompareImageViewState = COMPARE_VIEW_UNINITIALIZED;
    private int mLastCompareImageViewState = COMPARE_VIEW_UNINITIALIZED;

    Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(LOG_TAG, "mRunnable running, mRedrewFlag = " + mRedrewFlag);

            if (mRedrewFlag) {
                loadBitmaps();
                // recalculate error
                float error = mErrorCalculator.calcErrorRS(mSoftwareBitmap, mHardwareBitmap);
                String modname = "";
                for (String s : DisplayModifier.getLastAppliedModifications()) {
                    modname = modname.concat(s + ".");
                }

                Log.d(LOG_TAG, "error for " + modname + " is " + error);
                mErrorTextView.setText(String.format("%.4f", error));
            }

            if (mCompareImageViewState != mLastCompareImageViewState || mRedrewFlag) {
                switch (mCompareImageViewState) {
                    case COMPARE_VIEW_UNINITIALIZED:
                        // set to hardware
                    case COMPARE_VIEW_HARDWARE:
                        mCompareImageView.setImageBitmap(mHardwareBitmap);
                        break;
                    case COMPARE_VIEW_SOFTWARE:
                        mCompareImageView.setImageBitmap(mSoftwareBitmap);
                        break;
                    case COMPARE_VIEW_HEATMAP:
                        mErrorCalculator.calcErrorHeatmapRS(mSoftwareBitmap, mHardwareBitmap,
                                mCompareBitmap);
                        mCompareImageView.setImageBitmap(mCompareBitmap);
                        break;
                }
                mCompareImageView.getDrawable().setFilterBitmap(false);
                mCompareImageView.invalidate();
            }

            mLastCompareImageViewState = mCompareImageViewState;
            mRedrewFlag = false;
            mHandler.removeCallbacks(mRunnable);
        }
    };

    private void redrawViews() {
        mHardwareView.invalidate();
        mSoftwareView.invalidate();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.manual_layout);
        onCreateCommon(mRunnable);

        mSoftwareView = (MainView) findViewById(R.id.software_view);
        mSoftwareView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mSoftwareView.setBackgroundColor(Color.WHITE);
        mSoftwareView.addDrawCallback(mDrawCallback);

        mCompareImageView = (ImageView) findViewById(R.id.compare_image_view);

        int width = getResources().getDimensionPixelSize(R.dimen.layer_width);
        int height = getResources().getDimensionPixelSize(R.dimen.layer_height);
        mCompareBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        mErrorTextView = (TextView) findViewById(R.id.current_error);
        ((ImageButton) findViewById(R.id.next)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                DisplayModifier.step();
                updateSpinners();
                redrawViews();
            }
        });
        ((ImageButton) findViewById(R.id.previous)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                DisplayModifier.stepBack();
                updateSpinners();
                redrawViews();
            }
        });
        ((Button) findViewById(R.id.show_hardware_version))
                .setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mCompareImageViewState = COMPARE_VIEW_HARDWARE;
                        mHandler.post(mRunnable);
                    }
                });
        ((Button) findViewById(R.id.show_software_version))
                .setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mCompareImageViewState = COMPARE_VIEW_SOFTWARE;
                        mHandler.post(mRunnable);
                    }
                });
        ((Button) findViewById(R.id.show_error_heatmap)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mCompareImageViewState = COMPARE_VIEW_HEATMAP;
                mHandler.post(mRunnable);
            }
        });

        buildSpinnerLayout();
    }

    private class DisplayModifierSpinner extends Spinner {
        private final int mIndex;

        public DisplayModifierSpinner(int index) {
            super(ManualActivity.this);
            mIndex = index;
            setOnItemSelectedListener(new OnItemSelectedListener() {

                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItem,
                        int position, long id) {
                    DisplayModifier.setIndex(mIndex, position);
                    redrawViews();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });
        }
    }

    private Spinner[] mSpinners;

    private void buildSpinnerLayout() {
        LinearLayout layout = (LinearLayout) findViewById(R.id.spinner_layout);
        String[][] mapsStrings = DisplayModifier.getStrings();
        mSpinners = new Spinner[mapsStrings.length];
        int index = 0;
        for (String[] spinnerValues : mapsStrings) {
            mSpinners[index] = new DisplayModifierSpinner(index);
            mSpinners[index].setAdapter(new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_dropdown_item, spinnerValues));
            layout.addView(mSpinners[index]);
            index++;
        }
        Log.d(LOG_TAG, "created " + index + " spinners");
    }

    private void updateSpinners() {
        int[] indices = DisplayModifier.getIndices();
        for (int i = 0; i < mSpinners.length; i++) {
            mSpinners[i].setSelection(indices[i]);
        }
    }

    @Override
    protected boolean forceRecreateBitmaps() {
        // continually recreate bitmaps to avoid modifying bitmaps currently being drawn
        return true;
    }
}
