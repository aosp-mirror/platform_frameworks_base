/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.benchmark.synthetic;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.benchmark.R;
import com.android.benchmark.app.PerfTimeline;

import junit.framework.Test;


public class MemoryActivity extends Activity {
    private TextView mTextStatus;
    private TextView mTextMin;
    private TextView mTextMax;
    private TextView mTextTypical;
    private PerfTimeline mTimeline;

    TestInterface mTI;
    int mActiveTest;

    private class SyntheticTestCallback extends TestInterface.TestResultCallback {
        @Override
        void onTestResult(int command, float result) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("com.android.benchmark.synthetic.TEST_RESULT", result);
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory);

        mTextStatus = (TextView) findViewById(R.id.textView_status);
        mTextMin = (TextView) findViewById(R.id.textView_min);
        mTextMax = (TextView) findViewById(R.id.textView_max);
        mTextTypical = (TextView) findViewById(R.id.textView_typical);

        mTimeline = (PerfTimeline) findViewById(R.id.mem_timeline);

        mTI = new TestInterface(mTimeline, 2, new SyntheticTestCallback());
        mTI.mTextMax = mTextMax;
        mTI.mTextMin = mTextMin;
        mTI.mTextStatus = mTextStatus;
        mTI.mTextTypical = mTextTypical;

        mTimeline.mLinesLow = mTI.mLinesLow;
        mTimeline.mLinesHigh = mTI.mLinesHigh;
        mTimeline.mLinesValue = mTI.mLinesValue;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mActiveTest = i.getIntExtra("test", 0);

        switch (mActiveTest) {
            case 0:
                mTI.runMemoryBandwidth();
                break;
            case 1:
                mTI.runMemoryLatency();
                break;
            case 2:
                mTI.runPowerManagement();
                break;
            case 3:
                mTI.runCPUHeatSoak();
                break;
            case 4:
                mTI.runCPUGFlops();
                break;
            default:
                break;

        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_memory, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void onCpuBandwidth(View v) {


    }




}
