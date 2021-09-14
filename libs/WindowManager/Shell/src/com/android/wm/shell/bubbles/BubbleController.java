/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_BOTTOM;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_LEFT;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_NONE;
import static com.android.wm.shell.bubbles.BubblePositioner.TASKBAR_POSITION_RIGHT;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_BLOCKED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_GROUP_CANCELLED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_INVALID_INTENT;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NOTIF_CANCEL;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NO_BUBBLE_UP;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_NO_LONGER_BUBBLE;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_PACKAGE_REMOVED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_SHORTCUT_REMOVED;
import static com.android.wm.shell.bubbles.Bubbles.DISMISS_USER_CHANGED;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseSetArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.WindowContainerTransaction;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
public class BubbleController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleController" : TAG_BUBBLES;

    // TODO(b/173386799) keep in sync with Launcher3 and also don't do a broadcast
    public static final String TASKBAR_CHANGED_BROADCAST = "taskbarChanged";
    public static final String EXTRA_TASKBAR_CREATED = "taskbarCreated";
    public static final String EXTRA_BUBBLE_OVERFLOW_OPENED = "bubbleOverflowOpened";
    public static final String EXTRA_TASKBAR_VISIBLE = "taskbarVisible";
    public static final String EXTRA_TASKBAR_POSITION = "taskbarPosition";
    public static final String EXTRA_TASKBAR_ICON_SIZE = "taskbarIconSize";
    public static final String EXTRA_TASKBAR_BUBBLE_XY = "taskbarBubbleXY";
    public static final String EXTRA_TASKBAR_SIZE = "taskbarSize";
    public static final String LEFT_POSITION = "Left";
    public static final String RIGHT_POSITION = "Right";
    public static final String BOTTOM_POSITION = "Bottom";

    private final Context mContext;
    private final BubblesImpl mImpl = new BubblesImpl();
    private Bubbles.BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private final BubbleDataRepository mDataRepository;
    private final WindowManagerShellWrapper mWindowManagerShellWrapper;
    private final LauncherApps mLauncherApps;
    private final IStatusBarService mBarService;
    private final WindowManager mWindowManager;
    private final TaskStackListenerImpl mTaskStackListener;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final DisplayController mDisplayController;
    private final SyncTransactionQueue mSyncQueue;

    // Used to post to main UI thread
    private final ShellExecutor mMainExecutor;
    private final Handler mMainHandler;

    private BubbleLogger mLogger;
    private BubbleData mBubbleData;
    private View mBubbleScrim;
    @Nullable private BubbleStackView mStackView;
    private BubbleIconFactory mBubbleIconFactory;
    private BubblePositioner mBubblePositioner;
    private Bubbles.SysuiProxy mSysuiProxy;

    // Tracks the id of the current (foreground) user.
    private int mCurrentUserId;
    // Current profiles of the user (e.g. user with a workprofile)
    private SparseArray<UserInfo> mCurrentProfiles;
    // Saves notification keys of active bubbles when users are switched.
    private final SparseSetArray<String> mSavedBubbleKeysPerUser;

    // Used when ranking updates occur and we check if things should bubble / unbubble
    private NotificationListenerService.Ranking mTmpRanking;

    // Callback that updates BubbleOverflowActivity on data change.
    @Nullable private BubbleData.Listener mOverflowListener = null;

    // Typically only load once & after user switches
    private boolean mOverflowDataLoadNeeded = true;

    /**
     * When the shade status changes to SHADE (from anything but SHADE, like LOCKED) we'll select
     * this bubble and expand the stack.
     */
    @Nullable private BubbleEntry mNotifEntryToExpandOnShadeUnlock;

    /** LayoutParams used to add the BubbleStackView to the window manager. */
    private WindowManager.LayoutParams mWmLayoutParams;
    /** Whether or not the BubbleStackView has been added to the WindowManager. */
    private boolean mAddedToWindowManager = false;

    /** Saved screen density, used to detect display size changes in {@link #onConfigChanged}. */
    private int mDensityDpi = Configuration.DENSITY_DPI_UNDEFINED;

    /** Saved screen bounds, used to detect screen size changes in {@link #onConfigChanged}. **/
    private Rect mScreenBounds = new Rect();

    /** Saved font scale, used to detect font size changes in {@link #onConfigChanged}. */
    private float mFontScale = 0;

    /** Saved direction, used to detect layout direction changes @link #onConfigChanged}. */
    private int mLayoutDirection = View.LAYOUT_DIRECTION_UNDEFINED;

    /** Saved insets, used to detect WindowInset changes. */
    private WindowInsets mWindowInsets;

    private boolean mInflateSynchronously;

    /** True when user is in status bar unlock shade. */
    private boolean mIsStatusBarShade = true;

    /**
     * Creates an instance of the BubbleController.
     */
    public static BubbleController create(Context context,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            @Nullable IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            ShellTaskOrganizer organizer,
            DisplayController displayController,
            ShellExecutor mainExecutor,
            Handler mainHandler,
            SyncTransactionQueue syncQueue) {
        BubbleLogger logger = new BubbleLogger(uiEventLogger);
        BubblePositioner positioner = new BubblePositioner(context, windowManager);
        BubbleData data = new BubbleData(context, logger, positioner, mainExecutor);
        return new BubbleController(context, data, synchronizer, floatingContentCoordinator,
                new BubbleDataRepository(context, launcherApps, mainExecutor),
                statusBarService, windowManager, windowManagerShellWrapper, launcherApps,
                logger, taskStackListener, organizer, positioner, displayController, mainExecutor,
                mainHandler, syncQueue);
    }

    /**
     * Testing constructor.
     */
    @VisibleForTesting
    protected BubbleController(Context context,
            BubbleData data,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            FloatingContentCoordinator floatingContentCoordinator,
            BubbleDataRepository dataRepository,
            @Nullable IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            BubbleLogger bubbleLogger,
            TaskStackListenerImpl taskStackListener,
            ShellTaskOrganizer organizer,
            BubblePositioner positioner,
            DisplayController displayController,
            ShellExecutor mainExecutor,
            Handler mainHandler,
            SyncTransactionQueue syncQueue) {
        mContext = context;
        mLauncherApps = launcherApps;
        mBarService = statusBarService == null
                ? IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE))
                : statusBarService;
        mWindowManager = windowManager;
        mWindowManagerShellWrapper = windowManagerShellWrapper;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mDataRepository = dataRepository;
        mLogger = bubbleLogger;
        mMainExecutor = mainExecutor;
        mMainHandler = mainHandler;
        mTaskStackListener = taskStackListener;
        mTaskOrganizer = organizer;
        mSurfaceSynchronizer = synchronizer;
        mCurrentUserId = ActivityManager.getCurrentUser();
        mBubblePositioner = positioner;
        mBubbleData = data;
        mSavedBubbleKeysPerUser = new SparseSetArray<>();
        mBubbleIconFactory = new BubbleIconFactory(context);
        mDisplayController = displayController;
        mSyncQueue = syncQueue;
    }

    public void initialize() {
        mBubbleData.setListener(mBubbleDataListener);
        mBubbleData.setSuppressionChangedListener(this::onBubbleNotificationSuppressionChanged);

        mBubbleData.setPendingIntentCancelledListener(bubble -> {
            if (bubble.getBubbleIntent() == null) {
                return;
            }
            if (bubble.isIntentActive() || mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                bubble.setPendingIntentCanceled();
                return;
            }
            mMainExecutor.execute(() -> removeBubble(bubble.getKey(), DISMISS_INVALID_INTENT));
        });

        try {
            mWindowManagerShellWrapper.addPinnedStackListener(new BubblesImeListener());
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        mBubbleData.setCurrentUserId(mCurrentUserId);

        mTaskOrganizer.addLocusIdListener((taskId, locus, visible) ->
                mBubbleData.onLocusVisibilityChanged(taskId, locus, visible));

        mLauncherApps.registerCallback(new LauncherApps.Callback() {
            @Override
            public void onPackageAdded(String s, UserHandle userHandle) {}

            @Override
            public void onPackageChanged(String s, UserHandle userHandle) {}

            @Override
            public void onPackageRemoved(String s, UserHandle userHandle) {
                // Remove bubbles with this package name, since it has been uninstalled and attempts
                // to open a bubble from an uninstalled app can cause issues.
                mBubbleData.removeBubblesWithPackageName(s, DISMISS_PACKAGE_REMOVED);
            }

            @Override
            public void onPackagesAvailable(String[] strings, UserHandle userHandle, boolean b) {}

            @Override
            public void onPackagesUnavailable(String[] packages, UserHandle userHandle,
                    boolean b) {
                for (String packageName : packages) {
                    // Remove bubbles from unavailable apps. This can occur when the app is on
                    // external storage that has been removed.
                    mBubbleData.removeBubblesWithPackageName(packageName, DISMISS_PACKAGE_REMOVED);
                }
            }

            @Override
            public void onShortcutsChanged(String packageName, List<ShortcutInfo> validShortcuts,
                    UserHandle user) {
                super.onShortcutsChanged(packageName, validShortcuts, user);

                // Remove bubbles whose shortcuts aren't in the latest list of valid shortcuts.
                mBubbleData.removeBubblesWithInvalidShortcuts(
                        packageName, validShortcuts, DISMISS_SHORTCUT_REMOVED);
            }
        }, mMainHandler);

        mTaskStackListener.addListener(new TaskStackListenerCallback() {
            @Override
            public void onTaskMovedToFront(int taskId) {
                if (mSysuiProxy == null) {
                    return;
                }

                mSysuiProxy.isNotificationShadeExpand((expand) -> {
                    mMainExecutor.execute(() -> {
                        int expandedId = INVALID_TASK_ID;
                        if (mStackView != null && mStackView.getExpandedBubble() != null
                                && isStackExpanded() && !mStackView.isExpansionAnimating()
                                && !expand) {
                            expandedId = mStackView.getExpandedBubble().getTaskId();
                        }

                        if (expandedId != INVALID_TASK_ID && expandedId != taskId) {
                            mBubbleData.setExpanded(false);
                        }
                    });
                });
            }

            @Override
            public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                    boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                for (Bubble b : mBubbleData.getBubbles()) {
                    if (task.taskId == b.getTaskId()) {
                        mBubbleData.setSelectedBubble(b);
                        mBubbleData.setExpanded(true);
                        return;
                    }
                }
                for (Bubble b : mBubbleData.getOverflowBubbles()) {
                    if (task.taskId == b.getTaskId()) {
                        promoteBubbleFromOverflow(b);
                        mBubbleData.setExpanded(true);
                        return;
                    }
                }
            }
        });

        mDisplayController.addDisplayChangingController(
                new DisplayChangeController.OnDisplayChangingListener() {
                    @Override
                    public void onRotateDisplay(int displayId, int fromRotation, int toRotation,
                            WindowContainerTransaction t) {
                        // This is triggered right before the rotation is applied
                        if (fromRotation != toRotation) {
                            mBubblePositioner.setRotation(toRotation);
                            if (mStackView != null) {
                                // Layout listener set on stackView will update the positioner
                                // once the rotation is applied
                                mStackView.onOrientationChanged();
                            }
                        }
                    }
                });
    }

    @VisibleForTesting
    public Bubbles asBubbles() {
        return mImpl;
    }

    @VisibleForTesting
    public BubblesImpl.CachedState getImplCachedState() {
        return mImpl.mCachedState;
    }

    public ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    /**
     * Hides the current input method, wherever it may be focused, via InputMethodManagerInternal.
     */
    void hideCurrentInputMethod() {
        try {
            mBarService.hideCurrentInputMethodForBubbles();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void openBubbleOverflow() {
        ensureStackViewCreated();
        mBubbleData.setShowingOverflow(true);
        mBubbleData.setSelectedBubble(mBubbleData.getOverflow());
        mBubbleData.setExpanded(true);
    }

    /** Called when any taskbar state changes (e.g. visibility, position, sizes). */
    private void onTaskbarChanged(Bundle b) {
        if (b == null) {
            return;
        }
        boolean isVisible = b.getBoolean(EXTRA_TASKBAR_VISIBLE, false /* default */);
        String position = b.getString(EXTRA_TASKBAR_POSITION, RIGHT_POSITION /* default */);
        @BubblePositioner.TaskbarPosition int taskbarPosition = TASKBAR_POSITION_NONE;
        switch (position) {
            case LEFT_POSITION:
                taskbarPosition = TASKBAR_POSITION_LEFT;
                break;
            case RIGHT_POSITION:
                taskbarPosition = TASKBAR_POSITION_RIGHT;
                break;
            case BOTTOM_POSITION:
                taskbarPosition = TASKBAR_POSITION_BOTTOM;
                break;
        }
        int[] itemPosition = b.getIntArray(EXTRA_TASKBAR_BUBBLE_XY);
        int iconSize = b.getInt(EXTRA_TASKBAR_ICON_SIZE);
        int taskbarSize = b.getInt(EXTRA_TASKBAR_SIZE);
        Log.w(TAG, "onTaskbarChanged:"
                + " isVisible: " + isVisible
                + " position: " + position
                + " itemPosition: " + itemPosition[0] + "," + itemPosition[1]
                + " iconSize: " + iconSize);
        PointF point = new PointF(itemPosition[0], itemPosition[1]);
        mBubblePositioner.setPinnedLocation(isVisible ? point : null);
        mBubblePositioner.updateForTaskbar(iconSize, taskbarPosition, isVisible, taskbarSize);
        if (mStackView != null) {
            if (isVisible && b.getBoolean(EXTRA_TASKBAR_CREATED, false /* default */)) {
                // If taskbar was created, add and remove the window so that bubbles display on top
                removeFromWindowManagerMaybe();
                addToWindowManagerMaybe();
            }
            mStackView.updateStackPosition();
            mBubbleIconFactory = new BubbleIconFactory(mContext);
            mStackView.onDisplaySizeChanged();
        }
        if (b.getBoolean(EXTRA_BUBBLE_OVERFLOW_OPENED, false)) {
            openBubbleOverflow();
        }
    }

    /**
     * Called when the status bar has become visible or invisible (either permanently or
     * temporarily).
     */
    private void onStatusBarVisibilityChanged(boolean visible) {
        if (mStackView != null) {
            // Hide the stack temporarily if the status bar has been made invisible, and the stack
            // is collapsed. An expanded stack should remain visible until collapsed.
            mStackView.setTemporarilyInvisible(!visible && !isStackExpanded());
        }
    }

    private void onZenStateChanged() {
        for (Bubble b : mBubbleData.getBubbles()) {
            b.setShowDot(b.showInShade());
        }
    }

    private void onStatusBarStateChanged(boolean isShade) {
        mIsStatusBarShade = isShade;
        if (!mIsStatusBarShade) {
            collapseStack();
        }

        if (mNotifEntryToExpandOnShadeUnlock != null) {
            expandStackAndSelectBubble(mNotifEntryToExpandOnShadeUnlock);
            mNotifEntryToExpandOnShadeUnlock = null;
        }

        updateStack();
    }

    @VisibleForTesting
    public void onBubbleNotificationSuppressionChanged(Bubble bubble) {
        // Make sure NoMan knows suppression state so that anyone querying it can tell.
        try {
            mBarService.onBubbleNotificationSuppressionChanged(bubble.getKey(),
                    !bubble.showInShade(), bubble.isSuppressed());
        } catch (RemoteException e) {
            // Bad things have happened
        }
        mImpl.mCachedState.updateBubbleSuppressedState(bubble);
    }

    /** Called when the current user changes. */
    @VisibleForTesting
    public void onUserChanged(int newUserId) {
        saveBubbles(mCurrentUserId);
        mCurrentUserId = newUserId;

        mBubbleData.dismissAll(DISMISS_USER_CHANGED);
        mBubbleData.clearOverflow();
        mOverflowDataLoadNeeded = true;

        restoreBubbles(newUserId);
        mBubbleData.setCurrentUserId(newUserId);
    }

    /** Called when the profiles for the current user change. **/
    public void onCurrentProfilesChanged(SparseArray<UserInfo> currentProfiles) {
        mCurrentProfiles = currentProfiles;
    }

    /** Whether this userId belongs to the current user. */
    private boolean isCurrentProfile(int userId) {
        return userId == UserHandle.USER_ALL
                || (mCurrentProfiles != null && mCurrentProfiles.get(userId) != null);
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    public void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    /** Set a listener to be notified of when overflow view update. */
    public void setOverflowListener(BubbleData.Listener listener) {
        mOverflowListener = listener;
    }

    /**
     * @return Bubbles for updating overflow.
     */
    List<Bubble> getOverflowBubbles() {
        return mBubbleData.getOverflowBubbles();
    }

    /** The task listener for events in bubble tasks. */
    public ShellTaskOrganizer getTaskOrganizer() {
        return mTaskOrganizer;
    }

    SyncTransactionQueue getSyncTransactionQueue() {
        return mSyncQueue;
    }

    /** Contains information to help position things on the screen.  */
    BubblePositioner getPositioner() {
        return mBubblePositioner;
    }

    Bubbles.SysuiProxy getSysuiProxy() {
        return mSysuiProxy;
    }

    /**
     * BubbleStackView is lazily created by this method the first time a Bubble is added. This
     * method initializes the stack view and adds it to window manager.
     */
    private void ensureStackViewCreated() {
        if (mStackView == null) {
            mStackView = new BubbleStackView(
                    mContext, this, mBubbleData, mSurfaceSynchronizer, mFloatingContentCoordinator,
                    mMainExecutor);
            mStackView.onOrientationChanged();
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
            }
            mStackView.setUnbubbleConversationCallback(mSysuiProxy::onUnbubbleConversation);
        }

        addToWindowManagerMaybe();
    }

    /** Adds the BubbleStackView to the WindowManager if it's not already there. */
    private void addToWindowManagerMaybe() {
        // If the stack is null, or already added, don't add it.
        if (mStackView == null || mAddedToWindowManager) {
            return;
        }

        mWmLayoutParams = new WindowManager.LayoutParams(
                // Fill the screen so we can use translation animations to position the bubble
                // stack. We'll use touchable regions to ignore touches that are not on the bubbles
                // themselves.
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        mWmLayoutParams.setTrustedOverlay();
        mWmLayoutParams.setFitInsetsTypes(0);
        mWmLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mWmLayoutParams.token = new Binder();
        mWmLayoutParams.setTitle("Bubbles!");
        mWmLayoutParams.packageName = mContext.getPackageName();
        mWmLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWmLayoutParams.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;

        try {
            mAddedToWindowManager = true;
            mBubbleData.getOverflow().initialize(this);
            mWindowManager.addView(mStackView, mWmLayoutParams);
            mStackView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                if (!windowInsets.equals(mWindowInsets)) {
                    mWindowInsets = windowInsets;
                    mBubblePositioner.update();
                    mStackView.onDisplaySizeChanged();
                }
                return windowInsets;
            });
        } catch (IllegalStateException e) {
            // This means the stack has already been added. This shouldn't happen...
            e.printStackTrace();
        }
    }

    /**
     * In some situations bubble's should be able to receive key events for back:
     * - when the bubble overflow is showing
     * - when the user education for the stack is showing.
     *
     * @param interceptBack whether back should be intercepted or not.
     */
    void updateWindowFlagsForBackpress(boolean interceptBack) {
        if (mStackView != null && mAddedToWindowManager) {
            mWmLayoutParams.flags = interceptBack
                    ? 0
                    : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mWmLayoutParams.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            mWindowManager.updateViewLayout(mStackView, mWmLayoutParams);
        }
    }

    /** Removes the BubbleStackView from the WindowManager if it's there. */
    private void removeFromWindowManagerMaybe() {
        if (!mAddedToWindowManager) {
            return;
        }

        try {
            mAddedToWindowManager = false;
            if (mStackView != null) {
                mWindowManager.removeView(mStackView);
                mBubbleData.getOverflow().cleanUpExpandedState();
            } else {
                Log.w(TAG, "StackView added to WindowManager, but was null when removing!");
            }
        } catch (IllegalArgumentException e) {
            // This means the stack has already been removed - it shouldn't happen, but ignore if it
            // does, since we wanted it removed anyway.
            e.printStackTrace();
        }
    }

    /**
     * Called by the BubbleStackView and whenever all bubbles have animated out, and none have been
     * added in the meantime.
     */
    void onAllBubblesAnimatedOut() {
        if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
            removeFromWindowManagerMaybe();
        }
    }

    /**
     * Records the notification key for any active bubbles. These are used to restore active
     * bubbles when the user returns to the foreground.
     *
     * @param userId the id of the user
     */
    private void saveBubbles(@UserIdInt int userId) {
        // First clear any existing keys that might be stored.
        mSavedBubbleKeysPerUser.remove(userId);
        // Add in all active bubbles for the current user.
        for (Bubble bubble: mBubbleData.getBubbles()) {
            mSavedBubbleKeysPerUser.add(userId, bubble.getKey());
        }
    }

    /**
     * Promotes existing notifications to Bubbles if they were previously bubbles.
     *
     * @param userId the id of the user
     */
    private void restoreBubbles(@UserIdInt int userId) {
        ArraySet<String> savedBubbleKeys = mSavedBubbleKeysPerUser.get(userId);
        if (savedBubbleKeys == null) {
            // There were no bubbles saved for this used.
            return;
        }
        mSysuiProxy.getShouldRestoredEntries(savedBubbleKeys, (entries) -> {
            mMainExecutor.execute(() -> {
                for (BubbleEntry e : entries) {
                    if (canLaunchInTaskView(mContext, e)) {
                        updateBubble(e, true /* suppressFlyout */, false /* showInShade */);
                    }
                }
            });
        });
        // Finally, remove the entries for this user now that bubbles are restored.
        mSavedBubbleKeysPerUser.remove(userId);
    }

    private void updateForThemeChanges() {
        if (mStackView != null) {
            mStackView.onThemeChanged();
        }
        mBubbleIconFactory = new BubbleIconFactory(mContext);
        // Reload each bubble
        for (Bubble b: mBubbleData.getBubbles()) {
            b.inflate(null /* callback */, mContext, this, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
        for (Bubble b: mBubbleData.getOverflowBubbles()) {
            b.inflate(null /* callback */, mContext, this, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
    }

    private void onConfigChanged(Configuration newConfig) {
        if (mBubblePositioner != null) {
            mBubblePositioner.update();
        }
        if (mStackView != null && newConfig != null) {
            if (newConfig.densityDpi != mDensityDpi
                    || !newConfig.windowConfiguration.getBounds().equals(mScreenBounds)) {
                mDensityDpi = newConfig.densityDpi;
                mScreenBounds.set(newConfig.windowConfiguration.getBounds());
                mBubbleData.onMaxBubblesChanged();
                mBubbleIconFactory = new BubbleIconFactory(mContext);
                mStackView.onDisplaySizeChanged();
            }
            if (newConfig.fontScale != mFontScale) {
                mFontScale = newConfig.fontScale;
                mStackView.updateFontScale();
            }
            if (newConfig.getLayoutDirection() != mLayoutDirection) {
                mLayoutDirection = newConfig.getLayoutDirection();
                mStackView.onLayoutDirectionChanged(mLayoutDirection);
            }
        }
    }

    private void setSysuiProxy(Bubbles.SysuiProxy proxy) {
        mSysuiProxy = proxy;
    }

    @VisibleForTesting
    public void setExpandListener(Bubbles.BubbleExpandListener listener) {
        mExpandListener = ((isExpanding, key) -> {
            if (listener != null) {
                listener.onBubbleExpandChanged(isExpanding, key);
            }
        });
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    @VisibleForTesting
    public boolean hasBubbles() {
        if (mStackView == null) {
            return false;
        }
        return mBubbleData.hasBubbles() || mBubbleData.isShowingOverflow();
    }

    @VisibleForTesting
    public boolean isStackExpanded() {
        return mBubbleData.isExpanded();
    }

    @VisibleForTesting
    public void collapseStack() {
        mBubbleData.setExpanded(false /* expanded */);
    }

    @VisibleForTesting
    public boolean isBubbleNotificationSuppressedFromShade(String key, String groupKey) {
        boolean isSuppressedBubble = (mBubbleData.hasAnyBubbleWithKey(key)
                && !mBubbleData.getAnyBubbleWithkey(key).showInShade());

        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey);
        boolean isSummary = key.equals(mBubbleData.getSummaryKey(groupKey));
        return (isSummary && isSuppressedSummary) || isSuppressedBubble;
    }

    private void removeSuppressedSummaryIfNecessary(String groupKey, Consumer<String> callback) {
        if (mBubbleData.isSummarySuppressed(groupKey)) {
            mBubbleData.removeSuppressedSummary(groupKey);
            if (callback != null) {
                callback.accept(mBubbleData.getSummaryKey(groupKey));
            }
        }
    }

    /** Promote the provided bubble from the overflow view. */
    public void promoteBubbleFromOverflow(Bubble bubble) {
        mLogger.log(bubble, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_BACK_TO_STACK);
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.setShouldAutoExpand(true);
        bubble.markAsAccessedAt(System.currentTimeMillis());
        setIsBubble(bubble, true /* isBubble */);
    }

    /**
     * Expands and selects the provided bubble as long as it already exists in the stack or the
     * overflow.
     *
     * This is currently only used when opening a bubble via clicking on a conversation widget.
     */
    public void expandStackAndSelectBubble(Bubble b) {
        if (b == null) {
            return;
        }
        if (mBubbleData.hasBubbleInStackWithKey(b.getKey())) {
            // already in the stack
            mBubbleData.setSelectedBubble(b);
            mBubbleData.setExpanded(true);
        } else if (mBubbleData.hasOverflowBubbleWithKey(b.getKey())) {
            // promote it out of the overflow
            promoteBubbleFromOverflow(b);
        }
    }

    /**
     * Expands and selects a bubble based on the provided {@link BubbleEntry}. If no bubble
     * exists for this entry, and it is able to bubble, a new bubble will be created.
     *
     * This is the method to use when opening a bubble via a notification or in a state where
     * the device might not be unlocked.
     *
     * @param entry the entry to use for the bubble.
     */
    public void expandStackAndSelectBubble(BubbleEntry entry) {
        if (mIsStatusBarShade) {
            mNotifEntryToExpandOnShadeUnlock = null;

            String key = entry.getKey();
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(key);
            if (bubble != null) {
                mBubbleData.setSelectedBubble(bubble);
                mBubbleData.setExpanded(true);
            } else {
                bubble = mBubbleData.getOverflowBubbleWithKey(key);
                if (bubble != null) {
                    promoteBubbleFromOverflow(bubble);
                } else if (entry.canBubble()) {
                    // It can bubble but it's not -- it got aged out of the overflow before it
                    // was dismissed or opened, make it a bubble again.
                    setIsBubble(entry, true /* isBubble */, true /* autoExpand */);
                }
            }
        } else {
            // Wait until we're unlocked to expand, so that the user can see the expand animation
            // and also to work around bugs with expansion animation + shade unlock happening at the
            // same time.
            mNotifEntryToExpandOnShadeUnlock = entry;
        }
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     */
    @VisibleForTesting
    public void updateBubble(BubbleEntry notif) {
        updateBubble(notif, false /* suppressFlyout */, true /* showInShade */);
    }

    /**
     * Fills the overflow bubbles by loading them from disk.
     */
    void loadOverflowBubblesFromDisk() {
        if (!mOverflowDataLoadNeeded) {
            return;
        }
        mOverflowDataLoadNeeded = false;
        mDataRepository.loadBubbles(mCurrentUserId, (bubbles) -> {
            bubbles.forEach(bubble -> {
                if (mBubbleData.hasAnyBubbleWithKey(bubble.getKey())) {
                    // if the bubble is already active, there's no need to push it to overflow
                    return;
                }
                bubble.inflate(
                        (b) -> mBubbleData.overflowBubble(Bubbles.DISMISS_RELOAD_FROM_DISK, bubble),
                        mContext, this, mStackView, mBubbleIconFactory, true /* skipInflation */);
            });
            return null;
        });
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     * @param suppressFlyout this bubble suppress flyout or not.
     * @param showInShade this bubble show in shade or not.
     */
    @VisibleForTesting
    public void updateBubble(BubbleEntry notif, boolean suppressFlyout, boolean showInShade) {
        // If this is an interruptive notif, mark that it's interrupted
        mSysuiProxy.setNotificationInterruption(notif.getKey());
        if (!notif.getRanking().visuallyInterruptive()
                && (notif.getBubbleMetadata() != null
                    && !notif.getBubbleMetadata().getAutoExpandBubble())
                && mBubbleData.hasOverflowBubbleWithKey(notif.getKey())) {
            // Update the bubble but don't promote it out of overflow
            Bubble b = mBubbleData.getOverflowBubbleWithKey(notif.getKey());
            b.setEntry(notif);
        } else {
            Bubble bubble = mBubbleData.getOrCreateBubble(notif, null /* persistedBubble */);
            inflateAndAdd(bubble, suppressFlyout, showInShade);
        }
    }

    void inflateAndAdd(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        // Lazy init stack view when a bubble is created
        ensureStackViewCreated();
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.inflate(b -> mBubbleData.notificationEntryUpdated(b, suppressFlyout, showInShade),
                mContext, this, mStackView, mBubbleIconFactory, false /* skipInflation */);
    }

    /**
     * Removes the bubble with the given key.
     * <p>
     * Must be called from the main thread.
     */
    @VisibleForTesting
    @MainThread
    public void removeBubble(String key, int reason) {
        if (mBubbleData.hasAnyBubbleWithKey(key)) {
            mBubbleData.dismissBubbleWithKey(key, reason);
        }
    }

    private void onEntryAdded(BubbleEntry entry) {
        if (canLaunchInTaskView(mContext, entry)) {
            updateBubble(entry);
        }
    }

    private void onEntryUpdated(BubbleEntry entry, boolean shouldBubbleUp) {
        // shouldBubbleUp checks canBubble & for bubble metadata
        boolean shouldBubble = shouldBubbleUp && canLaunchInTaskView(mContext, entry);
        if (!shouldBubble && mBubbleData.hasAnyBubbleWithKey(entry.getKey())) {
            // It was previously a bubble but no longer a bubble -- lets remove it
            removeBubble(entry.getKey(), DISMISS_NO_LONGER_BUBBLE);
        } else if (shouldBubble && entry.isBubble()) {
            updateBubble(entry);
        }
    }

    private void onEntryRemoved(BubbleEntry entry) {
        if (isSummaryOfBubbles(entry)) {
            final String groupKey = entry.getStatusBarNotification().getGroupKey();
            mBubbleData.removeSuppressedSummary(groupKey);

            // Remove any associated bubble children with the summary
            final List<Bubble> bubbleChildren = getBubblesInGroup(groupKey);
            for (int i = 0; i < bubbleChildren.size(); i++) {
                removeBubble(bubbleChildren.get(i).getKey(), DISMISS_GROUP_CANCELLED);
            }
        } else {
            removeBubble(entry.getKey(), DISMISS_NOTIF_CANCEL);
        }
    }

    private void onRankingUpdated(RankingMap rankingMap,
            HashMap<String, Pair<BubbleEntry, Boolean>> entryDataByKey) {
        if (mTmpRanking == null) {
            mTmpRanking = new NotificationListenerService.Ranking();
        }
        String[] orderedKeys = rankingMap.getOrderedKeys();
        for (int i = 0; i < orderedKeys.length; i++) {
            String key = orderedKeys[i];
            Pair<BubbleEntry, Boolean> entryData = entryDataByKey.get(key);
            BubbleEntry entry = entryData.first;
            boolean shouldBubbleUp = entryData.second;

            if (entry != null && !isCurrentProfile(
                    entry.getStatusBarNotification().getUser().getIdentifier())) {
                return;
            }

            rankingMap.getRanking(key, mTmpRanking);
            boolean isActiveBubble = mBubbleData.hasAnyBubbleWithKey(key);
            if (isActiveBubble && !mTmpRanking.canBubble()) {
                // If this entry is no longer allowed to bubble, dismiss with the BLOCKED reason.
                // This means that the app or channel's ability to bubble has been revoked.
                mBubbleData.dismissBubbleWithKey(key, DISMISS_BLOCKED);
            } else if (isActiveBubble && !shouldBubbleUp) {
                // If this entry is allowed to bubble, but cannot currently bubble up, dismiss it.
                // This happens when DND is enabled and configured to hide bubbles. Dismissing with
                // the reason DISMISS_NO_BUBBLE_UP will retain the underlying notification, so that
                // the bubble will be re-created if shouldBubbleUp returns true.
                mBubbleData.dismissBubbleWithKey(key, DISMISS_NO_BUBBLE_UP);
            } else if (entry != null && mTmpRanking.isBubble() && !isActiveBubble) {
                entry.setFlagBubble(true);
                onEntryUpdated(entry, true /* shouldBubbleUp */);
            }
        }
    }

    /**
     * Retrieves any bubbles that are part of the notification group represented by the provided
     * group key.
     */
    private ArrayList<Bubble> getBubblesInGroup(@Nullable String groupKey) {
        ArrayList<Bubble> bubbleChildren = new ArrayList<>();
        if (groupKey == null) {
            return bubbleChildren;
        }
        for (Bubble bubble : mBubbleData.getActiveBubbles()) {
            if (bubble.getGroupKey() != null && groupKey.equals(bubble.getGroupKey())) {
                bubbleChildren.add(bubble);
            }
        }
        return bubbleChildren;
    }

    private void setIsBubble(@NonNull final BubbleEntry entry, final boolean isBubble,
            final boolean autoExpand) {
        Objects.requireNonNull(entry);
        entry.setFlagBubble(isBubble);
        try {
            int flags = 0;
            if (autoExpand) {
                flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
                flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            }
            mBarService.onNotificationBubbleChanged(entry.getKey(), isBubble, flags);
        } catch (RemoteException e) {
            // Bad things have happened
        }
    }

    private void setIsBubble(@NonNull final Bubble b, final boolean isBubble) {
        Objects.requireNonNull(b);
        b.setIsBubble(isBubble);
        mSysuiProxy.getPendingOrActiveEntry(b.getKey(), (entry) -> {
            mMainExecutor.execute(() -> {
                if (entry != null) {
                    // Updating the entry to be a bubble will trigger our normal update flow
                    setIsBubble(entry, isBubble, b.shouldAutoExpand());
                } else if (isBubble) {
                    // If bubble doesn't exist, it's a persisted bubble so we need to add it to the
                    // stack ourselves
                    Bubble bubble = mBubbleData.getOrCreateBubble(null, b /* persistedBubble */);
                    inflateAndAdd(bubble, bubble.shouldAutoExpand() /* suppressFlyout */,
                            !bubble.shouldAutoExpand() /* showInShade */);
                }
            });
        });
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {
            ensureStackViewCreated();

            // Lazy load overflow bubbles from disk
            loadOverflowBubblesFromDisk();

            mStackView.updateOverflowButtonDot();

            // Update bubbles in overflow.
            if (mOverflowListener != null) {
                mOverflowListener.applyUpdate(update);
            }

            // Collapsing? Do this first before remaining steps.
            if (update.expandedChanged && !update.expanded) {
                mStackView.setExpanded(false);
                mSysuiProxy.requestNotificationShadeTopUi(false, TAG);
            }

            // Do removals, if any.
            ArrayList<Pair<Bubble, Integer>> removedBubbles =
                    new ArrayList<>(update.removedBubbles);
            ArrayList<Bubble> bubblesToBeRemovedFromRepository = new ArrayList<>();
            for (Pair<Bubble, Integer> removed : removedBubbles) {
                final Bubble bubble = removed.first;
                @Bubbles.DismissReason final int reason = removed.second;

                if (mStackView != null) {
                    mStackView.removeBubble(bubble);
                }

                // Leave the notification in place if we're dismissing due to user switching, or
                // because DND is suppressing the bubble. In both of those cases, we need to be able
                // to restore the bubble from the notification later.
                if (reason == DISMISS_USER_CHANGED || reason == DISMISS_NO_BUBBLE_UP) {
                    continue;
                }
                if (reason == DISMISS_NOTIF_CANCEL) {
                    bubblesToBeRemovedFromRepository.add(bubble);
                }
                if (!mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                    if (!mBubbleData.hasOverflowBubbleWithKey(bubble.getKey())
                            && (!bubble.showInShade()
                            || reason == DISMISS_NOTIF_CANCEL
                            || reason == DISMISS_GROUP_CANCELLED)) {
                        // The bubble is now gone & the notification is hidden from the shade, so
                        // time to actually remove it
                        mSysuiProxy.notifyRemoveNotification(bubble.getKey(), REASON_CANCEL);
                    } else {
                        if (bubble.isBubble()) {
                            setIsBubble(bubble, false /* isBubble */);
                        }
                        mSysuiProxy.updateNotificationBubbleButton(bubble.getKey());
                    }

                }
                mSysuiProxy.getPendingOrActiveEntry(bubble.getKey(), (entry) -> {
                    mMainExecutor.execute(() -> {
                        if (entry != null) {
                            final String groupKey = entry.getStatusBarNotification().getGroupKey();
                            if (getBubblesInGroup(groupKey).isEmpty()) {
                                // Time to potentially remove the summary
                                mSysuiProxy.notifyMaybeCancelSummary(bubble.getKey());
                            }
                        }
                    });
                });
            }
            mDataRepository.removeBubbles(mCurrentUserId, bubblesToBeRemovedFromRepository);

            if (update.addedBubble != null && mStackView != null) {
                mDataRepository.addBubble(mCurrentUserId, update.addedBubble);
                mStackView.addBubble(update.addedBubble);
            }

            if (update.updatedBubble != null && mStackView != null) {
                mStackView.updateBubble(update.updatedBubble);
            }

            // At this point, the correct bubbles are inflated in the stack.
            // Make sure the order in bubble data is reflected in bubble row.
            if (update.orderChanged && mStackView != null) {
                mDataRepository.addBubbles(mCurrentUserId, update.bubbles);
                mStackView.updateBubbleOrder(update.bubbles);
            }

            if (update.selectionChanged && mStackView != null) {
                mStackView.setSelectedBubble(update.selectedBubble);
                if (update.selectedBubble != null) {
                    mSysuiProxy.updateNotificationSuppression(update.selectedBubble.getKey());
                }
            }

            if (update.suppressedBubble != null && mStackView != null) {
                mStackView.setBubbleVisibility(update.suppressedBubble, false);
            }

            if (update.unsuppressedBubble != null && mStackView != null) {
                mStackView.setBubbleVisibility(update.unsuppressedBubble, true);
            }

            // Expanding? Apply this last.
            if (update.expandedChanged && update.expanded) {
                if (mStackView != null) {
                    mStackView.setExpanded(true);
                    mSysuiProxy.requestNotificationShadeTopUi(true, TAG);
                }
            }

            mSysuiProxy.notifyInvalidateNotifications("BubbleData.Listener.applyUpdate");
            updateStack();

            // Update the cached state for queries from SysUI
            mImpl.mCachedState.update(update);
        }
    };

    private boolean handleDismissalInterception(BubbleEntry entry,
            @Nullable List<BubbleEntry> children, IntConsumer removeCallback) {
        if (isSummaryOfBubbles(entry)) {
            handleSummaryDismissalInterception(entry, children, removeCallback);
        } else {
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(entry.getKey());
            if (bubble == null || !entry.isBubble()) {
                bubble = mBubbleData.getOverflowBubbleWithKey(entry.getKey());
            }
            if (bubble == null) {
                return false;
            }
            bubble.setSuppressNotification(true);
            bubble.setShowDot(false /* show */);
        }
        // Update the shade
        mSysuiProxy.notifyInvalidateNotifications("BubbleController.handleDismissalInterception");
        return true;
    }

    private boolean isSummaryOfBubbles(BubbleEntry entry) {
        String groupKey = entry.getStatusBarNotification().getGroupKey();
        ArrayList<Bubble> bubbleChildren = getBubblesInGroup(groupKey);
        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey)
                && mBubbleData.getSummaryKey(groupKey).equals(entry.getKey());
        boolean isSummary = entry.getStatusBarNotification().getNotification().isGroupSummary();
        return (isSuppressedSummary || isSummary) && !bubbleChildren.isEmpty();
    }

    private void handleSummaryDismissalInterception(
            BubbleEntry summary, @Nullable List<BubbleEntry> children, IntConsumer removeCallback) {
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                BubbleEntry child = children.get(i);
                if (mBubbleData.hasAnyBubbleWithKey(child.getKey())) {
                    // Suppress the bubbled child
                    // As far as group manager is concerned, once a child is no longer shown
                    // in the shade, it is essentially removed.
                    Bubble bubbleChild = mBubbleData.getAnyBubbleWithkey(child.getKey());
                    if (bubbleChild != null) {
                        mSysuiProxy.removeNotificationEntry(bubbleChild.getKey());
                        bubbleChild.setSuppressNotification(true);
                        bubbleChild.setShowDot(false /* show */);
                    }
                } else {
                    // non-bubbled children can be removed
                    removeCallback.accept(i);
                }
            }
        }

        // And since all children are removed, remove the summary.
        removeCallback.accept(-1);

        // TODO: (b/145659174) remove references to mSuppressedGroupKeys once fully migrated
        mBubbleData.addSummaryToSuppress(summary.getStatusBarNotification().getGroupKey(),
                summary.getKey());
    }

    /**
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides.
     * Updates stack description for TalkBack focus.
     */
    public void updateStack() {
        if (mStackView == null) {
            return;
        }

        if (!mIsStatusBarShade) {
            // Bubbles don't appear over the locked shade.
            mStackView.setVisibility(INVISIBLE);
        } else if (hasBubbles()) {
            // If we're unlocked, show the stack if we have bubbles. If we don't have bubbles, the
            // stack will be set to INVISIBLE in onAllBubblesAnimatedOut after the bubbles animate
            // out.
            mStackView.setVisibility(VISIBLE);
        }

        mStackView.updateContentDescription();
    }

    @VisibleForTesting
    public BubbleStackView getStackView() {
        return mStackView;
    }

    /**
     * Description of current bubble state.
     */
    private void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BubbleController state:");
        mBubbleData.dump(fd, pw, args);
        pw.println();
        if (mStackView != null) {
            mStackView.dump(fd, pw, args);
        }
        pw.println();
    }

    /**
     * Whether an intent is properly configured to display in a
     * {@link com.android.wm.shell.TaskView}.
     *
     * Keep checks in sync with BubbleExtractor#canLaunchInTaskView. Typically
     * that should filter out any invalid bubbles, but should protect SysUI side just in case.
     *
     * @param context the context to use.
     * @param entry the entry to bubble.
     */
    static boolean canLaunchInTaskView(Context context, BubbleEntry entry) {
        PendingIntent intent = entry.getBubbleMetadata() != null
                ? entry.getBubbleMetadata().getIntent()
                : null;
        if (entry.getBubbleMetadata() != null
                && entry.getBubbleMetadata().getShortcutId() != null) {
            return true;
        }
        if (intent == null) {
            Log.w(TAG, "Unable to create bubble -- no intent: " + entry.getKey());
            return false;
        }
        PackageManager packageManager = getPackageManagerForUser(
                context, entry.getStatusBarNotification().getUser().getIdentifier());
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(packageManager, 0);
        if (info == null) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " couldn't find activity info for intent: "
                    + intent);
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " activity is not resizable for intent: "
                    + intent);
            return false;
        }
        return true;
    }

    static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                                Context.CONTEXT_RESTRICTED,
                                new UserHandle(userId));
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    /** PinnedStackListener that dispatches IME visibility updates to the stack. */
    //TODO(b/170442945): Better way to do this / insets listener?
    private class BubblesImeListener extends PinnedStackListenerForwarder.PinnedTaskListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            if (mStackView != null) {
                mStackView.onImeVisibilityChanged(imeVisible, imeHeight);
            }
        }
    }

    private class BubblesImpl implements Bubbles {
        // Up-to-date cached state of bubbles data for SysUI to query from the calling thread
        @VisibleForTesting
        public class CachedState {
            private boolean mIsStackExpanded;
            private String mSelectedBubbleKey;
            private HashSet<String> mSuppressedBubbleKeys = new HashSet<>();
            private HashMap<String, String> mSuppressedGroupToNotifKeys = new HashMap<>();
            private HashMap<String, Bubble> mShortcutIdToBubble = new HashMap<>();

            private ArrayList<Bubble> mTmpBubbles = new ArrayList<>();

            /**
             * Updates the cached state based on the last full BubbleData change.
             */
            synchronized void update(BubbleData.Update update) {
                if (update.selectionChanged) {
                    mSelectedBubbleKey = update.selectedBubble != null
                            ? update.selectedBubble.getKey()
                            : null;
                }
                if (update.expandedChanged) {
                    mIsStackExpanded = update.expanded;
                }
                if (update.suppressedSummaryChanged) {
                    String summaryKey =
                            mBubbleData.getSummaryKey(update.suppressedSummaryGroup);
                    if (summaryKey != null) {
                        mSuppressedGroupToNotifKeys.put(update.suppressedSummaryGroup, summaryKey);
                    } else {
                        mSuppressedGroupToNotifKeys.remove(update.suppressedSummaryGroup);
                    }
                }

                mTmpBubbles.clear();
                mTmpBubbles.addAll(update.bubbles);
                mTmpBubbles.addAll(update.overflowBubbles);

                mSuppressedBubbleKeys.clear();
                mShortcutIdToBubble.clear();
                for (Bubble b : mTmpBubbles) {
                    mShortcutIdToBubble.put(b.getShortcutId(), b);
                    updateBubbleSuppressedState(b);
                }
            }

            /**
             * Updates a specific bubble suppressed state.  This is used mainly because notification
             * suppression changes don't go through the same BubbleData update mechanism.
             */
            synchronized void updateBubbleSuppressedState(Bubble b) {
                if (!b.showInShade()) {
                    mSuppressedBubbleKeys.add(b.getKey());
                } else {
                    mSuppressedBubbleKeys.remove(b.getKey());
                }
            }

            public synchronized boolean isStackExpanded() {
                return mIsStackExpanded;
            }

            public synchronized boolean isBubbleExpanded(String key) {
                return mIsStackExpanded && key.equals(mSelectedBubbleKey);
            }

            public synchronized boolean isBubbleNotificationSuppressedFromShade(String key,
                    String groupKey) {
                return mSuppressedBubbleKeys.contains(key)
                        || (mSuppressedGroupToNotifKeys.containsKey(groupKey)
                                && key.equals(mSuppressedGroupToNotifKeys.get(groupKey)));
            }

            @Nullable
            public synchronized Bubble getBubbleWithShortcutId(String id) {
                return mShortcutIdToBubble.get(id);
            }

            synchronized void dump(PrintWriter pw) {
                pw.println("BubbleImpl.CachedState state:");

                pw.println("mIsStackExpanded: " + mIsStackExpanded);
                pw.println("mSelectedBubbleKey: " + mSelectedBubbleKey);

                pw.print("mSuppressedBubbleKeys: ");
                pw.println(mSuppressedBubbleKeys.size());
                for (String key : mSuppressedBubbleKeys) {
                    pw.println("   suppressing: " + key);
                }

                pw.print("mSuppressedGroupToNotifKeys: ");
                pw.println(mSuppressedGroupToNotifKeys.size());
                for (String key : mSuppressedGroupToNotifKeys.keySet()) {
                    pw.println("   suppressing: " + key);
                }
            }
        }

        private CachedState mCachedState = new CachedState();

        @Override
        public boolean isBubbleNotificationSuppressedFromShade(String key, String groupKey) {
            return mCachedState.isBubbleNotificationSuppressedFromShade(key, groupKey);
        }

        @Override
        public boolean isBubbleExpanded(String key) {
            return mCachedState.isBubbleExpanded(key);
        }

        @Override
        public boolean isStackExpanded() {
            return mCachedState.isStackExpanded();
        }

        @Override
        @Nullable
        public Bubble getBubbleWithShortcutId(String shortcutId) {
            return mCachedState.getBubbleWithShortcutId(shortcutId);
        }

        @Override
        public void removeSuppressedSummaryIfNecessary(String groupKey, Consumer<String> callback,
                Executor callbackExecutor) {
            mMainExecutor.execute(() -> {
                Consumer<String> cb = callback != null
                        ? (key) -> callbackExecutor.execute(() -> callback.accept(key))
                        : null;
                BubbleController.this.removeSuppressedSummaryIfNecessary(groupKey, cb);
            });
        }

        @Override
        public void collapseStack() {
            mMainExecutor.execute(() -> {
                BubbleController.this.collapseStack();
            });
        }

        @Override
        public void updateForThemeChanges() {
            mMainExecutor.execute(() -> {
                BubbleController.this.updateForThemeChanges();
            });
        }

        @Override
        public void expandStackAndSelectBubble(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.expandStackAndSelectBubble(entry);
            });
        }

        @Override
        public void expandStackAndSelectBubble(Bubble bubble) {
            mMainExecutor.execute(() -> {
                BubbleController.this.expandStackAndSelectBubble(bubble);
            });
        }

        @Override
        public void onTaskbarChanged(Bundle b) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onTaskbarChanged(b);
            });
        }

        @Override
        public void openBubbleOverflow() {
            mMainExecutor.execute(() -> {
                BubbleController.this.openBubbleOverflow();
            });
        }

        @Override
        public boolean handleDismissalInterception(BubbleEntry entry,
                @Nullable List<BubbleEntry> children, IntConsumer removeCallback,
                Executor callbackExecutor) {
            IntConsumer cb = removeCallback != null
                    ? (index) -> callbackExecutor.execute(() -> removeCallback.accept(index))
                    : null;
            return mMainExecutor.executeBlockingForResult(() -> {
                return BubbleController.this.handleDismissalInterception(entry, children, cb);
            }, Boolean.class);
        }

        @Override
        public void setSysuiProxy(SysuiProxy proxy) {
            mMainExecutor.execute(() -> {
                BubbleController.this.setSysuiProxy(proxy);
            });
        }

        @Override
        public void setExpandListener(BubbleExpandListener listener) {
            mMainExecutor.execute(() -> {
                BubbleController.this.setExpandListener(listener);
            });
        }

        @Override
        public void onEntryAdded(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryAdded(entry);
            });
        }

        @Override
        public void onEntryUpdated(BubbleEntry entry, boolean shouldBubbleUp) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryUpdated(entry, shouldBubbleUp);
            });
        }

        @Override
        public void onEntryRemoved(BubbleEntry entry) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onEntryRemoved(entry);
            });
        }

        @Override
        public void onRankingUpdated(RankingMap rankingMap,
                HashMap<String, Pair<BubbleEntry, Boolean>> entryDataByKey) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onRankingUpdated(rankingMap, entryDataByKey);
            });
        }

        @Override
        public void onStatusBarVisibilityChanged(boolean visible) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onStatusBarVisibilityChanged(visible);
            });
        }

        @Override
        public void onZenStateChanged() {
            mMainExecutor.execute(() -> {
                BubbleController.this.onZenStateChanged();
            });
        }

        @Override
        public void onStatusBarStateChanged(boolean isShade) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onStatusBarStateChanged(isShade);
            });
        }

        @Override
        public void onUserChanged(int newUserId) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onUserChanged(newUserId);
            });
        }

        @Override
        public void onCurrentProfilesChanged(SparseArray<UserInfo> currentProfiles) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onCurrentProfilesChanged(currentProfiles);
            });
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            mMainExecutor.execute(() -> {
                BubbleController.this.onConfigChanged(newConfig);
            });
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            try {
                mMainExecutor.executeBlocking(() -> {
                    BubbleController.this.dump(fd, pw, args);
                    mCachedState.dump(pw);
                });
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to dump BubbleController in 2s");
            }
        }
    }
}
