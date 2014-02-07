package com.android.keyguard;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class KeyguardSimpleHostView extends KeyguardViewBase {

    public KeyguardSimpleHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void showBouncer(boolean show) {
        super.showBouncer(show);
        if (show) {
            getSecurityContainer().showBouncer(250);
        } else {
            getSecurityContainer().hideBouncer(250);
        }
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
        return -1; // not used
    }

    @Override
    protected void onUserSwitching(boolean switching) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void onCreateOptions(Bundle options) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void onExternalMotionEvent(MotionEvent event) {
        // TODO Auto-generated method stub
    }

}
