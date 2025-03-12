/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.window.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION;
import static android.window.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS;


import static com.android.launcher3.icons.BaseIconFactory.MODE_DEFAULT;
import static com.android.wm.shell.shared.desktopmode.DesktopModeStatus.canEnterDesktopMode;
import static com.android.wm.shell.shared.desktopmode.DesktopModeStatus.canEnterDesktopModeOrShowAppHandle;
import static com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.DisabledEdge;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.DisabledEdge.NONE;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getFineResizeCornerSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getLargeResizeCornerSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getResizeEdgeHandleSize;
import static com.android.wm.shell.windowdecor.DragResizeWindowGeometry.getResizeHandleEdgeInset;
import static com.android.wm.shell.windowdecor.DragPositioningCallbackUtility.DragEventListener;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.WindowConfiguration.WindowingMode;
import android.app.assist.AssistContent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Size;
import android.util.Slog;
import android.view.Choreographer;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.window.DesktopModeFlags;
import android.window.TaskSnapshot;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.IconProvider;
import com.android.window.flags.Flags;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser;
import com.android.wm.shell.apptoweb.AppToWebUtils;
import com.android.wm.shell.apptoweb.AssistContentRequester;
import com.android.wm.shell.apptoweb.OpenByDefaultDialog;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.MultiInstanceHelper;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.CaptionState;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.desktopmode.DesktopModeUtils;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource;
import com.android.wm.shell.shared.multiinstance.ManageWindowsViewContainer;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;
import com.android.wm.shell.windowdecor.viewholder.AppHandleViewHolder;
import com.android.wm.shell.windowdecor.viewholder.AppHeaderViewHolder;
import com.android.wm.shell.windowdecor.viewholder.WindowDecorationViewHolder;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link DesktopModeWindowDecorViewModel}.
 *
 * The shadow's thickness is 20dp when the window is in focus and 5dp when the window isn't.
 */
public class DesktopModeWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private static final String TAG = "DesktopModeWindowDecoration";

    @VisibleForTesting
    static final long CLOSE_MAXIMIZE_MENU_DELAY_MS = 150L;

    private final Handler mHandler;
    private final @ShellBackgroundThread ShellExecutor mBgExecutor;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;
    private final SplitScreenController mSplitScreenController;
    private final WindowManagerWrapper mWindowManagerWrapper;

    private WindowDecorationViewHolder mWindowDecorViewHolder;
    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private View.OnLongClickListener mOnCaptionLongClickListener;
    private View.OnGenericMotionListener mOnCaptionGenericMotionListener;
    private Function0<Unit> mOnMaximizeOrRestoreClickListener;
    private Function0<Unit> mOnImmersiveOrRestoreClickListener;
    private Function0<Unit> mOnLeftSnapClickListener;
    private Function0<Unit> mOnRightSnapClickListener;
    private Consumer<DesktopModeTransitionSource> mOnToDesktopClickListener;
    private Function0<Unit> mOnToFullscreenClickListener;
    private Function0<Unit> mOnToSplitscreenClickListener;
    private Function0<Unit> mOnNewWindowClickListener;
    private Function0<Unit> mOnManageWindowsClickListener;
    private Function0<Unit> mOnChangeAspectRatioClickListener;
    private Function0<Unit> mOnMaximizeHoverListener;
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;
    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private DisabledEdge mDisabledResizingEdge =
            NONE;
    private final WindowDecoration.RelayoutResult<WindowDecorLinearLayout> mResult =
            new WindowDecoration.RelayoutResult<>();

    private final Point mPositionInParent = new Point();
    private HandleMenu mHandleMenu;
    private boolean mMinimumInstancesFound;
    private ManageWindowsViewContainer mManageWindowsMenu;

    private MaximizeMenu mMaximizeMenu;

    private OpenByDefaultDialog mOpenByDefaultDialog;

    private ResizeVeil mResizeVeil;
    private Bitmap mAppIconBitmap;
    private Bitmap mResizeVeilBitmap;

    private CharSequence mAppName;
    private CapturedLink mCapturedLink;
    private Uri mGenericLink;
    private Uri mWebUri;
    private Consumer<Intent> mOpenInBrowserClickListener;

    private ExclusionRegionListener mExclusionRegionListener;

    private final AppHeaderViewHolder.Factory mAppHeaderViewHolderFactory;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final MaximizeMenuFactory mMaximizeMenuFactory;
    private final HandleMenuFactory mHandleMenuFactory;
    private final AppToWebGenericLinksParser mGenericLinksParser;
    private final AssistContentRequester mAssistContentRequester;

    // Hover state for the maximize menu and button. The menu will remain open as long as either of
    // these is true. See {@link #onMaximizeHoverStateChanged()}.
    private boolean mIsAppHeaderMaximizeButtonHovered = false;
    private boolean mIsMaximizeMenuHovered = false;
    // Used to schedule the closing of the maximize menu when neither of the button or menu are
    // being hovered. There's a small delay after stopping the hover, to allow a quick reentry
    // to cancel the close.
    private final Runnable mCloseMaximizeWindowRunnable = this::closeMaximizeMenu;
    private final MultiInstanceHelper mMultiInstanceHelper;
    private final WindowDecorCaptionHandleRepository mWindowDecorCaptionHandleRepository;
    private final DesktopUserRepositories mDesktopUserRepositories;

    public DesktopModeWindowDecoration(
            Context context,
            @NonNull Context userContext,
            DisplayController displayController,
            SplitScreenController splitScreenController,
            DesktopUserRepositories desktopUserRepositories,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            AppHeaderViewHolder.Factory appHeaderViewHolderFactory,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            AppToWebGenericLinksParser genericLinksParser,
            AssistContentRequester assistContentRequester,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            MultiInstanceHelper multiInstanceHelper,
            WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
            DesktopModeEventLogger desktopModeEventLogger) {
        this (context, userContext, displayController, splitScreenController,
                desktopUserRepositories, taskOrganizer, taskInfo, taskSurface, handler,
                bgExecutor, choreographer, syncQueue, appHeaderViewHolderFactory,
                rootTaskDisplayAreaOrganizer, genericLinksParser, assistContentRequester,
                SurfaceControl.Builder::new, SurfaceControl.Transaction::new,
                WindowContainerTransaction::new, SurfaceControl::new, new WindowManagerWrapper(
                        context.getSystemService(WindowManager.class)),
                new SurfaceControlViewHostFactory() {},
                windowDecorViewHostSupplier,
                DefaultMaximizeMenuFactory.INSTANCE,
                DefaultHandleMenuFactory.INSTANCE, multiInstanceHelper,
                windowDecorCaptionHandleRepository, desktopModeEventLogger);
    }

    DesktopModeWindowDecoration(
            Context context,
            @NonNull Context userContext,
            DisplayController displayController,
            SplitScreenController splitScreenController,
            DesktopUserRepositories desktopUserRepositories,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            AppHeaderViewHolder.Factory appHeaderViewHolderFactory,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            AppToWebGenericLinksParser genericLinksParser,
            AssistContentRequester assistContentRequester,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
            Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
            Supplier<SurfaceControl> surfaceControlSupplier,
            WindowManagerWrapper windowManagerWrapper,
            SurfaceControlViewHostFactory surfaceControlViewHostFactory,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            MaximizeMenuFactory maximizeMenuFactory,
            HandleMenuFactory handleMenuFactory,
            MultiInstanceHelper multiInstanceHelper,
            WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
            DesktopModeEventLogger desktopModeEventLogger) {
        super(context, userContext, displayController, taskOrganizer, taskInfo,
                taskSurface, surfaceControlBuilderSupplier, surfaceControlTransactionSupplier,
                windowContainerTransactionSupplier, surfaceControlSupplier,
                surfaceControlViewHostFactory, windowDecorViewHostSupplier, desktopModeEventLogger);
        mSplitScreenController = splitScreenController;
        mHandler = handler;
        mBgExecutor = bgExecutor;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
        mAppHeaderViewHolderFactory = appHeaderViewHolderFactory;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mGenericLinksParser = genericLinksParser;
        mAssistContentRequester = assistContentRequester;
        mMaximizeMenuFactory = maximizeMenuFactory;
        mHandleMenuFactory = handleMenuFactory;
        mMultiInstanceHelper = multiInstanceHelper;
        mWindowManagerWrapper = windowManagerWrapper;
        mWindowDecorCaptionHandleRepository = windowDecorCaptionHandleRepository;
        mDesktopUserRepositories = desktopUserRepositories;
    }

    /**
     * Register a listener to be called back when one of the tasks' maximize/restore action is
     * triggered.
     * TODO(b/346441962): hook this up to double-tap and the header's maximize button, instead of
     *  having the ViewModel deal with parsing motion events.
     */
    void setOnMaximizeOrRestoreClickListener(Function0<Unit> listener) {
        mOnMaximizeOrRestoreClickListener = listener;
    }

    /**
     * Registers a listener to be called back when one of the tasks' immersive/restore action is
     * triggered.
     */
    void setOnImmersiveOrRestoreClickListener(Function0<Unit> listener) {
        mOnImmersiveOrRestoreClickListener = listener;
    }

    /** Registers a listener to be called when the decoration's snap-left action is triggered.*/
    void setOnLeftSnapClickListener(Function0<Unit> listener) {
        mOnLeftSnapClickListener = listener;
    }

    /** Registers a listener to be called when the decoration's snap-right action is triggered. */
    void setOnRightSnapClickListener(Function0<Unit> listener) {
        mOnRightSnapClickListener = listener;
    }

    /** Registers a listener to be called when the decoration's to-desktop action is triggered. */
    void setOnToDesktopClickListener(Consumer<DesktopModeTransitionSource> listener) {
        mOnToDesktopClickListener = listener;
    }

    /**
     * Registers a listener to be called when the decoration's to-fullscreen action is triggered.
     */
    void setOnToFullscreenClickListener(Function0<Unit> listener) {
        mOnToFullscreenClickListener = listener;
    }

    /** Registers a listener to be called when the decoration's to-split action is triggered. */
    void setOnToSplitScreenClickListener(Function0<Unit> listener) {
        mOnToSplitscreenClickListener = listener;
    }

    /**
     * Adds a drag resize observer that gets notified on the task being drag resized.
     *
     * @param dragResizeListener The observing object to be added.
     */
    public void addDragResizeListener(DragEventListener dragResizeListener) {
        mTaskDragResizer.addDragEventListener(dragResizeListener);
    }

    /**
     * Removes an already existing drag resize observer.
     *
     * @param dragResizeListener observer to be removed.
     */
    public void removeDragResizeListener(DragEventListener dragResizeListener) {
        mTaskDragResizer.removeDragEventListener(dragResizeListener);
    }

    /** Registers a listener to be called when the decoration's new window action is triggered. */
    void setOnNewWindowClickListener(Function0<Unit> listener) {
        mOnNewWindowClickListener = listener;
    }

    /**
     * Registers a listener to be called when the decoration's manage windows action is
     * triggered.
     */
    void setManageWindowsClickListener(Function0<Unit> listener) {
        mOnManageWindowsClickListener = listener;
    }

    /** Registers a listener to be called when the aspect ratio action is triggered. */
    void setOnChangeAspectRatioClickListener(Function0<Unit> listener) {
        mOnChangeAspectRatioClickListener = listener;
    }

    /** Registers a listener to be called when the maximize header button is hovered. */
    void setOnMaximizeHoverListener(Function0<Unit> listener) {
        mOnMaximizeHoverListener = listener;
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener,
            View.OnLongClickListener onLongClickListener,
            View.OnGenericMotionListener onGenericMotionListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
        mOnCaptionLongClickListener = onLongClickListener;
        mOnCaptionGenericMotionListener = onGenericMotionListener;
    }

    void setExclusionRegionListener(ExclusionRegionListener exclusionRegionListener) {
        mExclusionRegionListener = exclusionRegionListener;
    }

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
    }

    void setOpenInBrowserClickListener(Consumer<Intent> listener) {
        mOpenInBrowserClickListener = listener;
    }

    @Override
    void relayout(ActivityManager.RunningTaskInfo taskInfo, boolean hasGlobalFocus,
            @NonNull Region displayExclusionRegion) {
        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        // The visibility, crop and position of the task should only be set when a task is
        // fluid resizing. In all other cases, it is expected that the transition handler sets
        // those task properties to allow the handler time to animate with full control of the task
        // leash. In general, allowing the window decoration to set any of these is likely to cause
        // incorrect frames and flickering because relayouts from TaskListener#onTaskInfoChanged
        // aren't synchronized with shell transition callbacks, so if they come too early it
        // might show/hide or crop the task at a bad time.
        // Fluid resizing is exempt from this because it intentionally doesn't use shell
        // transitions to resize the task, so onTaskInfoChanged relayouts is the only way to make
        // sure the crop is set correctly.
        final boolean shouldSetTaskVisibilityPositionAndCrop =
                !DesktopModeStatus.isVeiledResizeEnabled()
                        && mTaskDragResizer.isResizingOrAnimating();
        // For headers only (i.e. in freeform): use |applyStartTransactionOnDraw| so that the
        // transaction (that applies task crop) is synced with the buffer transaction (that draws
        // the View). Both will be shown on screen at the same, whereas applying them independently
        // causes flickering. See b/270202228.
        final boolean applyTransactionOnDraw = taskInfo.isFreeform();
        relayout(taskInfo, t, t, applyTransactionOnDraw, shouldSetTaskVisibilityPositionAndCrop,
                hasGlobalFocus, displayExclusionRegion);
        if (!applyTransactionOnDraw) {
            t.apply();
        }
    }

    /**
     * Disables resizing for the given edge.
     *
     * @param disabledResizingEdge edge to disable.
     * @param shouldDelayUpdate whether the update should be executed immediately or delayed.
     */
    public void updateDisabledResizingEdge(
            DragResizeWindowGeometry.DisabledEdge disabledResizingEdge, boolean shouldDelayUpdate) {
        mDisabledResizingEdge = disabledResizingEdge;
        final boolean inFullImmersive = mDesktopUserRepositories.getCurrent()
                .isTaskInFullImmersiveState(mTaskInfo.taskId);
        if (shouldDelayUpdate) {
            return;
        }
        updateDragResizeListener(mDecorationContainerSurface, inFullImmersive);
    }


    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw, boolean shouldSetTaskVisibilityPositionAndCrop,
            boolean hasGlobalFocus, @NonNull Region displayExclusionRegion) {
        Trace.beginSection("DesktopModeWindowDecoration#relayout");

        if (Flags.enableDesktopWindowingAppToWeb()) {
            setCapturedLink(taskInfo.capturedLink, taskInfo.capturedLinkTimestamp);
        }

        if (isHandleMenuActive()) {
            mHandleMenu.relayout(
                    startT,
                    mResult.mCaptionX,
                    // Add top padding to the caption Y so that the menu is shown over what is the
                    // actual contents of the caption, ignoring padding. This is currently relevant
                    // to the Header in desktop immersive.
                    mResult.mCaptionY + mResult.mCaptionTopPadding);
        }

        if (isOpenByDefaultDialogActive()) {
            mOpenByDefaultDialog.relayout(taskInfo);
        }

        final boolean inFullImmersive = mDesktopUserRepositories.getProfile(taskInfo.userId)
                .isTaskInFullImmersiveState(taskInfo.taskId);
        updateRelayoutParams(mRelayoutParams, mContext, taskInfo, mSplitScreenController,
                applyStartTransactionOnDraw, shouldSetTaskVisibilityPositionAndCrop,
                mIsStatusBarVisible, mIsKeyguardVisibleAndOccluded, inFullImmersive,
                mDisplayController.getInsetsState(taskInfo.displayId), hasGlobalFocus,
                displayExclusionRegion);

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        Trace.beginSection("DesktopModeWindowDecoration#relayout-applyWCT");
        mBgExecutor.execute(() -> mTaskOrganizer.applyTransaction(wct));
        Trace.endSection();

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            if (canEnterDesktopMode(mContext) && isEducationEnabled()) {
                notifyNoCaptionHandle();
            }
            mExclusionRegionListener.onExclusionRegionDismissed(mTaskInfo.taskId);
            disposeStatusBarInputLayer();
            Trace.endSection(); // DesktopModeWindowDecoration#relayout
            return;
        }

        if (oldRootView != mResult.mRootView) {
            disposeStatusBarInputLayer();
            mWindowDecorViewHolder = createViewHolder();
        }

        final Point position = new Point();
        if (isAppHandle(mWindowDecorViewHolder)) {
            position.set(determineHandlePosition());
        }
        if (canEnterDesktopMode(mContext) && isEducationEnabled()) {
            notifyCaptionStateChanged();
        }

        Trace.beginSection("DesktopModeWindowDecoration#relayout-bindData");
        if (isAppHandle(mWindowDecorViewHolder)) {
            mWindowDecorViewHolder.bindData(new AppHandleViewHolder.HandleData(
                    mTaskInfo, position, mResult.mCaptionWidth, mResult.mCaptionHeight,
                    isCaptionVisible()
            ));
        } else {
            mWindowDecorViewHolder.bindData(new AppHeaderViewHolder.HeaderData(
                    mTaskInfo,
                    DesktopModeUtils.isTaskMaximized(mTaskInfo, mDisplayController),
                    inFullImmersive,
                    hasGlobalFocus,
                    /* maximizeHoverEnabled= */ canOpenMaximizeMenu(
                            /* animatingTaskResizeOrReposition= */ false)
            ));
        }
        Trace.endSection();

        if (!hasGlobalFocus) {
            closeHandleMenu();
            closeManageWindowsMenu();
            closeMaximizeMenu();
        }
        updateDragResizeListener(oldDecorationSurface, inFullImmersive);
        updateMaximizeMenu(startT, inFullImmersive);
        Trace.endSection(); // DesktopModeWindowDecoration#relayout
    }

    private boolean isCaptionVisible() {
        return mTaskInfo.isVisible && mIsCaptionVisible;
    }

    private void setCapturedLink(Uri capturedLink, long timeStamp) {
        if (capturedLink == null
                || (mCapturedLink != null && mCapturedLink.mTimeStamp == timeStamp)) {
            return;
        }
        mCapturedLink = new CapturedLink(capturedLink, timeStamp);
    }

    @Nullable
    private Intent getBrowserLink() {
        final Uri browserLink;
        if (isCapturedLinkAvailable()) {
            browserLink = mCapturedLink.mUri;
        } else if (mWebUri != null) {
            browserLink = mWebUri;
        } else {
            browserLink = mGenericLink;
        }

        if (browserLink == null) return null;
        return AppToWebUtils.getBrowserIntent(browserLink, mContext.getPackageManager());

    }

    @Nullable
    private Intent getAppLink() {
        return mWebUri == null ? null
                : AppToWebUtils.getAppIntent(mWebUri, mContext.getPackageManager());
    }

    private boolean isBrowserApp() {
        final ComponentName baseActivity = mTaskInfo.baseActivity;
        return baseActivity != null && AppToWebUtils.isBrowserApp(mContext,
                baseActivity.getPackageName(), mUserContext.getUserId());
    }

    UserHandle getUser() {
        return mUserContext.getUser();
    }

    private void updateDragResizeListener(SurfaceControl oldDecorationSurface,
            boolean inFullImmersive) {
        if (!isDragResizable(mTaskInfo, inFullImmersive)) {
            if (!mTaskInfo.positionInParent.equals(mPositionInParent)) {
                // We still want to track caption bar's exclusion region on a non-resizeable task.
                updateExclusionRegion(inFullImmersive);
            }
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            Trace.beginSection("DesktopModeWindowDecoration#relayout-DragResizeInputListener");
            mDragResizeListener = new DragResizeInputListener(
                    mContext,
                    mTaskInfo,
                    mHandler,
                    mChoreographer,
                    mDisplay.getDisplayId(),
                    mDecorationContainerSurface,
                    mDragPositioningCallback,
                    mSurfaceControlBuilderSupplier,
                    mSurfaceControlTransactionSupplier,
                    mDisplayController,
                    mDesktopModeEventLogger);
            Trace.endSection();
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();

        // If either task geometry or position have changed, update this task's
        // exclusion region listener
        final Resources res = mResult.mRootView.getResources();
        if (mDragResizeListener.setGeometry(
                new DragResizeWindowGeometry(mRelayoutParams.mCornerRadius,
                        new Size(mResult.mWidth, mResult.mHeight),
                        getResizeEdgeHandleSize(res), getResizeHandleEdgeInset(res),
                        getFineResizeCornerSize(res), getLargeResizeCornerSize(res),
                        mDisabledResizingEdge), touchSlop)
                || !mTaskInfo.positionInParent.equals(mPositionInParent)) {
            updateExclusionRegion(inFullImmersive);
        }
    }

    private static boolean isDragResizable(ActivityManager.RunningTaskInfo taskInfo,
            boolean inFullImmersive) {
        if (inFullImmersive) {
            // Task cannot be resized in full immersive.
            return false;
        }
        if (DesktopModeFlags.ENABLE_WINDOWING_SCALED_RESIZING.isTrue()) {
            return taskInfo.isFreeform();
        }
        return taskInfo.isFreeform() && taskInfo.isResizeable;
    }

    private void notifyCaptionStateChanged() {
        // TODO: b/366159408 - Ensure bounds sent with notification account for RTL mode.
        if (!canEnterDesktopMode(mContext) || !isEducationEnabled()) {
            return;
        }
        if (!isCaptionVisible()) {
            notifyNoCaptionHandle();
        } else if (isAppHandle(mWindowDecorViewHolder)) {
            // App handle is visible since `mWindowDecorViewHolder` is of type
            // [AppHandleViewHolder].
            final CaptionState captionState = new CaptionState.AppHandle(mTaskInfo,
                    isHandleMenuActive(), getCurrentAppHandleBounds(), isCapturedLinkAvailable());
            mWindowDecorCaptionHandleRepository.notifyCaptionChanged(captionState);
        } else {
            // App header is visible since `mWindowDecorViewHolder` is of type
            // [AppHeaderViewHolder].
            ((AppHeaderViewHolder) mWindowDecorViewHolder).runOnAppChipGlobalLayout(
                    () -> {
                        notifyAppChipStateChanged();
                        return Unit.INSTANCE;
                    });
        }
    }

    private boolean isCapturedLinkAvailable() {
        return mCapturedLink != null && !mCapturedLink.mUsed;
    }

    private void onCapturedLinkUsed() {
        if (mCapturedLink != null) {
            mCapturedLink.setUsed();
        }
    }

    private void notifyNoCaptionHandle() {
        if (!canEnterDesktopMode(mContext) || !isEducationEnabled()) {
            return;
        }
        mWindowDecorCaptionHandleRepository.notifyCaptionChanged(
                CaptionState.NoCaption.INSTANCE);
    }

    private Rect getCurrentAppHandleBounds() {
        return new Rect(
                mResult.mCaptionX,
                /* top= */0,
                mResult.mCaptionX + mResult.mCaptionWidth,
                mResult.mCaptionHeight);
    }

    private void notifyAppChipStateChanged() {
        final Rect appChipPositionInWindow =
                ((AppHeaderViewHolder) mWindowDecorViewHolder).getAppChipLocationInWindow();
        final Rect taskBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
        final Rect appChipGlobalPosition = new Rect(
                taskBounds.left + appChipPositionInWindow.left,
                taskBounds.top + appChipPositionInWindow.top,
                taskBounds.left + appChipPositionInWindow.right,
                taskBounds.top + appChipPositionInWindow.bottom);
        final CaptionState captionState = new CaptionState.AppHeader(
                mTaskInfo,
                isHandleMenuActive(),
                appChipGlobalPosition,
                isCapturedLinkAvailable());

        mWindowDecorCaptionHandleRepository.notifyCaptionChanged(captionState);
    }

    private void updateMaximizeMenu(SurfaceControl.Transaction startT, boolean inFullImmersive) {
        if (!isDragResizable(mTaskInfo, inFullImmersive) || !isMaximizeMenuActive()) {
            return;
        }
        if (!mTaskInfo.isVisible()) {
            closeMaximizeMenu();
        } else {
            final int menuWidth = calculateMaximizeMenuWidth();
            mMaximizeMenu.positionMenu(calculateMaximizeMenuPosition(menuWidth), startT);
        }
    }

    private Point determineHandlePosition() {
        final Point position = new Point(mResult.mCaptionX, 0);
        if (mSplitScreenController.getSplitPosition(mTaskInfo.taskId)
                == SPLIT_POSITION_BOTTOM_OR_RIGHT
                && mDisplayController.getDisplayLayout(mTaskInfo.displayId).isLandscape()
        ) {
            // If this is the right split task, add left stage's width.
            final Rect leftStageBounds = new Rect();
            mSplitScreenController.getStageBounds(leftStageBounds, new Rect());
            position.x += leftStageBounds.width();
        }
        return position;
    }

    /**
     * Dispose of the view used to forward inputs in status bar region. Intended to be
     * used any time handle is no longer visible.
     */
    void disposeStatusBarInputLayer() {
        if (!isAppHandle(mWindowDecorViewHolder)
                || !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            return;
        }
        asAppHandle(mWindowDecorViewHolder).disposeStatusBarInputLayer();
    }

    private WindowDecorationViewHolder createViewHolder() {
        if (mRelayoutParams.mLayoutResId == R.layout.desktop_mode_app_handle) {
            return new AppHandleViewHolder(
                    mResult.mRootView,
                    mOnCaptionTouchListener,
                    mOnCaptionButtonClickListener,
                    mWindowManagerWrapper,
                    mHandler
            );
        } else if (mRelayoutParams.mLayoutResId
                == R.layout.desktop_mode_app_header) {
            loadAppInfoIfNeeded();
            return mAppHeaderViewHolderFactory.create(
                    mResult.mRootView,
                    mOnCaptionTouchListener,
                    mOnCaptionButtonClickListener,
                    mOnCaptionLongClickListener,
                    mOnCaptionGenericMotionListener,
                    mAppName,
                    mAppIconBitmap,
                    mOnMaximizeHoverListener);
        }
        throw new IllegalArgumentException("Unexpected layout resource id");
    }

    private boolean isAppHandle(WindowDecorationViewHolder viewHolder) {
        return viewHolder instanceof AppHandleViewHolder;
    }

    @Nullable
    private AppHandleViewHolder asAppHandle(WindowDecorationViewHolder viewHolder) {
        if (viewHolder instanceof AppHandleViewHolder) {
            return (AppHandleViewHolder) viewHolder;
        }
        return null;
    }

    @Nullable
    private AppHeaderViewHolder asAppHeader(WindowDecorationViewHolder viewHolder) {
        if (viewHolder instanceof AppHeaderViewHolder) {
            return (AppHeaderViewHolder) viewHolder;
        }
        return null;
    }

    @VisibleForTesting
    static void updateRelayoutParams(
            RelayoutParams relayoutParams,
            Context context,
            ActivityManager.RunningTaskInfo taskInfo,
            SplitScreenController splitScreenController,
            boolean applyStartTransactionOnDraw,
            boolean shouldSetTaskVisibilityPositionAndCrop,
            boolean isStatusBarVisible,
            boolean isKeyguardVisibleAndOccluded,
            boolean inFullImmersiveMode,
            @NonNull InsetsState displayInsetsState,
            boolean hasGlobalFocus,
            @NonNull Region displayExclusionRegion) {
        final int captionLayoutId = getDesktopModeWindowDecorLayoutId(taskInfo.getWindowingMode());
        final boolean isAppHeader =
                captionLayoutId == R.layout.desktop_mode_app_header;
        final boolean isAppHandle = captionLayoutId == R.layout.desktop_mode_app_handle;
        relayoutParams.reset();
        relayoutParams.mRunningTaskInfo = taskInfo;
        relayoutParams.mLayoutResId = captionLayoutId;
        relayoutParams.mCaptionHeightId = getCaptionHeightIdStatic(taskInfo.getWindowingMode());
        relayoutParams.mCaptionWidthId = getCaptionWidthId(relayoutParams.mLayoutResId);
        relayoutParams.mHasGlobalFocus = hasGlobalFocus;
        relayoutParams.mDisplayExclusionRegion.set(displayExclusionRegion);
        // Allow the handle view to be delayed since the handle is just a small addition to the
        // window, whereas the header cannot be delayed because it is expected to be visible from
        // the first frame.
        relayoutParams.mAsyncViewHost = isAppHandle;

        final boolean showCaption;
        if (Flags.enableFullyImmersiveInDesktop()) {
            if (inFullImmersiveMode) {
                showCaption = isStatusBarVisible && !isKeyguardVisibleAndOccluded;
            } else {
                showCaption = taskInfo.isFreeform()
                        || (isStatusBarVisible && !isKeyguardVisibleAndOccluded);
            }
        } else {
            // Caption should always be visible in freeform mode. When not in freeform,
            // align with the status bar except when showing over keyguard (where it should not
            // shown).
            //  TODO(b/356405803): Investigate how it's possible for the status bar visibility to
            //   be false while a freeform window is open if the status bar is always
            //   forcibly-shown. It may be that the InsetsState (from which |mIsStatusBarVisible|
            //   is set) still contains an invisible insets source in immersive cases even if the
            //   status bar is shown?
            showCaption = taskInfo.isFreeform()
                    || (isStatusBarVisible && !isKeyguardVisibleAndOccluded);
        }
        relayoutParams.mIsCaptionVisible = showCaption;
        final boolean isBottomSplit = !splitScreenController.isLeftRightSplit()
                && splitScreenController.getSplitPosition(taskInfo.taskId)
                == SPLIT_POSITION_BOTTOM_OR_RIGHT;
        relayoutParams.mIsInsetSource = (isAppHeader && !inFullImmersiveMode) || isBottomSplit;
        if (isAppHeader) {
            if (TaskInfoKt.isTransparentCaptionBarAppearance(taskInfo)) {
                // The app is requesting to customize the caption bar, which means input on
                // customizable/exclusion regions must go to the app instead of to the system.
                // This may be accomplished with spy windows or custom touchable regions:
                if (Flags.enableAccessibleCustomHeaders()) {
                    // Set the touchable region of the caption to only the areas where input should
                    // be handled by the system (i.e. non custom-excluded areas). The region will
                    // be calculated based on occluding caption elements and exclusion areas
                    // reported by the app.
                    relayoutParams.mLimitTouchRegionToSystemAreas = true;
                } else {
                    // Allow input to fall through to the windows below so that the app can respond
                    // to input events on their custom content.
                    relayoutParams.mInputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_SPY;
                }
            } else {
                if (ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION.isTrue()) {
                    // Force-consume the caption bar insets when the app tries to hide the caption.
                    // This improves app compatibility of immersive apps.
                    relayoutParams.mInsetSourceFlags |= FLAG_FORCE_CONSUMING;
                }
            }
            if (ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS.isTrue()) {
                // Always force-consume the caption bar insets for maximum app compatibility,
                // including non-immersive apps that just don't handle caption insets properly.
                relayoutParams.mInsetSourceFlags |= FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
            }
            if (Flags.enableFullyImmersiveInDesktop() && inFullImmersiveMode) {
                final Insets systemBarInsets = displayInsetsState.calculateInsets(
                        taskInfo.getConfiguration().windowConfiguration.getBounds(),
                        WindowInsets.Type.systemBars() & ~WindowInsets.Type.captionBar(),
                        false /* ignoreVisibility */);
                relayoutParams.mCaptionTopPadding = systemBarInsets.top;
            }
            // Report occluding elements as bounding rects to the insets system so that apps can
            // draw in the empty space in the center:
            //   First, the "app chip" section of the caption bar (+ some extra margins).
            final RelayoutParams.OccludingCaptionElement appChipElement =
                    new RelayoutParams.OccludingCaptionElement();
            appChipElement.mWidthResId = R.dimen.desktop_mode_customizable_caption_margin_start;
            appChipElement.mAlignment = RelayoutParams.OccludingCaptionElement.Alignment.START;
            relayoutParams.mOccludingCaptionElements.add(appChipElement);
            //   Then, the right-aligned section (drag space, maximize and close buttons).
            final RelayoutParams.OccludingCaptionElement controlsElement =
                    new RelayoutParams.OccludingCaptionElement();
            controlsElement.mWidthResId = R.dimen.desktop_mode_customizable_caption_margin_end;
            if (Flags.enableMinimizeButton()) {
                controlsElement.mWidthResId =
                      R.dimen.desktop_mode_customizable_caption_with_minimize_button_margin_end;
            }
            controlsElement.mAlignment = RelayoutParams.OccludingCaptionElement.Alignment.END;
            relayoutParams.mOccludingCaptionElements.add(controlsElement);
        } else if (isAppHandle && !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            // The focused decor (fullscreen/split) does not need to handle input because input in
            // the App Handle is handled by the InputMonitor in DesktopModeWindowDecorViewModel.
            // Note: This does not apply with the above flag enabled as the status bar input layer
            // will forward events to the handle directly.
            relayoutParams.mInputFeatures
                    |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        }
        if (isAppHeader
                && DesktopModeStatus.useWindowShadow(/* isFocusedWindow= */ hasGlobalFocus)) {
            relayoutParams.mShadowRadius = hasGlobalFocus
                    ? context.getResources().getDimensionPixelSize(
                            R.dimen.freeform_decor_shadow_focused_thickness)
                    : context.getResources().getDimensionPixelSize(
                            R.dimen.freeform_decor_shadow_unfocused_thickness);
        } else {
            relayoutParams.mShadowRadius = INVALID_SHADOW_RADIUS;
        }
        relayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
        relayoutParams.mSetTaskVisibilityPositionAndCrop = shouldSetTaskVisibilityPositionAndCrop;

        // The configuration used to layout the window decoration. A copy is made instead of using
        // the original reference so that the configuration isn't mutated on config changes and
        // diff checks can be made in WindowDecoration#relayout using the pre/post-relayout
        // configuration. See b/301119301.
        // TODO(b/301119301): consider moving the config data needed for diffs to relayout params
        // instead of using a whole Configuration as a parameter.
        final Configuration windowDecorConfig = new Configuration();
        if (DesktopModeFlags.ENABLE_APP_HEADER_WITH_TASK_DENSITY.isTrue() && isAppHeader) {
            // Should match the density of the task. The task may have had its density overridden
            // to be different that SysUI's.
            windowDecorConfig.setTo(taskInfo.configuration);
        } else if (DesktopModeStatus.useDesktopOverrideDensity()) {
            // The task has had its density overridden, but keep using the system's density to
            // layout the header.
            windowDecorConfig.setTo(context.getResources().getConfiguration());
        } else {
            windowDecorConfig.setTo(taskInfo.configuration);
        }
        relayoutParams.mWindowDecorConfig = windowDecorConfig;

        if (DesktopModeStatus.useRoundedCorners()) {
            relayoutParams.mCornerRadius = taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                    ? loadDimensionPixelSize(context.getResources(),
                    R.dimen.desktop_windowing_freeform_rounded_corner_radius)
                    : INVALID_CORNER_RADIUS;
        }
    }

    /**
     * If task has focused window decor, return the caption id of the fullscreen caption size
     * resource. Otherwise, return ID_NULL and caption width be set to task width.
     */
    private static int getCaptionWidthId(int layoutResId) {
        if (layoutResId == R.layout.desktop_mode_app_handle) {
            return R.dimen.desktop_mode_fullscreen_decor_caption_width;
        }
        return Resources.ID_NULL;
    }

    private int calculateMaximizeMenuWidth() {
        final boolean showImmersive = Flags.enableFullyImmersiveInDesktop()
                && TaskInfoKt.getRequestingImmersive(mTaskInfo);
        final boolean showMaximize = true;
        final boolean showSnaps = mTaskInfo.isResizeable;
        int showCount = 0;
        if (showImmersive) showCount++;
        if (showMaximize) showCount++;
        if (showSnaps) showCount++;
        return switch (showCount) {
            case 1 -> loadDimensionPixelSize(mContext.getResources(),
                    R.dimen.desktop_mode_maximize_menu_width_one_options);
            case 2 -> loadDimensionPixelSize(mContext.getResources(),
                    R.dimen.desktop_mode_maximize_menu_width_two_options);
            case 3 -> loadDimensionPixelSize(mContext.getResources(),
                    R.dimen.desktop_mode_maximize_menu_width_three_options);
            default -> throw new IllegalArgumentException("");
        };
    }

    private PointF calculateMaximizeMenuPosition(int menuWidth) {
        final PointF position = new PointF();
        final Resources resources = mContext.getResources();
        final DisplayLayout displayLayout =
                mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        if (displayLayout == null) return position;

        final int displayWidth = displayLayout.width();
        final int displayHeight = displayLayout.height();
        final int captionHeight = getCaptionHeight(mTaskInfo.getWindowingMode());

        final ImageButton maximizeWindowButton =
                mResult.mRootView.findViewById(R.id.maximize_window);
        final int[] maximizeButtonLocation = new int[2];
        maximizeWindowButton.getLocationInWindow(maximizeButtonLocation);

        final int menuHeight = loadDimensionPixelSize(
                resources, R.dimen.desktop_mode_maximize_menu_height);

        float menuLeft = (mPositionInParent.x + maximizeButtonLocation[0] - ((float) (menuWidth
                - maximizeWindowButton.getWidth()) / 2));
        float menuTop = (mPositionInParent.y + captionHeight);
        final float menuRight = menuLeft + menuWidth;
        final float menuBottom = menuTop + menuHeight;

        // If the menu is out of screen bounds, shift it up/left as needed
        if (menuRight > displayWidth) {
            menuLeft = (displayWidth - menuWidth);
        }
        if (menuBottom > displayHeight) {
            menuTop = (displayHeight - menuHeight);
        }

        return new PointF(menuLeft, menuTop);
    }

    boolean isHandleMenuActive() {
        return mHandleMenu != null;
    }

    boolean isOpenByDefaultDialogActive() {
        return mOpenByDefaultDialog != null;
    }

    void createOpenByDefaultDialog() {
        mOpenByDefaultDialog = new OpenByDefaultDialog(
                mContext,
                mTaskInfo,
                mTaskSurface,
                mDisplayController,
                mSurfaceControlTransactionSupplier,
                new OpenByDefaultDialog.DialogLifecycleListener() {
                    @Override
                    public void onDialogCreated() {
                        closeHandleMenu();
                    }

                    @Override
                    public void onDialogDismissed() {
                        mOpenByDefaultDialog = null;
                    }
                },
                mAppIconBitmap,
                mAppName
        );
    }

    boolean shouldResizeListenerHandleEvent(@NonNull MotionEvent e, @NonNull Point offset) {
        return mDragResizeListener != null && mDragResizeListener.shouldHandleEvent(e, offset);
    }

    boolean isHandlingDragResize() {
        return mDragResizeListener != null && mDragResizeListener.isHandlingDragResize();
    }

    private void loadAppInfoIfNeeded() {
        // TODO(b/337370277): move this to another thread.
        try {
            Trace.beginSection("DesktopModeWindowDecoration#loadAppInfoIfNeeded");
            if (mAppIconBitmap != null && mAppName != null) {
                return;
            }
            if (mTaskInfo.baseIntent == null) {
                Slog.e(TAG, "Base intent not found in task");
                return;
            }
            final PackageManager pm = mUserContext.getPackageManager();
            final ActivityInfo activityInfo =
                    pm.getActivityInfo(mTaskInfo.baseIntent.getComponent(), 0 /* flags */);
            final IconProvider provider = new IconProvider(mContext);
            final Drawable appIconDrawable = provider.getIcon(activityInfo);
            final Drawable badgedAppIconDrawable = pm.getUserBadgedIcon(appIconDrawable,
                    UserHandle.of(mTaskInfo.userId));
            final BaseIconFactory headerIconFactory = createIconFactory(mContext,
                    R.dimen.desktop_mode_caption_icon_radius);
            mAppIconBitmap = headerIconFactory.createIconBitmap(badgedAppIconDrawable,
                    1f /* scale */);

            final BaseIconFactory resizeVeilIconFactory = createIconFactory(mContext,
                    R.dimen.desktop_mode_resize_veil_icon_size);
            mResizeVeilBitmap = resizeVeilIconFactory
                    .createScaledBitmap(appIconDrawable, MODE_DEFAULT);

            final ApplicationInfo applicationInfo = activityInfo.applicationInfo;
            mAppName = pm.getApplicationLabel(applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Base activity's component name cannot be found on the system", e);
        } finally {
            Trace.endSection();
        }
    }

    private BaseIconFactory createIconFactory(Context context, int dimensions) {
        final Resources resources = context.getResources();
        final int densityDpi = resources.getDisplayMetrics().densityDpi;
        final int iconSize = resources.getDimensionPixelSize(dimensions);
        return new BaseIconFactory(context, densityDpi, iconSize);
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    /**
     * Create the resize veil for this task. Note the veil's visibility is View.GONE by default
     * until a resize event calls showResizeVeil below.
     */
    private void createResizeVeilIfNeeded() {
        if (mResizeVeil != null) return;
        loadAppInfoIfNeeded();
        mResizeVeil = new ResizeVeil(mContext, mDisplayController, mResizeVeilBitmap,
                mTaskSurface, mSurfaceControlTransactionSupplier, mTaskInfo);
    }

    /**
     * Show the resize veil.
     */
    public void showResizeVeil(Rect taskBounds) {
        createResizeVeilIfNeeded();
        mResizeVeil.showVeil(mTaskSurface, taskBounds, mTaskInfo);
    }

    /**
     * Show the resize veil.
     */
    public void showResizeVeil(SurfaceControl.Transaction tx, Rect taskBounds) {
        createResizeVeilIfNeeded();
        mResizeVeil.showVeil(tx, mTaskSurface, taskBounds, mTaskInfo, false /* fadeIn */);
    }

    /**
     * Set new bounds for the resize veil
     */
    public void updateResizeVeil(Rect newBounds) {
        mResizeVeil.updateResizeVeil(newBounds);
    }

    /**
     * Set new bounds for the resize veil
     */
    public void updateResizeVeil(SurfaceControl.Transaction tx, Rect newBounds) {
        mResizeVeil.updateResizeVeil(tx, newBounds);
    }

    /**
     * Fade the resize veil out.
     */
    public void hideResizeVeil() {
        mResizeVeil.hideVeil();
    }

    private void disposeResizeVeil() {
        if (mResizeVeil == null) return;
        mResizeVeil.dispose();
        mResizeVeil = null;
    }

    /**
     * Determine valid drag area for this task based on elements in the app chip.
     */
    @Override
    @NonNull
    Rect calculateValidDragArea() {
        final int appTextWidth = ((AppHeaderViewHolder)
                mWindowDecorViewHolder).getAppNameTextWidth();
        final int leftButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.desktop_mode_app_details_width_minus_text) + appTextWidth;
        final int requiredEmptySpace = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.freeform_required_visible_empty_space_in_header);
        final int rightButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.desktop_mode_right_edge_buttons_width);
        final int taskWidth = mTaskInfo.configuration.windowConfiguration.getBounds().width();
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        final int displayWidth = layout.width();
        final Rect stableBounds = new Rect();
        layout.getStableBounds(stableBounds);
        return new Rect(
                determineMinX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth),
                stableBounds.top,
                determineMaxX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth, displayWidth),
                determineMaxY(requiredEmptySpace, stableBounds));
    }


    /**
     * Determine the lowest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMinX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth) {
        // Do not let apps with < 48dp empty header space go off the left edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return 0;
        }
        return -taskWidth + requiredEmptySpace + rightButtonsWidth;
    }

    /**
     * Determine the highest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth, int displayWidth) {
        // Do not let apps with < 48dp empty header space go off the right edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return displayWidth - taskWidth;
        }
        return displayWidth - requiredEmptySpace - leftButtonsWidth;
    }

    /**
     * Determine the highest y coordinate of a freeform task. Used for restricting drag inputs.fmdra
     */
    private int determineMaxY(int requiredEmptySpace, Rect stableBounds) {
        return stableBounds.bottom - requiredEmptySpace;
    }


    /**
     * Create and display maximize menu window
     */
    void createMaximizeMenu() {
        final int menuWidth = calculateMaximizeMenuWidth();
        mMaximizeMenu = mMaximizeMenuFactory.create(mSyncQueue, mRootTaskDisplayAreaOrganizer,
                mDisplayController, mTaskInfo, mContext,
                calculateMaximizeMenuPosition(menuWidth), mSurfaceControlTransactionSupplier);

        mMaximizeMenu.show(
                /* isTaskInImmersiveMode= */ Flags.enableFullyImmersiveInDesktop()
                        && mDesktopUserRepositories.getProfile(mTaskInfo.userId)
                            .isTaskInFullImmersiveState(mTaskInfo.taskId),
                /* menuWidth= */ menuWidth,
                /* showImmersiveOption= */ Flags.enableFullyImmersiveInDesktop()
                        && TaskInfoKt.getRequestingImmersive(mTaskInfo),
                /* showSnapOptions= */ mTaskInfo.isResizeable,
                mOnMaximizeOrRestoreClickListener,
                mOnImmersiveOrRestoreClickListener,
                mOnLeftSnapClickListener,
                mOnRightSnapClickListener,
                hovered -> {
                    mIsMaximizeMenuHovered = hovered;
                    onMaximizeHoverStateChanged();
                    return null;
                },
                () -> {
                    closeMaximizeMenu();
                    return null;
                }
        );
    }

    /** Set whether the app header's maximize button is hovered. */
    void setAppHeaderMaximizeButtonHovered(boolean hovered) {
        mIsAppHeaderMaximizeButtonHovered = hovered;
        onMaximizeHoverStateChanged();
    }

    /**
     * Called when either one of the maximize button in the app header or the maximize menu has
     * changed its hover state.
     */
    void onMaximizeHoverStateChanged() {
        if (!mIsMaximizeMenuHovered && !mIsAppHeaderMaximizeButtonHovered) {
            // Neither is hovered, close the menu.
            if (isMaximizeMenuActive()) {
                mHandler.postDelayed(mCloseMaximizeWindowRunnable, CLOSE_MAXIMIZE_MENU_DELAY_MS);
            }
            return;
        }
        // At least one of the two is hovered, cancel the close if needed.
        mHandler.removeCallbacks(mCloseMaximizeWindowRunnable);
    }

    /**
     * Close the maximize menu window
     */
    void closeMaximizeMenu() {
        if (!isMaximizeMenuActive()) return;
        mMaximizeMenu.close(() -> {
            // Request the accessibility service to refocus on the maximize button after closing
            // the menu.
            final AppHeaderViewHolder appHeader = asAppHeader(mWindowDecorViewHolder);
            if (appHeader != null) {
                appHeader.requestAccessibilityFocus();
            }
            return Unit.INSTANCE;
        });
        mMaximizeMenu = null;
    }

    boolean isMaximizeMenuActive() {
        return mMaximizeMenu != null;
    }

    /**
     * Updates app info and creates and displays handle menu window.
     */
    void createHandleMenu(boolean minimumInstancesFound) {
        // Requests assist content. When content is received, calls {@link #onAssistContentReceived}
        // which sets app info and creates the handle menu.
        mMinimumInstancesFound = minimumInstancesFound;
        mAssistContentRequester.requestAssistContent(
                mTaskInfo.taskId, this::onAssistContentReceived);
    }

    /**
     * Called when assist content is received. updates the saved links and creates the handle menu.
     */
    @VisibleForTesting
    void onAssistContentReceived(@Nullable AssistContent assistContent) {
        mWebUri = assistContent == null ? null : assistContent.getWebUri();
        loadAppInfoIfNeeded();
        updateGenericLink();
        final boolean supportsMultiInstance = mMultiInstanceHelper
                .supportsMultiInstanceSplit(mTaskInfo.baseActivity)
                && Flags.enableDesktopWindowingMultiInstanceFeatures();
        final boolean shouldShowManageWindowsButton = supportsMultiInstance
                && mMinimumInstancesFound;
        final boolean shouldShowChangeAspectRatioButton = HandleMenu.Companion
                .shouldShowChangeAspectRatioButton(mTaskInfo);
        final boolean inDesktopImmersive = mDesktopUserRepositories.getProfile(mTaskInfo.userId)
                .isTaskInFullImmersiveState(mTaskInfo.taskId);
        final boolean isBrowserApp = isBrowserApp();
        mHandleMenu = mHandleMenuFactory.create(
                this,
                mWindowManagerWrapper,
                mRelayoutParams.mLayoutResId,
                mAppIconBitmap,
                mAppName,
                mSplitScreenController,
                canEnterDesktopModeOrShowAppHandle(mContext),
                supportsMultiInstance,
                shouldShowManageWindowsButton,
                shouldShowChangeAspectRatioButton,
                canEnterDesktopMode(mContext),
                isBrowserApp,
                isBrowserApp ? getAppLink() : getBrowserLink(),
                mResult.mCaptionWidth,
                mResult.mCaptionHeight,
                mResult.mCaptionX,
                // Add top padding to the caption Y so that the menu is shown over what is the
                // actual contents of the caption, ignoring padding. This is currently relevant
                // to the Header in desktop immersive.
                mResult.mCaptionY + mResult.mCaptionTopPadding
        );
        mWindowDecorViewHolder.onHandleMenuOpened();
        mHandleMenu.show(
                /* onToDesktopClickListener= */ () -> {
                    mOnToDesktopClickListener.accept(APP_HANDLE_MENU_BUTTON);
                    return Unit.INSTANCE;
                },
                /* onToFullscreenClickListener= */ mOnToFullscreenClickListener,
                /* onToSplitScreenClickListener= */ mOnToSplitscreenClickListener,
                /* onNewWindowClickListener= */ mOnNewWindowClickListener,
                /* onManageWindowsClickListener= */ mOnManageWindowsClickListener,
                /* onAspectRatioSettingsClickListener= */ mOnChangeAspectRatioClickListener,
                /* openInBrowserClickListener= */ (intent) -> {
                    mOpenInBrowserClickListener.accept(intent);
                    onCapturedLinkUsed();
                    if (Flags.enableDesktopWindowingAppToWebEducationIntegration()) {
                        mWindowDecorCaptionHandleRepository.onAppToWebUsage();
                    }
                    return Unit.INSTANCE;
                },
                /* onOpenByDefaultClickListener= */ () -> {
                    if (!isOpenByDefaultDialogActive()) {
                        createOpenByDefaultDialog();
                    }
                    return Unit.INSTANCE;
                },
                /* onCloseMenuClickListener= */ () -> {
                    closeHandleMenu();
                    return Unit.INSTANCE;
                },
                /* onOutsideTouchListener= */ () -> {
                    closeHandleMenu();
                    return Unit.INSTANCE;
                },
                /* forceShowSystemBars= */ inDesktopImmersive
        );
        if (canEnterDesktopMode(mContext) && isEducationEnabled()) {
            notifyCaptionStateChanged();
        }
        mMinimumInstancesFound = false;
    }

    void createManageWindowsMenu(@NonNull List<Pair<Integer, TaskSnapshot>> snapshotList,
            @NonNull Function1<Integer, Unit> onIconClickListener
    ) {
        if (mTaskInfo.isFreeform()) {
            // The menu uses display-wide coordinates for positioning, so make position the sum
            // of task position and caption position.
            final Rect taskBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
            mManageWindowsMenu = new DesktopHeaderManageWindowsMenu(
                    mTaskInfo,
                    /* x= */ taskBounds.left + mResult.mCaptionX,
                    /* y= */ taskBounds.top + mResult.mCaptionY + mResult.mCaptionTopPadding,
                    mDisplayController,
                    mRootTaskDisplayAreaOrganizer,
                    mContext,
                    mDesktopUserRepositories,
                    mSurfaceControlBuilderSupplier,
                    mSurfaceControlTransactionSupplier,
                    snapshotList,
                    onIconClickListener,
                    /* onOutsideClickListener= */ () -> {
                        closeManageWindowsMenu();
                        return Unit.INSTANCE;
                    }
                    );
        } else {
            mManageWindowsMenu = new DesktopHandleManageWindowsMenu(
                    mTaskInfo,
                    mSplitScreenController,
                    getCaptionX(),
                    mResult.mCaptionWidth,
                    mWindowManagerWrapper,
                    mContext,
                    snapshotList,
                    onIconClickListener,
                    /* onOutsideClickListener= */ () -> {
                        closeManageWindowsMenu();
                        return Unit.INSTANCE;
                    }
                    );
        }
    }

    void closeManageWindowsMenu() {
        if (mManageWindowsMenu != null) {
            mManageWindowsMenu.animateClose();
        }
        mManageWindowsMenu = null;
    }

    private void updateGenericLink() {
        final ComponentName baseActivity = mTaskInfo.baseActivity;
        if (baseActivity == null) {
            return;
        }

        final String genericLink =
                mGenericLinksParser.getGenericLink(baseActivity.getPackageName());
        mGenericLink = genericLink == null ? null : Uri.parse(genericLink);
    }

    /**
     * Close the handle menu window.
     */
    void closeHandleMenu() {
        if (!isHandleMenuActive()) return;
        mWindowDecorViewHolder.onHandleMenuClosed();
        mHandleMenu.close();
        mHandleMenu = null;
        if (canEnterDesktopMode(mContext) && isEducationEnabled()) {
            notifyCaptionStateChanged();
        }
    }

    @Override
    void releaseViews(WindowContainerTransaction wct) {
        closeHandleMenu();
        closeManageWindowsMenu();
        closeMaximizeMenu();
        super.releaseViews(wct);
    }

    /**
     * Close an open handle menu if input is outside of menu coordinates
     *
     * @param ev the tapped point to compare against
     */
    void closeHandleMenuIfNeeded(MotionEvent ev) {
        if (!isHandleMenuActive()) return;

        PointF inputPoint = offsetCaptionLocation(ev);

        // If this is called before open_menu_button's onClick, we don't want to close
        // the menu since it will just reopen in onClick.
        final boolean pointInOpenMenuButton = pointInView(
                mResult.mRootView.findViewById(R.id.open_menu_button),
                inputPoint.x,
                inputPoint.y);

        if (!mHandleMenu.isValidMenuInput(inputPoint) && !pointInOpenMenuButton) {
            closeHandleMenu();
        }
    }

    boolean isFocused() {
        return mHasGlobalFocus;
    }

    /**
     * Offset the coordinates of a {@link MotionEvent} to be in the same coordinate space as caption
     *
     * @param ev the {@link MotionEvent} to offset
     * @return the point of the input in local space
     */
    private PointF offsetCaptionLocation(MotionEvent ev) {
        final PointF result = new PointF(ev.getX(), ev.getY());
        final ActivityManager.RunningTaskInfo taskInfo =
                mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId);
        if (taskInfo == null) return result;
        final Point positionInParent = taskInfo.positionInParent;
        result.offset(-positionInParent.x, -positionInParent.y);
        return result;
    }

    /**
     * Checks if motion event occurs in the caption handle area of a focused caption (the caption on
     * a task in fullscreen or in multi-windowing mode). This should be used in cases where
     * onTouchListener will not work (i.e. when caption is in status bar area).
     *
     * @param ev       the {@link MotionEvent} to check
     * @return {@code true} if event is inside caption handle view, {@code false} if not
     */
    boolean checkTouchEventInFocusedCaptionHandle(MotionEvent ev) {
        if (isHandleMenuActive() || !isAppHandle(mWindowDecorViewHolder)
                || DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
            return false;
        }
        // The status bar input layer can only receive input in handle coordinates to begin with,
        // so checking coordinates is unnecessary as input is always within handle bounds.
        if (isAppHandle(mWindowDecorViewHolder)
                && DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()
                && isCaptionVisible()) {
            return true;
        }

        return checkTouchEventInCaption(ev);
    }

    /**
     * Checks if touch event occurs in caption.
     *
     * @param ev       the {@link MotionEvent} to check
     * @return {@code true} if event is inside caption view, {@code false} if not
     */
    boolean checkTouchEventInCaption(MotionEvent ev) {
        final PointF inputPoint = offsetCaptionLocation(ev);
        return inputPoint.x >= mResult.mCaptionX
                && inputPoint.x <= mResult.mCaptionX + mResult.mCaptionWidth
                && inputPoint.y >= 0
                && inputPoint.y <= mResult.mCaptionHeight;
    }

    /**
     * Checks whether the touch event falls inside the customizable caption region.
     */
    boolean checkTouchEventInCustomizableRegion(MotionEvent ev) {
        return mResult.mCustomizableCaptionRegion.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /**
     * Check a passed MotionEvent if it has occurred on any button related to this decor.
     * Note this should only be called when a regular onClick is not possible
     * (i.e. the button was clicked through status bar layer)
     *
     * @param ev the MotionEvent to compare
     */
    void checkTouchEvent(MotionEvent ev) {
        if (mResult.mRootView == null || DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) return;
        final View caption = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
        final View handle = caption.findViewById(R.id.caption_handle);
        final boolean inHandle = !isHandleMenuActive()
                && checkTouchEventInFocusedCaptionHandle(ev);
        final int action = ev.getActionMasked();
        if (action == ACTION_UP && inHandle) {
            handle.performClick();
        }
        if (isHandleMenuActive()) {
            // If the whole handle menu can be touched directly, rely on FLAG_WATCH_OUTSIDE_TOUCH.
            // This is for the case that some of the handle menu is underneath the status bar.
            if (isAppHandle(mWindowDecorViewHolder)
                    && !DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) {
                mHandleMenu.checkMotionEvent(ev);
                closeHandleMenuIfNeeded(ev);
            }
        }
    }

    /**
     * Updates hover and pressed status of views in this decoration. Should only be called
     * when status cannot be updated normally (i.e. the button is hovered through status
     * bar layer).
     * @param ev the MotionEvent to compare against.
     */
    void updateHoverAndPressStatus(MotionEvent ev) {
        if (mResult.mRootView == null || DesktopModeFlags.ENABLE_HANDLE_INPUT_FIX.isTrue()) return;
        final View handle = mResult.mRootView.findViewById(R.id.caption_handle);
        final boolean inHandle = !isHandleMenuActive()
                && checkTouchEventInFocusedCaptionHandle(ev);
        final int action = ev.getActionMasked();
        // The comparison against ACTION_UP is needed for the cancel drag to desktop case.
        handle.setHovered(inHandle && action != ACTION_UP);
        // We want handle to remain pressed if the pointer moves outside of it during a drag.
        handle.setPressed((inHandle && action == ACTION_DOWN)
                || (handle.isPressed() && action != ACTION_UP && action != ACTION_CANCEL));
        if (isHandleMenuActive()) {
            mHandleMenu.checkMotionEvent(ev);
        }
    }

    private boolean pointInView(View v, float x, float y) {
        return v != null && v.getLeft() <= x && v.getRight() >= x
                && v.getTop() <= y && v.getBottom() >= y;
    }

    /** Returns true if at least one education flag is enabled. */
    private boolean isEducationEnabled() {
        return Flags.enableDesktopWindowingAppHandleEducation()
                || Flags.enableDesktopWindowingAppToWebEducationIntegration();
    }

    @Override
    public void close() {
        closeDragResizeListener();
        closeHandleMenu();
        closeManageWindowsMenu();
        mExclusionRegionListener.onExclusionRegionDismissed(mTaskInfo.taskId);
        disposeResizeVeil();
        disposeStatusBarInputLayer();
        if (canEnterDesktopMode(mContext) && isEducationEnabled()) {
            notifyNoCaptionHandle();
        }
        super.close();
    }

    private static int getDesktopModeWindowDecorLayoutId(@WindowingMode int windowingMode) {
        return windowingMode == WINDOWING_MODE_FREEFORM
                ? R.layout.desktop_mode_app_header
                : R.layout.desktop_mode_app_handle;
    }

    private void updatePositionInParent() {
        mPositionInParent.set(mTaskInfo.positionInParent);
    }

    private void updateExclusionRegion(boolean inFullImmersive) {
        // An outdated position in parent is one reason for this to be called; update it here.
        updatePositionInParent();
        mExclusionRegionListener
                .onExclusionRegionChanged(mTaskInfo.taskId,
                        getGlobalExclusionRegion(inFullImmersive));
    }

    /**
     * Create a new exclusion region from the corner rects (if resizeable) and caption bounds
     * of this task.
     */
    private Region getGlobalExclusionRegion(boolean inFullImmersive) {
        Region exclusionRegion;
        if (mDragResizeListener != null && isDragResizable(mTaskInfo, inFullImmersive)) {
            exclusionRegion = mDragResizeListener.getCornersRegion();
        } else {
            exclusionRegion = new Region();
        }
        if (inFullImmersive) {
            // Task can't be moved in full immersive, so skip excluding the caption region.
            return exclusionRegion;
        }
        exclusionRegion.union(new Rect(0, 0, mResult.mWidth,
                getCaptionHeight(mTaskInfo.getWindowingMode())));
        exclusionRegion.translate(mPositionInParent.x, mPositionInParent.y);
        return exclusionRegion;
    }

    int getCaptionX() {
        return mResult.mCaptionX;
    }

    @Override
    int getCaptionHeightId(@WindowingMode int windowingMode) {
        return getCaptionHeightIdStatic(windowingMode);
    }

    private static int getCaptionHeightIdStatic(@WindowingMode int windowingMode) {
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                ? com.android.internal.R.dimen.status_bar_height_default
                : R.dimen.desktop_mode_freeform_decor_caption_height;
    }

    private int getCaptionHeight(@WindowingMode int windowingMode) {
        return loadDimensionPixelSize(mContext.getResources(), getCaptionHeightId(windowingMode));
    }

    @Override
    int getCaptionViewId() {
        return R.id.desktop_mode_caption;
    }

    void setAnimatingTaskResizeOrReposition(boolean animatingTaskResizeOrReposition) {
        if (mRelayoutParams.mLayoutResId == R.layout.desktop_mode_app_handle) return;
        final boolean inFullImmersive =
                mDesktopUserRepositories.getProfile(mTaskInfo.userId)
                        .isTaskInFullImmersiveState(mTaskInfo.taskId);
        asAppHeader(mWindowDecorViewHolder).bindData(new AppHeaderViewHolder.HeaderData(
                mTaskInfo,
                DesktopModeUtils.isTaskMaximized(mTaskInfo, mDisplayController),
                inFullImmersive,
                isFocused(),
                /* maximizeHoverEnabled= */ canOpenMaximizeMenu(animatingTaskResizeOrReposition)));
    }

    /**
     * Called when there is a {@link MotionEvent#ACTION_HOVER_EXIT} on the maximize window button.
     */
    void onMaximizeButtonHoverExit() {
        asAppHeader(mWindowDecorViewHolder).onMaximizeWindowHoverExit();
    }

    /**
     * Called when there is a {@link MotionEvent#ACTION_HOVER_ENTER} on the maximize window button.
     */
    void onMaximizeButtonHoverEnter() {
        asAppHeader(mWindowDecorViewHolder).onMaximizeWindowHoverEnter();
    }

    private boolean canOpenMaximizeMenu(boolean animatingTaskResizeOrReposition) {
        if (!Flags.enableFullyImmersiveInDesktop()) {
            return !animatingTaskResizeOrReposition;
        }
        final boolean inImmersiveAndRequesting =
                mDesktopUserRepositories.getProfile(mTaskInfo.userId)
                        .isTaskInFullImmersiveState(mTaskInfo.taskId)
                    && TaskInfoKt.getRequestingImmersive(mTaskInfo);
        return !animatingTaskResizeOrReposition && !inImmersiveAndRequesting;
    }

    @Override
    public String toString() {
        return "{"
                + "mPositionInParent=" + mPositionInParent + ", "
                + "taskId=" + mTaskInfo.taskId + ", "
                + "windowingMode=" + windowingModeToString(mTaskInfo.getWindowingMode()) + ", "
                + "isFocused=" + isFocused()
                + "}";
    }

    static class Factory {

        DesktopModeWindowDecoration create(
                Context context,
                @NonNull Context userContext,
                DisplayController displayController,
                SplitScreenController splitScreenController,
                DesktopUserRepositories desktopUserRepositories,
                ShellTaskOrganizer taskOrganizer,
                ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Handler handler,
                @ShellBackgroundThread ShellExecutor bgExecutor,
                Choreographer choreographer,
                SyncTransactionQueue syncQueue,
                AppHeaderViewHolder.Factory appHeaderViewHolderFactory,
                RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
                AppToWebGenericLinksParser genericLinksParser,
                AssistContentRequester assistContentRequester,
                @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost>
                        windowDecorViewHostSupplier,
                MultiInstanceHelper multiInstanceHelper,
                WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
                DesktopModeEventLogger desktopModeEventLogger) {
            return new DesktopModeWindowDecoration(
                    context,
                    userContext,
                    displayController,
                    splitScreenController,
                    desktopUserRepositories,
                    taskOrganizer,
                    taskInfo,
                    taskSurface,
                    handler,
                    bgExecutor,
                    choreographer,
                    syncQueue,
                    appHeaderViewHolderFactory,
                    rootTaskDisplayAreaOrganizer,
                    genericLinksParser,
                    assistContentRequester,
                    windowDecorViewHostSupplier,
                    multiInstanceHelper,
                    windowDecorCaptionHandleRepository,
                    desktopModeEventLogger);
        }
    }

    @VisibleForTesting
    static class CapturedLink {
        private final long mTimeStamp;
        private final Uri mUri;
        private boolean mUsed;

        CapturedLink(@NonNull Uri uri, long timeStamp) {
            mUri = uri;
            mTimeStamp = timeStamp;
        }

        private void setUsed() {
            mUsed = true;
        }
    }

    interface ExclusionRegionListener {
        /** Inform the implementing class of this task's change in region resize handles */
        void onExclusionRegionChanged(int taskId, Region region);

        /**
         * Inform the implementing class that this task no longer needs an exclusion region,
         * likely due to it closing.
         */
        void onExclusionRegionDismissed(int taskId);
    }
}
