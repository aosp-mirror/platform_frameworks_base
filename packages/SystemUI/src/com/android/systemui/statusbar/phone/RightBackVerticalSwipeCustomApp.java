package com.android.systemui.statusbar.phone;

import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.provider.Settings;

public class RightBackVerticalSwipeCustomApp extends LeftBackSwipeCustomApp {

    @Override
    protected void setPackage(String packageName, String friendlyAppString) {
        Settings.System.putStringForUser(getContentResolver(),
                Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_ACTION, packageName,
                UserHandle.USER_CURRENT);
        Settings.System.putStringForUser(getContentResolver(),
                Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_FR_ACTION, friendlyAppString,
                UserHandle.USER_CURRENT);
    }

    @Override
    protected void setPackageActivity(ActivityInfo ai) {
        Settings.System.putStringForUser(
                getContentResolver(), Settings.System.RIGHT_VERTICAL_BACK_SWIPE_APP_ACTIVITY_ACTION,
                ai != null ? ai.name : "NONE",
                UserHandle.USER_CURRENT);
    }
}
