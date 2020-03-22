package com.google.android.systemui;

import android.content.Context;
import android.view.View;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.StatusBar;
import java.util.ArrayList;

public class OpaEnableDispatcher {
    private final Context mContext;

    public OpaEnableDispatcher(Context context) {
        mContext = context;
    }

    public void refreshOpa() {
        dispatchUnchecked(OpaUtils.shouldEnable(mContext));
    }

    private void dispatchUnchecked(boolean z) {
        StatusBar statusBar = (StatusBar) ((SystemUIApplication) mContext.getApplicationContext()).getComponent(StatusBar.class);
        if (statusBar != null) {
            ArrayList<View> views = statusBar.getNavigationBarView().getHomeButton().getViews();
            for (int i = 0; i < views.size(); i++) {
                ((OpaLayout) views.get(i)).setOpaEnabled(z);
            }
        }
    }
}
