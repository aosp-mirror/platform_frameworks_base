/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.recents.misc;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.util.MutableBoolean;
import android.view.Display;
import android.view.IDockedStackListener;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManager.KeyboardShortcutsReceiver;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.app.AssistUtils;
import com.android.internal.os.BackgroundThread;
import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsImpl;
import com.android.systemui.statusbar.policy.UserInfoController;

import java.util.List;

/**
 * Acts as a shim around the real system services that we need to access data from, and provides
 * a point of injection when testing UI.
 */
public class SystemServicesProxy {
    final static String TAG = "SystemServicesProxy";

    final static BitmapFactory.Options sBitmapOptions;
    static {
        sBitmapOptions = new BitmapFactory.Options();
        sBitmapOptions.inMutable = true;
        sBitmapOptions.inPreferredConfig = Bitmap.Config.RGB_565;
    }

    private static SystemServicesProxy sSystemServicesProxy;

    AccessibilityManager mAccm;
    ActivityManager mAm;
    IActivityManager mIam;
    PackageManager mPm;
    IPackageManager mIpm;
    private final IDreamManager mDreamManager;
    private final Context mContext;
    AssistUtils mAssistUtils;
    WindowManager mWm;
    IWindowManager mIwm;
    UserManager mUm;
    Display mDisplay;
    String mRecentsPackage;
    private int mCurrentUserId;

    boolean mIsSafeMode;

    int mDummyThumbnailWidth;
    int mDummyThumbnailHeight;
    Paint mBgProtectionPaint;
    Canvas mBgProtectionCanvas;

    private final Runnable mGcRunnable = new Runnable() {
        @Override
        public void run() {
            System.gc();
            System.runFinalization();
        }
    };

    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            (String name, Drawable picture, String userAccount) ->
                    mCurrentUserId = mAm.getCurrentUser();

    /** Private constructor */
    private SystemServicesProxy(Context context) {
        mContext = context.getApplicationContext();
        mAccm = AccessibilityManager.getInstance(context);
        mAm = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManager.getService();
        mPm = context.getPackageManager();
        mIpm = AppGlobals.getPackageManager();
        mAssistUtils = new AssistUtils(context);
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mIwm = WindowManagerGlobal.getWindowManagerService();
        mUm = UserManager.get(context);
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mDisplay = mWm.getDefaultDisplay();
        mRecentsPackage = context.getPackageName();
        mIsSafeMode = mPm.isSafeMode();
        mCurrentUserId = mAm.getCurrentUser();

        // Get the dummy thumbnail width/heights
        Resources res = context.getResources();
        int wId = com.android.internal.R.dimen.thumbnail_width;
        int hId = com.android.internal.R.dimen.thumbnail_height;
        mDummyThumbnailWidth = res.getDimensionPixelSize(wId);
        mDummyThumbnailHeight = res.getDimensionPixelSize(hId);

        // Create the protection paints
        mBgProtectionPaint = new Paint();
        mBgProtectionPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_ATOP));
        mBgProtectionPaint.setColor(0xFFffffff);
        mBgProtectionCanvas = new Canvas();

        // Since SystemServicesProxy can be accessed from a per-SysUI process component, create a
        // per-process listener to keep track of the current user id to reduce the number of binder
        // calls to fetch it.
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback(mOnUserInfoChangedListener);
    }

    /**
     * Returns the single instance of the {@link SystemServicesProxy}.
     * This should only be called on the main thread.
     */
    public static synchronized SystemServicesProxy getInstance(Context context) {
        if (sSystemServicesProxy == null) {
            sSystemServicesProxy = new SystemServicesProxy(context);
        }
        return sSystemServicesProxy;
    }

    /**
     * Requests a gc() from the background thread.
     */
    public void gc() {
        BackgroundThread.getHandler().post(mGcRunnable);
    }

    /**
     * Returns whether the recents activity is currently visible.
     */
    public boolean isRecentsActivityVisible() {
        return isRecentsActivityVisible(null);
    }

    /**
     * Returns whether the recents activity is currently visible.
     *
     * @param isHomeStackVisible if provided, will return whether the home stack is visible
     *                           regardless of the recents visibility
     *
     * TODO(winsonc): Refactor this check to just use the recents activity lifecycle
     */
    public boolean isRecentsActivityVisible(MutableBoolean isHomeStackVisible) {
        if (mIam == null) return false;

        try {
            List<StackInfo> stackInfos = mIam.getAllStackInfos();
            ActivityManager.StackInfo homeStackInfo = null;
            ActivityManager.StackInfo fullscreenStackInfo = null;
            ActivityManager.StackInfo recentsStackInfo = null;
            for (int i = 0; i < stackInfos.size(); i++) {
                final StackInfo stackInfo = stackInfos.get(i);
                final WindowConfiguration winConfig = stackInfo.configuration.windowConfiguration;
                final int activityType = winConfig.getActivityType();
                final int windowingMode = winConfig.getWindowingMode();
                if (homeStackInfo == null && activityType == ACTIVITY_TYPE_HOME) {
                    homeStackInfo = stackInfo;
                } else if (fullscreenStackInfo == null && activityType == ACTIVITY_TYPE_STANDARD
                        && (windowingMode == WINDOWING_MODE_FULLSCREEN
                            || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY)) {
                    fullscreenStackInfo = stackInfo;
                } else if (recentsStackInfo == null && activityType == ACTIVITY_TYPE_RECENTS) {
                    recentsStackInfo = stackInfo;
                }
            }
            boolean homeStackVisibleNotOccluded = isStackNotOccluded(homeStackInfo,
                    fullscreenStackInfo);
            boolean recentsStackVisibleNotOccluded = isStackNotOccluded(recentsStackInfo,
                    fullscreenStackInfo);
            if (isHomeStackVisible != null) {
                isHomeStackVisible.value = homeStackVisibleNotOccluded;
            }
            ComponentName topActivity = recentsStackInfo != null ?
                    recentsStackInfo.topActivity : null;
            return (recentsStackVisibleNotOccluded && topActivity != null
                    && topActivity.getPackageName().equals(RecentsImpl.RECENTS_PACKAGE)
                    && Recents.RECENTS_ACTIVITIES.contains(topActivity.getClassName()));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isStackNotOccluded(ActivityManager.StackInfo stackInfo,
            ActivityManager.StackInfo fullscreenStackInfo) {
        boolean stackVisibleNotOccluded = stackInfo == null || stackInfo.visible;
        if (fullscreenStackInfo != null && stackInfo != null) {
            boolean isFullscreenStackOccludingg = fullscreenStackInfo.visible &&
                    fullscreenStackInfo.position > stackInfo.position;
            stackVisibleNotOccluded &= !isFullscreenStackOccludingg;
        }
        return stackVisibleNotOccluded;
    }

    /**
     * Returns whether this device is in the safe mode.
     */
    public boolean isInSafeMode() {
        return mIsSafeMode;
    }

    /** Moves an already resumed task to the side of the screen to initiate split screen. */
    public boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode,
            Rect initialBounds) {
        if (mIam == null) {
            return false;
        }

        try {
            return mIam.setTaskWindowingModeSplitScreenPrimary(taskId, createMode, true /* onTop */,
                    false /* animate */, initialBounds, true /* showRecents */);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public ActivityManager.StackInfo getSplitScreenPrimaryStack() {
        try {
            return mIam.getStackInfo(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_UNDEFINED);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * @return whether there are any docked tasks for the current user.
     */
    public boolean hasDockedTask() {
        if (mIam == null) return false;

        ActivityManager.StackInfo stackInfo = getSplitScreenPrimaryStack();
        if (stackInfo != null) {
            int userId = getCurrentUser();
            boolean hasUserTask = false;
            for (int i = stackInfo.taskUserIds.length - 1; i >= 0 && !hasUserTask; i--) {
                hasUserTask = (stackInfo.taskUserIds[i] == userId);
            }
            return hasUserTask;
        }
        return false;
    }

    /**
     * Returns whether there is a soft nav bar.
     */
    public boolean hasSoftNavigationBar() {
        try {
            return mIwm.hasNavigationBar();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Returns whether the device has a transposed nav bar (on the right of the screen) in the
     * current display orientation.
     */
    public boolean hasTransposedNavigationBar() {
        Rect insets = new Rect();
        getStableInsets(insets);
        return insets.right > 0;
    }

    /** Set the task's windowing mode. */
    public void setTaskWindowingMode(int taskId, int windowingMode) {
        if (mIam == null) return;

        try {
            mIam.setTaskWindowingMode(taskId, windowingMode, false /* onTop */);
        } catch (RemoteException | IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns whether the provided {@param userId} represents the system user.
     */
    public boolean isSystemUser(int userId) {
        return userId == UserHandle.USER_SYSTEM;
    }

    /**
     * Returns the current user id.  Used instead of KeyguardUpdateMonitor in SystemUI components
     * that run in the non-primary SystemUI process.
     */
    public int getCurrentUser() {
        return mCurrentUserId;
    }

    /**
     * Returns the processes user id.
     */
    public int getProcessUser() {
        if (mUm == null) return 0;
        return mUm.getUserHandle();
    }

    /**
     * Returns whether touch exploration is currently enabled.
     */
    public boolean isTouchExplorationEnabled() {
        if (mAccm == null) return false;

        return mAccm.isEnabled() && mAccm.isTouchExplorationEnabled();
    }

    /**
     * Returns whether the current task is in screen-pinning mode.
     */
    public boolean isScreenPinningActive() {
        if (mIam == null) return false;

        try {
            return mIam.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_PINNED;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns the smallest width/height.
     */
    public int getDeviceSmallestWidth() {
        if (mDisplay == null) return 0;

        Point smallestSizeRange = new Point();
        Point largestSizeRange = new Point();
        mDisplay.getCurrentSizeRange(smallestSizeRange, largestSizeRange);
        return smallestSizeRange.x;
    }

    /**
     * Returns the current display rect in the current display orientation.
     */
    public Rect getDisplayRect() {
        Rect displayRect = new Rect();
        if (mDisplay == null) return displayRect;

        Point p = new Point();
        mDisplay.getRealSize(p);
        displayRect.set(0, 0, p.x, p.y);
        return displayRect;
    }

    /**
     * Returns the window rect for the RecentsActivity, based on the dimensions of the recents stack
     */
    public Rect getWindowRect() {
        Rect windowRect = new Rect();
        if (mIam == null) return windowRect;

        try {
            // Use the recents stack bounds, fallback to fullscreen stack if it is null
            ActivityManager.StackInfo stackInfo =
                    mIam.getStackInfo(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_RECENTS);
            if (stackInfo == null) {
                stackInfo = mIam.getStackInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            }
            if (stackInfo != null) {
                windowRect.set(stackInfo.bounds);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        } finally {
            return windowRect;
        }
    }

    public void startActivityAsUserAsync(Intent intent, ActivityOptions opts) {
        mUiOffloadThread.submit(() -> mContext.startActivityAsUser(intent,
                opts != null ? opts.toBundle() : null, UserHandle.CURRENT));
    }

    /** Starts an in-place animation on the front most application windows. */
    public void startInPlaceAnimationOnFrontMostApplication(ActivityOptions opts) {
        if (mIam == null) return;

        try {
            mIam.startInPlaceAnimationOnFrontMostApplication(
                    opts == null ? null : opts.toBundle());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerDockedStackListener(IDockedStackListener listener) {
        if (mWm == null) return;

        try {
            mIwm.registerDockedStackListener(listener);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Calculates the size of the dock divider in the current orientation.
     */
    public int getDockedDividerSize(Context context) {
        Resources res = context.getResources();
        int dividerWindowWidth = res.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        int dividerInsets = res.getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
        return dividerWindowWidth - 2 * dividerInsets;
    }

    public void requestKeyboardShortcuts(
            Context context, KeyboardShortcutsReceiver receiver, int deviceId) {
        mWm.requestAppKeyboardShortcuts(receiver, deviceId);
    }

    public void getStableInsets(Rect outStableInsets) {
        if (mWm == null) return;

        try {
            mIwm.getStableInsets(Display.DEFAULT_DISPLAY, outStableInsets);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the visibility of recents.
     */
    public void setRecentsVisibility(final boolean visible) {
        mUiOffloadThread.submit(() -> {
            try {
                mIwm.setRecentsVisibility(visible);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to reach window manager", e);
            }
        });
    }

    /**
     * Updates the visibility of the picture-in-picture.
     */
    public void setPipVisibility(final boolean visible) {
        mUiOffloadThread.submit(() -> {
            try {
                mIwm.setPipVisibility(visible);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to reach window manager", e);
            }
        });
    }

    public boolean isDreaming() {
        try {
            return mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
        }
        return false;
    }

    public void awakenDreamsAsync() {
        mUiOffloadThread.submit(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
    }

    public interface StartActivityFromRecentsResultListener {
        void onStartActivityResult(boolean succeeded);
    }
}
