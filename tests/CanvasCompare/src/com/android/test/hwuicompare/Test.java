package com.android.test.hwuicompare;

import com.android.test.hwuicompare.AutomaticActivity.FinalCallback;

import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;

public class Test extends ActivityInstrumentationTestCase2<AutomaticActivity> {
    AutomaticActivity mActivity;
    private Bundle mBundle;

    public Test() {
        super(AutomaticActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mBundle = new Bundle();
        mActivity = getActivity();
        mActivity.setFinalCallback(new FinalCallback() {

            @Override
            void report(String key, float value) {
                mBundle.putFloat(key, value);
            }
            @Override
            void complete() {
                synchronized(mBundle) {
                    mBundle.notify();
                }
            }
        });
    }

    public void testCanvas() {
        synchronized(mBundle) {
            try {
                mBundle.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        getInstrumentation().sendStatus(0, mBundle);
    }
}
