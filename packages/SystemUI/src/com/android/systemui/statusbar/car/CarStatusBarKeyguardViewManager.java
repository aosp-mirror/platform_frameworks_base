package com.android.systemui.statusbar.car;

import android.content.Context;
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

public class CarStatusBarKeyguardViewManager extends StatusBarKeyguardViewManager {

    protected boolean mShouldHideNavBar;

    public CarStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        super(context, callback, lockPatternUtils);
        mShouldHideNavBar =context.getResources()
                .getBoolean(R.bool.config_hideNavWhenKeyguardBouncerShown);
    }

    @Override
    protected void updateNavigationBarVisibility(boolean navBarVisible) {
        if(!mShouldHideNavBar) {
            return;
        }
        CarStatusBar statusBar = (CarStatusBar) mStatusBar;
        statusBar.setNavBarVisibility(navBarVisible ? View.VISIBLE : View.GONE);
    }

    /**
     * Car is a multi-user system.  There's a cancel button on the bouncer that allows the user to
     * go back to the user switcher and select another user.  Different user may have different
     * security mode which requires bouncer container to be resized.  For this reason, the bouncer
     * view is destroyed on cancel.
     */
    @Override
    protected boolean shouldDestroyViewOnReset() {
        return true;
    }
}
