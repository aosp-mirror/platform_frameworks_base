package com.android.keyguard;

import android.content.Context;
import android.util.AttributeSet;

public class KeyguardSimpleHostView extends KeyguardViewBase {

    public KeyguardSimpleHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void verifyUnlock() {
        // TODO Auto-generated method stub

    }

    @Override
    public void cleanUp() {
        // TODO Auto-generated method stub

    }

    @Override
    public long getUserActivityTimeout() {
        // TODO Auto-generated method stub
        return 0;
    }

}
