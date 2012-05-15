/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Timer;
import java.util.TimerTask;


public class RSTestCore {
    int mWidth;
    int mHeight;
    Context mCtx;

    public RSTestCore(Context ctx) {
        mCtx = ctx;
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
    private boolean stopTesting;

    /* Periodic timer for ensuring future tests get scheduled */
    private Timer mTimer;
    public static final int RS_TIMER_PERIOD = 100;

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        stopTesting = false;

        mScript = new ScriptC_rslist(mRS, mRes, R.raw.rslist);

        unitTests = new ArrayList<UnitTest>();

        unitTests.add(new UT_primitives(this, mRes, mCtx));
        unitTests.add(new UT_constant(this, mRes, mCtx));
        unitTests.add(new UT_vector(this, mRes, mCtx));
        unitTests.add(new UT_array_init(this, mRes, mCtx));
        unitTests.add(new UT_array_alloc(this, mRes, mCtx));
        unitTests.add(new UT_clamp(this, mRes, mCtx));
        unitTests.add(new UT_clamp_relaxed(this, mRes, mCtx));
        unitTests.add(new UT_convert(this, mRes, mCtx));
        unitTests.add(new UT_convert_relaxed(this, mRes, mCtx));
        unitTests.add(new UT_rsdebug(this, mRes, mCtx));
        unitTests.add(new UT_rstime(this, mRes, mCtx));
        unitTests.add(new UT_rstypes(this, mRes, mCtx));
        unitTests.add(new UT_alloc(this, mRes, mCtx));
        unitTests.add(new UT_refcount(this, mRes, mCtx));
        unitTests.add(new UT_foreach(this, mRes, mCtx));
        unitTests.add(new UT_noroot(this, mRes, mCtx));
        unitTests.add(new UT_atomic(this, mRes, mCtx));
        unitTests.add(new UT_struct(this, mRes, mCtx));
        unitTests.add(new UT_math(this, mRes, mCtx));
        unitTests.add(new UT_math_conformance(this, mRes, mCtx));
        unitTests.add(new UT_element(this, mRes, mCtx));
        unitTests.add(new UT_sampler(this, mRes, mCtx));
        unitTests.add(new UT_program_store(this, mRes, mCtx));
        unitTests.add(new UT_program_raster(this, mRes, mCtx));
        unitTests.add(new UT_mesh(this, mRes, mCtx));
        unitTests.add(new UT_fp_mad(this, mRes, mCtx));

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
            listElem.text = Allocation.createFromString(mRS, uta[i].name, Allocation.USAGE_SCRIPT);
            listElem.result = uta[i].getResult();
            mListAllocs.set(listElem, i, false);
            uta[i].setItem(listElem);
        }

        mListAllocs.copyAll();

        mScript.bind_gList(mListAllocs);

        mFont = Font.create(mRS, mRes, "serif", Font.Style.BOLD, 8);
        mScript.set_gFont(mFont);

        mRS.bindRootScript(mScript);

        test_iter = unitTests.listIterator();
        refreshTestResults(); /* Kick off the first test */

        TimerTask pTask = new TimerTask() {
            public void run() {
                refreshTestResults();
            }
        };

        mTimer = new Timer();
        mTimer.schedule(pTask, RS_TIMER_PERIOD, RS_TIMER_PERIOD);
    }

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

        if (!stopTesting && activeTest == null) {
            if (test_iter.hasNext()) {
                activeTest = test_iter.next();
                activeTest.start();
                /* This routine will only get called once when a new test
                 * should start running. The message handler in UnitTest.java
                 * ensures this. */
            }
            else {
                if (mTimer != null) {
                    mTimer.cancel();
                    mTimer.purge();
                    mTimer = null;
                }
            }
        }
    }

    public void refreshTestResults() {
        checkAndRunNextTest();

        if (mListAllocs != null && mScript != null && mRS != null) {
            mListAllocs.copyAll();

            mScript.bind_gList(mListAllocs);
            mRS.bindRootScript(mScript);
        }
    }

    public void cleanup() {
        stopTesting = true;
        UnitTest t = activeTest;

        /* Stop periodic refresh of testing */
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }

        /* Wait to exit until we finish the current test */
        if (t != null) {
            try {
                t.join();
            }
            catch (InterruptedException e) {
            }
            t = null;
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
