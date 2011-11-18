package com.android.frameworkperf;

import android.app.Activity;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;

public class FrameworkPerfTest extends ActivityInstrumentationTestCase2<FrameworkPerfActivity> {

    private static final int TEST_TIMEOUT = 15 * 60 * 1000; //15 minutes

    public FrameworkPerfTest() {
        super("com.android.frameworkperf", FrameworkPerfActivity.class);
    }

    public void testFrameworkPerf() {
        final FrameworkPerfActivity activity = getActivity();
        synchronized (activity.mResultNotifier) {
            getInstrumentation().runOnMainSync(new Runnable() {
                @Override
                public void run() {
                    activity.startRunning();
                }
            });
            try {
                activity.mResultNotifier.wait(TEST_TIMEOUT);
            } catch (InterruptedException e) {
                fail("test interrupted.");
            }
        }
        Bundle testResult = new Bundle();
        synchronized (activity.mResults) {
            assertTrue("test results were empty.", activity.mResults.size() > 0);
            for (RunResult result : activity.mResults) {
                testResult.putString(result.name, String.format("%f,%d,%d,%f,%d,%d",
                        result.getFgMsPerOp(), result.fgOps, result.fgTime,
                        result.getBgMsPerOp(), result.bgOps, result.bgTime));
            }
        }
        getInstrumentation().sendStatus(Activity.RESULT_OK, testResult);
    }
}
