/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shortcut;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.settingslib.accessibility.AccessibilityUtils;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.stackdivider.DividerView;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;

import java.util.List;
import java.util.Set;

/**
 * Dispatches shortcut to System UI components
 */
public class ShortcutKeyDispatcher extends SystemUI
        implements ShortcutKeyServiceProxy.Callbacks {

    private static final String TAG = "ShortcutKeyDispatcher";

    private ShortcutKeyServiceProxy mShortcutKeyServiceProxy = new ShortcutKeyServiceProxy(this);
    private IWindowManager mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
    private IActivityManager mActivityManager = ActivityManager.getService();

    protected final long META_MASK = ((long) KeyEvent.META_META_ON) << Integer.SIZE;
    protected final long ALT_MASK = ((long) KeyEvent.META_ALT_ON) << Integer.SIZE;
    protected final long CTRL_MASK = ((long) KeyEvent.META_CTRL_ON) << Integer.SIZE;
    protected final long SHIFT_MASK = ((long) KeyEvent.META_SHIFT_ON) << Integer.SIZE;

    protected final long SC_DOCK_LEFT = META_MASK | KeyEvent.KEYCODE_LEFT_BRACKET;
    protected final long SC_DOCK_RIGHT = META_MASK | KeyEvent.KEYCODE_RIGHT_BRACKET;

    /**
     * Registers a shortcut key to window manager.
     * @param shortcutCode packed representation of shortcut key code and meta information
     */
    public void registerShortcutKey(long shortcutCode) {
        try {
            mWindowManagerService.registerShortcutKey(shortcutCode, mShortcutKeyServiceProxy);
        } catch (RemoteException e) {
            // Do nothing
        }
    }

    @Override
    public void onShortcutKeyPressed(long shortcutCode) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        if ((shortcutCode == SC_DOCK_LEFT || shortcutCode == SC_DOCK_RIGHT)
                && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            handleDockKey(shortcutCode);
        }
    }

    @Override
    public void start() {
        registerShortcutKey(SC_DOCK_LEFT);
        registerShortcutKey(SC_DOCK_RIGHT);
    }

    private void handleDockKey(long shortcutCode) {
        try {
            int dockSide = mWindowManagerService.getDockedStackSide();
            if (dockSide == WindowManager.DOCKED_INVALID) {
                // If there is no window docked, we dock the top-most window.
                Recents recents = getComponent(Recents.class);
                int dockMode = (shortcutCode == SC_DOCK_LEFT)
                        ? ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT
                        : ActivityManager.DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
                List<ActivityManager.RecentTaskInfo> taskList =
                        SystemServicesProxy.getInstance(mContext).getRecentTasks(1,
                                UserHandle.USER_CURRENT, false, new ArraySet<>());
                recents.showRecentApps(
                        false /* triggeredFromAltTab */,
                        false /* fromHome */);
                if (!taskList.isEmpty()) {
                    SystemServicesProxy.getInstance(mContext).startTaskInDockedMode(
                            taskList.get(0).id, dockMode);
                }
            } else {
                // If there is already a docked window, we respond by resizing the docking pane.
                DividerView dividerView = getComponent(Divider.class).getView();
                DividerSnapAlgorithm snapAlgorithm = dividerView.getSnapAlgorithm();
                int dividerPosition = dividerView.getCurrentPosition();
                DividerSnapAlgorithm.SnapTarget currentTarget =
                        snapAlgorithm.calculateNonDismissingSnapTarget(dividerPosition);
                DividerSnapAlgorithm.SnapTarget target = (shortcutCode == SC_DOCK_LEFT)
                        ? snapAlgorithm.getPreviousTarget(currentTarget)
                        : snapAlgorithm.getNextTarget(currentTarget);
                dividerView.startDragging(true /* animate */, false /* touching */);
                dividerView.stopDragging(target.position, 0f, false /* avoidDismissStart */,
                        true /* logMetrics */);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "handleDockKey() failed.");
        }
    }
}
