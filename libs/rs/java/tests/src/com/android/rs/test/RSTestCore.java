/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.rs.test;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;
import java.util.ArrayList;
import java.util.ListIterator;


public class RSTestCore {
    int mWidth;
    int mHeight;

    public RSTestCore() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;

    private Font mFont;
    ScriptField_ListAllocs_s mListAllocs;
    int mLastX;
    int mLastY;
    private ScriptC_rslist mScript;

    private ArrayList<UnitTest> unitTests;
    private ListIterator<UnitTest> test_iter;
    private UnitTest activeTest;

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;

        mScript = new ScriptC_rslist(mRS, mRes, R.raw.rslist, true);

        unitTests = new ArrayList<UnitTest>();

        unitTests.add(new UT_primitives(this, mRes));
        unitTests.add(new UT_fp_mad(this, mRes));
        /*
        unitTests.add(new UnitTest(null, "<Pass>", 1));
        unitTests.add(new UnitTest());
        unitTests.add(new UnitTest(null, "<Fail>", -1));

        for (int i = 0; i < 20; i++) {
            unitTests.add(new UnitTest(null, "<Pass>", 1));
        }
        */

        UnitTest [] uta = new UnitTest[unitTests.size()];
        uta = unitTests.toArray(uta);

        mListAllocs = new ScriptField_ListAllocs_s(mRS, uta.length);
        for (int i = 0; i < uta.length; i++) {
            ScriptField_ListAllocs_s.Item listElem = new ScriptField_ListAllocs_s.Item();
            listElem.text = Allocation.createFromString(mRS, uta[i].name);
            listElem.result = uta[i].result;
            mListAllocs.set(listElem, i, false);
            uta[i].setItem(listElem);
        }

        mListAllocs.copyAll();

        mScript.bind_gList(mListAllocs);

        mFont = Font.createFromFamily(mRS, mRes, "serif", Font.Style.BOLD, 8);
        mScript.set_gFont(mFont);

        mRS.contextBindRootScript(mScript);

        test_iter = unitTests.listIterator();
        refreshTestResults(); /* Kick off the first test */
    }

    static int count = 0;
    public void checkAndRunNextTest() {
        if (activeTest != null) {
            if (!activeTest.isAlive()) {
                /* Properly clean up on our last test */
                try {
                    activeTest.join();
                }
                catch (InterruptedException e) {
                }
                activeTest = null;
            }
        }

        if (activeTest == null) {
            if (test_iter.hasNext()) {
                activeTest = test_iter.next();
                activeTest.start();
                /* This routine will only get called once when a new test
                 * should start running. The message handler in UnitTest.java
                 * ensures this. */
            }
        }
        count++;
    }

    public void refreshTestResults() {
        checkAndRunNextTest();

        if (mListAllocs != null && mScript != null && mRS != null) {
            mListAllocs.copyAll();

            mScript.bind_gList(mListAllocs);
            mRS.contextBindRootScript(mScript);
        }
    }

    public void newTouchPosition(float x, float y, float pressure, int id) {
    }

    public void onActionDown(int x, int y) {
        mScript.set_gDY(0.0f);
        mLastX = x;
        mLastY = y;
        refreshTestResults();
    }

    public void onActionMove(int x, int y) {
        int dx = mLastX - x;
        int dy = mLastY - y;

        if (Math.abs(dy) <= 2) {
            dy = 0;
        }

        mScript.set_gDY(dy);

        mLastX = x;
        mLastY = y;
        refreshTestResults();
    }
}
