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

package com.android.perftest;

import android.os.Environment;
import android.content.res.Resources;
import android.renderscript.*;
import android.util.DisplayMetrics;

import android.util.Log;


public class TextTest implements RsBenchBaseTest{

    private static final String TAG = "TextTest";
    private RenderScriptGL mRS;
    private Resources mRes;

    private ScriptC_text_test mTextScript;
    ScriptField_TestScripts_s.Item[] mTests;

    private final String[] mNames = {
        "Fill screen with text 1 time",
        "Fill screen with text 3 times",
        "Fill screen with text 5 times"
    };

    public TextTest() {
    }

    void addTest(int index, int fillNum) {
        mTests[index] = new ScriptField_TestScripts_s.Item();
        mTests[index].testScript = mTextScript;
        mTests[index].testName = Allocation.createFromString(mRS,
                                                             mNames[index],
                                                             Allocation.USAGE_SCRIPT);
        mTests[index].debugName = RsBenchRS.createZeroTerminatedAlloc(mRS,
                                                                     mNames[index],
                                                                     Allocation.USAGE_SCRIPT);

        ScriptField_TextTestData_s.Item dataItem = new ScriptField_TextTestData_s.Item();
        dataItem.fillNum = fillNum;
        ScriptField_TextTestData_s testData = new ScriptField_TextTestData_s(mRS, 1);
        testData.set(dataItem, 0, true);
        mTests[index].testData = testData.getAllocation();
    }

    public boolean init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initTextScript();
        mTests = new ScriptField_TestScripts_s.Item[mNames.length];

        int index = 0;
        addTest(index++, 1 /*fillNum*/);
        addTest(index++, 3 /*fillNum*/);
        addTest(index++, 5 /*fillNum*/);

        return true;
    }

    public ScriptField_TestScripts_s.Item[] getTests() {
        return mTests;
    }

    public String[] getTestNames() {
        return mNames;
    }

    void initTextScript() {
        DisplayMetrics metrics = mRes.getDisplayMetrics();

        mTextScript = new ScriptC_text_test(mRS, mRes, R.raw.text_test);
        mTextScript.set_gFontSans(Font.create(mRS, mRes, "sans-serif",
                                              Font.Style.NORMAL, 8.0f / metrics.density));
        mTextScript.set_gFontSerif(Font.create(mRS, mRes, "serif",
                                               Font.Style.NORMAL, 8.0f / metrics.density));
    }
}
