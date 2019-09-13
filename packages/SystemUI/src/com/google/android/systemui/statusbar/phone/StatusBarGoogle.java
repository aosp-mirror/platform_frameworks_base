package com.google.android.systemui.statusbar.phone;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.google.android.systemui.NotificationLockscreenUserManagerGoogle;
import com.google.android.systemui.smartspace.SmartSpaceController;
import java.io.FileDescriptor;
import java.io.PrintWriter;

public class StatusBarGoogle extends StatusBar {

    @Override
    public void start() {
        super.start();
        ((NotificationLockscreenUserManagerGoogle) Dependency.get(NotificationLockscreenUserManager.class)).updateAodVisibilitySettings();
    }

    @Override
    public void setLockscreenUser(int i) {
        super.setLockscreenUser(i);
        SmartSpaceController.get(this.mContext).reloadData();
    }

    @Override
    public void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        super.dump(fileDescriptor, printWriter, strArr);
        SmartSpaceController.get(this.mContext).dump(fileDescriptor, printWriter, strArr);
    }

}
