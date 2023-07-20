/*
 * Copyright (C) 2020-2021 The Dirty Unicorns Project
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

package com.android.systemui.statusbar.policy;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.wallpaper.WallpaperService;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.internal.os.BackgroundThread;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.CommandQueue;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class TaskHelper implements CommandQueue.Callbacks, KeyguardStateController.Callback,
        ConfigurationController.ConfigurationListener {
    public interface Callback {
        public void onHomeVisibilityChanged(boolean isVisible);
    }

    private static final String TAG = TaskHelper.class.getSimpleName();
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SETTINGS = "com.android.settings";

    private static final String[] DEFAULT_HOME_CHANGE_ACTIONS = new String[] {
            PackageManagerWrapper.ACTION_PREFERRED_ACTIVITY_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED
    };

    @Nullable
    private ComponentName mDefaultHome;
    private final ComponentName mRecentsComponentName;
    private int mRunningTaskId;
    private ComponentName mTaskComponentName;
    private Context mContext;
    private final KeyguardStateController mKeyguardStateController;
    private PackageManager mPm;
    private boolean mKeyguardShowing;
    private TaskHelperHandler mHandler;
    private String mForegroundAppPackageName;
    private IActivityTaskManager mActivityTaskManager;
    private final Injector mInjector;
    private static final int MSG_UPDATE_FOREGROUND_APP = 0;
    private static final int MSG_UPDATE_CALLBACKS = 1;
    private final List<Callback> mCallbacks = new ArrayList<>();

    private final BroadcastReceiver mDefaultHomeBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mDefaultHome = getCurrentDefaultHome();
        }
    };

    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP);
        }
    };

    private final class TaskHelperHandler extends Handler {
        public TaskHelperHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_FOREGROUND_APP:
                    updateForegroundApp();
                    break;
                case MSG_UPDATE_CALLBACKS:
                    updateCallbacks(isLauncherShowing());
                    break;
            }
        }
    }

    private void updateForegroundApp() {
        // The ActivityTaskManager's lock tends to get contended, so this is done in a background
        // thread
        mInjector.getBackgroundThreadHandler().post(new Runnable() {
            public void run() {
                try {
                    final boolean isHomeShowingBefore = mTaskComponentName != null
                            ? isLauncherShowing()
                            : false;
                    // The foreground app is the top activity of the focused tasks stack.
                    final RootTaskInfo info = mActivityTaskManager.getFocusedRootTaskInfo();
                    mTaskComponentName = info != null ? info.topActivity : null;
                    if (mTaskComponentName == null) {
                        return;
                    }
                    mForegroundAppPackageName = mTaskComponentName.getPackageName();
                    mRunningTaskId = info.childTaskIds[info.childTaskIds.length - 1];
                    final boolean isHomeShowingAfter = isLauncherShowing();
                    if (isHomeShowingBefore != isHomeShowingAfter) {
                        // MUST call back into main thread
                        mHandler.sendEmptyMessage(MSG_UPDATE_CALLBACKS);
                    }
                } catch (RemoteException e) {
                    // Nothing to do
                }
            }
        });
    }

    public static class Injector {
        public Handler getBackgroundThreadHandler() {
            return BackgroundThread.getHandler();
        }
    }

    @Inject
    public TaskHelper(Context context) {
        mContext = context;
        mActivityTaskManager = ActivityTaskManager.getService();
        mInjector = new Injector();
        mHandler = new TaskHelperHandler(Looper.getMainLooper());
        IntentFilter homeFilter = new IntentFilter();
        for (String action : DEFAULT_HOME_CHANGE_ACTIONS) {
            homeFilter.addAction(action);
        }
        mDefaultHome = getCurrentDefaultHome();
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        context.registerReceiver(mDefaultHomeBroadcastReceiver, homeFilter);
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
        Dependency.get(CommandQueue.class).addCallback(this);
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mKeyguardStateController.addCallback(this);
        mPm = context.getPackageManager();
        Dependency.get(ConfigurationController.class).addCallback(this);
        updateForegroundApp();
    }

    public void addCallback(TaskHelper.Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(TaskHelper.Callback callback) {
        mCallbacks.remove(callback);
    }

    private void updateCallbacks(boolean isShowing) {
        mCallbacks.stream()
                .forEach(o -> ((Callback) o).onHomeVisibilityChanged(isShowing));
    }

    @Nullable
    private ComponentName getCurrentDefaultHome() {
        List<ResolveInfo> homeActivities = new ArrayList<>();
        ComponentName defaultHome = PackageManagerWrapper.getInstance()
                .getHomeActivities(homeActivities);
        if (defaultHome != null) {
            return defaultHome;
        }

        int topPriority = Integer.MIN_VALUE;
        ComponentName topComponent = null;
        for (ResolveInfo resolveInfo : homeActivities) {
            if (resolveInfo.priority > topPriority) {
                topComponent = resolveInfo.activityInfo.getComponentName();
                topPriority = resolveInfo.priority;
            } else if (resolveInfo.priority == topPriority) {
                topComponent = null;
            }
        }
        return topComponent;
    }

    @Override
    public void killForegroundApp() {
        if (isLauncherShowing()
                || !(mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.FORCE_STOP_PACKAGES) == PackageManager.PERMISSION_GRANTED)
                || isLockTaskOn()
                || mKeyguardShowing
                || mTaskComponentName == null
                || mTaskComponentName.equals(mRecentsComponentName)
                || mForegroundAppPackageName.equals(SYSTEMUI)
                || isPackageLiveWalls(mForegroundAppPackageName)) {
            return;
        }

        boolean killed = false;
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.forceStopPackage(mForegroundAppPackageName, UserHandle.USER_CURRENT); // kill
                                                                                                // app
            iam.removeTask(mRunningTaskId); // remove app from recents
            killed = true;
        } catch (RemoteException e) {
            killed = false;
        }
        if (killed) {
            String appLabel = getForegroundAppLabel();
            if (appLabel == null || appLabel.length() == 0) {
                appLabel = mContext.getString(R.string.empty_app_killed);
            }
            String toasty = mContext.getString(R.string.task_helper_app_killed, appLabel);
            Toast.makeText(mContext, toasty, Toast.LENGTH_SHORT).show();

            // Refresh current app info just in case TaskStackChangeListener callbacks don't get called properly
            mHandler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP);
        }
    }

    @Override
    public void onKeyguardShowingChanged() {
        mKeyguardShowing = mKeyguardStateController.isShowing();
        mHandler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP);
    }

    public void onOverlayChanged() {
        // refresh callback states on theme change. Allow a slight delay
        // so statusbar can reinflate and settle down
        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_CALLBACKS, 500);
    }

    public String getForegroundApp() {
        if (mForegroundAppPackageName == null) return "";
        return mForegroundAppPackageName;
    }

    public String getForegroundAppLabel() {
        try {
            return mPm.getActivityInfo(mTaskComponentName, 0).applicationInfo
                    .loadLabel(mPm).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isLauncherShowing() {
        // component name can be null during custom launcher installation process
        return ((mTaskComponentName != null && mTaskComponentName.equals(mDefaultHome))
                // boot time check
                || (mDefaultHome != null && mDefaultHome.getPackageName().equals(SETTINGS)));
    }

    private boolean isPackageLiveWalls(String pkg) {
        if (pkg == null) {
            return false;
        }
        List<ResolveInfo> liveWallsList = mPm.queryIntentServices(
                new Intent(WallpaperService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA);
        if (liveWallsList == null) {
            return false;
        }
        for (ResolveInfo info : liveWallsList) {
            if (info.serviceInfo != null) {
                String packageName = info.serviceInfo.packageName;
                if (TextUtils.equals(pkg, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLockTaskOn() {
        try {
            return ActivityManager.getService().isInLockTaskMode();
        } catch (Exception e) {
        }
        return false;
    }
}
