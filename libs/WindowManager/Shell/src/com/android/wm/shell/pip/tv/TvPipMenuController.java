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

package com.android.wm.shell.pip.tv;

import static android.view.WindowManager.SHELL_ROOT_LAYER_PIP;

import android.app.ActivityManager;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;

import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipMenuController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController, TvPipMenuView.Listener {
    private static final String TAG = "TvPipMenuController";
    private static final boolean DEBUG = TvPipController.DEBUG;
    private static final String BACKGROUND_WINDOW_TITLE = "PipBackgroundView";

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final TvPipBoundsState mTvPipBoundsState;
    private final Handler mMainHandler;
    private final int mPipMenuBorderWidth;
    private final int mPipEduTextShowDurationMs;
    private final int mPipEduTextHeight;

    private Delegate mDelegate;
    private SurfaceControl mLeash;
    private TvPipMenuView mPipMenuView;
    private View mPipBackgroundView;

    // User can actively move the PiP via the DPAD.
    private boolean mInMoveMode;
    // Used when only showing the move menu since we want to close the menu completely when
    // exiting the move menu instead of showing the regular button menu.
    private boolean mCloseAfterExitMoveMenu;

    private final List<RemoteAction> mMediaActions = new ArrayList<>();
    private final List<RemoteAction> mAppActions = new ArrayList<>();
    private RemoteAction mCloseAction;

    private SyncRtSurfaceTransactionApplier mApplier;
    private SyncRtSurfaceTransactionApplier mBackgroundApplier;
    RectF mTmpSourceRectF = new RectF();
    RectF mTmpDestinationRectF = new RectF();
    Matrix mMoveTransform = new Matrix();

    private final Runnable mCloseEduTextRunnable = this::closeEduText;

    public TvPipMenuController(Context context, TvPipBoundsState tvPipBoundsState,
            SystemWindows systemWindows, PipMediaController pipMediaController,
            Handler mainHandler) {
        mContext = context;
        mTvPipBoundsState = tvPipBoundsState;
        mSystemWindows = systemWindows;
        mMainHandler = mainHandler;

        // We need to "close" the menu the platform call for all the system dialogs to close (for
        // example, on the Home button press).
        final BroadcastReceiver closeSystemDialogsBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                closeMenu();
            }
        };
        context.registerReceiverForAllUsers(closeSystemDialogsBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null /* permission */,
                mainHandler, Context.RECEIVER_EXPORTED);

        pipMediaController.addActionListener(this::onMediaActionsChanged);

        mPipEduTextShowDurationMs = context.getResources()
                .getInteger(R.integer.pip_edu_text_show_duration_ms);
        mPipEduTextHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_edu_text_view_height);
        mPipMenuBorderWidth = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_border_width);
    }

    void setDelegate(Delegate delegate) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setDelegate(), delegate=%s", TAG, delegate);
        }
        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    @Override
    public void attach(SurfaceControl leash) {
        if (mDelegate == null) {
            throw new IllegalStateException("Delegate is not set.");
        }

        mLeash = leash;
        attachPipMenu();
    }

    private void attachPipMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: attachPipMenu()", TAG);
        }

        if (mPipMenuView != null) {
            detachPipMenu();
        }

        attachPipBackgroundView();
        attachPipMenuView();

        mTvPipBoundsState.setPipMenuPermanentDecorInsets(Insets.of(-mPipMenuBorderWidth,
                    -mPipMenuBorderWidth, -mPipMenuBorderWidth, -mPipMenuBorderWidth));
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.of(0, 0, 0, -mPipEduTextHeight));
        mMainHandler.postDelayed(mCloseEduTextRunnable, mPipEduTextShowDurationMs);
    }

    private void attachPipMenuView() {
        mPipMenuView = new TvPipMenuView(mContext);
        mPipMenuView.setListener(this);
        setUpViewSurfaceZOrder(mPipMenuView, 1);
        addPipMenuViewToSystemWindows(mPipMenuView, MENU_WINDOW_TITLE);
    }

    private void attachPipBackgroundView() {
        mPipBackgroundView = LayoutInflater.from(mContext)
                .inflate(R.layout.tv_pip_menu_background, null);
        setUpViewSurfaceZOrder(mPipBackgroundView, -1);
        addPipMenuViewToSystemWindows(mPipBackgroundView, BACKGROUND_WINDOW_TITLE);
    }

    private void setUpViewSurfaceZOrder(View v, int zOrderRelativeToPip) {
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewRootImpl().addSurfaceChangedCallback(
                            new PipMenuSurfaceChangedCallback(v, zOrderRelativeToPip));
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
        });
    }

    private void addPipMenuViewToSystemWindows(View v, String title) {
        mSystemWindows.addView(v, getPipMenuLayoutParams(title, 0 /* width */, 0 /* height */),
                0 /* displayId */, SHELL_ROOT_LAYER_PIP);
    }

    void notifyPipAnimating(boolean animating) {
        mPipMenuView.setEduTextActive(!animating);
    }

    void showMovementMenuOnly() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showMovementMenuOnly()", TAG);
        }
        mInMoveMode = true;
        mCloseAfterExitMoveMenu = true;
        showMenuInternal();
    }

    @Override
    public void showMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showMenu()", TAG);
        }
        mInMoveMode = false;
        mCloseAfterExitMoveMenu = false;
        showMenuInternal();
    }

    private void showMenuInternal() {
        if (mPipMenuView == null) {
            return;
        }
        maybeCloseEduText();
        maybeUpdateMenuViewActions();
        updateExpansionState();

        grantPipMenuFocus(true);
        if (mInMoveMode) {
            mPipMenuView.showMoveMenu(mDelegate.getPipGravity());
        } else {
            mPipMenuView.showButtonsMenu();
        }
    }

    private void maybeCloseEduText() {
        if (mMainHandler.hasCallbacks(mCloseEduTextRunnable)) {
            mMainHandler.removeCallbacks(mCloseEduTextRunnable);
            mCloseEduTextRunnable.run();
        }
    }

    private void closeEduText() {
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.NONE);
        mPipMenuView.hideEduText();
        mDelegate.closeEduText();
    }

    void updateGravity(int gravity) {
        mPipMenuView.showMovementHints(gravity);
    }

    void updateExpansionState() {
        mPipMenuView.setExpandedModeEnabled(mTvPipBoundsState.isTvExpandedPipSupported()
                && mTvPipBoundsState.getDesiredTvExpandedAspectRatio() != 0);
        mPipMenuView.setIsExpanded(mTvPipBoundsState.isTvPipExpanded());
    }

    private Rect calculateMenuSurfaceBounds(Rect pipBounds) {
        return mPipMenuView.getPipMenuContainerBounds(pipBounds);
    }

    void closeMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: closeMenu()", TAG);
        }

        if (mPipMenuView == null) {
            return;
        }

        mPipMenuView.hideAllUserControls();
        grantPipMenuFocus(false);
        mDelegate.onMenuClosed();
    }

    boolean isInMoveMode() {
        return mInMoveMode;
    }

    @Override
    public void onEnterMoveMode() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onEnterMoveMode - %b, close when exiting move menu: %b", TAG, mInMoveMode,
                    mCloseAfterExitMoveMenu);
        }
        mInMoveMode = true;
        mPipMenuView.showMoveMenu(mDelegate.getPipGravity());
    }

    @Override
    public boolean onExitMoveMode() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onExitMoveMode - %b, close when exiting move menu: %b", TAG, mInMoveMode,
                    mCloseAfterExitMoveMenu);
        }
        if (mCloseAfterExitMoveMenu) {
            mInMoveMode = false;
            mCloseAfterExitMoveMenu = false;
            closeMenu();
            return true;
        }
        if (mInMoveMode) {
            mInMoveMode = false;
            mPipMenuView.showButtonsMenu();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPipMovement(int keycode) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onPipMovement - %b", TAG, mInMoveMode);
        }
        if (mInMoveMode) {
            mDelegate.movePip(keycode);
        }
        return mInMoveMode;
    }

    @Override
    public void detach() {
        closeMenu();
        mMainHandler.removeCallbacks(mCloseEduTextRunnable);
        detachPipMenu();
        mLeash = null;
    }

    @Override
    public void setAppActions(ParceledListSlice<RemoteAction> actions, RemoteAction closeAction) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setAppActions()", TAG);
        }
        updateAdditionalActionsList(mAppActions, actions.getList(), closeAction);
    }

    private void onMediaActionsChanged(List<RemoteAction> actions) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: onMediaActionsChanged()", TAG);
        }

        // Hide disabled actions.
        List<RemoteAction> enabledActions = new ArrayList<>();
        for (RemoteAction remoteAction : actions) {
            if (remoteAction.isEnabled()) {
                enabledActions.add(remoteAction);
            }
        }
        updateAdditionalActionsList(mMediaActions, enabledActions, mCloseAction);
    }

    private void updateAdditionalActionsList(List<RemoteAction> destination,
            @Nullable List<RemoteAction> source, RemoteAction closeAction) {
        final int number = source != null ? source.size() : 0;
        if (number == 0 && destination.isEmpty() && Objects.equals(closeAction, mCloseAction)) {
            // Nothing changed.
            return;
        }

        mCloseAction = closeAction;

        destination.clear();
        if (number > 0) {
            destination.addAll(source);
        }
        maybeUpdateMenuViewActions();
    }

    private void maybeUpdateMenuViewActions() {
        if (mPipMenuView == null) {
            return;
        }
        if (!mAppActions.isEmpty()) {
            mPipMenuView.setAdditionalActions(mAppActions, mCloseAction, mMainHandler);
        } else {
            mPipMenuView.setAdditionalActions(mMediaActions, mCloseAction, mMainHandler);
        }
    }

    @Override
    public boolean isMenuVisible() {
        return true;
    }

    /**
     * Does an immediate window crop of the PiP menu.
     */
    @Override
    public void resizePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: resizePipMenu: %s", TAG, destinationBounds.toShortString());
        }
        if (destinationBounds.isEmpty()) {
            return;
        }

        if (!maybeCreateSyncApplier()) {
            return;
        }

        final Rect menuBounds = calculateMenuSurfaceBounds(destinationBounds);

        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SyncRtSurfaceTransactionApplier.SurfaceParams frontParams =
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(frontSurface)
                .withWindowCrop(menuBounds)
                .build();

        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final SyncRtSurfaceTransactionApplier.SurfaceParams backParams =
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(backSurface)
                .withWindowCrop(menuBounds)
                .build();

        // TODO(b/226580399): switch to using SurfaceSyncer (see b/200284684) to synchronize the
        // animations of the pip surface with the content of the front and back menu surfaces
        mBackgroundApplier.scheduleApply(backParams);
        if (pipLeash != null && t != null) {
            final SyncRtSurfaceTransactionApplier.SurfaceParams
                    pipParams = new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(t)
                    .build();
            mApplier.scheduleApply(frontParams, pipParams);
        } else {
            mApplier.scheduleApply(frontParams);
        }
    }

    private SurfaceControl getSurfaceControl(View v) {
        return mSystemWindows.getViewSurface(v);
    }

    @Override
    public void movePipMenu(SurfaceControl pipLeash, SurfaceControl.Transaction transaction,
            Rect pipDestBounds) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: movePipMenu: %s", TAG, pipDestBounds.toShortString());
        }

        if (pipDestBounds.isEmpty()) {
            if (transaction == null && DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: no transaction given", TAG);
            }
            return;
        }
        if (!maybeCreateSyncApplier()) {
            return;
        }

        final Rect menuDestBounds = calculateMenuSurfaceBounds(pipDestBounds);
        final Rect tmpSourceBounds = new Rect();
        // If there is no pip leash supplied, that means the PiP leash is already finalized
        // resizing and the PiP menu is also resized. We then want to do a scale from the current
        // new menu bounds.
        if (pipLeash != null && transaction != null) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: tmpSourceBounds based on mPipMenuView.getBoundsOnScreen()", TAG);
            }
            mPipMenuView.getBoundsOnScreen(tmpSourceBounds);
        } else {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: tmpSourceBounds based on menu width and height", TAG);
            }
            tmpSourceBounds.set(0, 0, menuDestBounds.width(), menuDestBounds.height());
        }

        mTmpSourceRectF.set(tmpSourceBounds);
        mTmpDestinationRectF.set(menuDestBounds);
        mMoveTransform.setTranslate(mTmpDestinationRectF.left, mTmpDestinationRectF.top);

        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SyncRtSurfaceTransactionApplier.SurfaceParams frontParams =
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(frontSurface)
                        .withMatrix(mMoveTransform)
                        .build();

        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final SyncRtSurfaceTransactionApplier.SurfaceParams backParams =
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(backSurface)
                        .withMatrix(mMoveTransform)
                        .build();

        // TODO(b/226580399): switch to using SurfaceSyncer (see b/200284684) to synchronize the
        // animations of the pip surface with the content of the front and back menu surfaces
        mBackgroundApplier.scheduleApply(backParams);
        if (pipLeash != null && transaction != null) {
            final SyncRtSurfaceTransactionApplier.SurfaceParams pipParams =
                    new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(transaction)
                    .build();
            mApplier.scheduleApply(frontParams, pipParams);
        } else {
            mApplier.scheduleApply(frontParams);
        }

        updateMenuBounds(pipDestBounds);
    }

    private boolean maybeCreateSyncApplier() {
        if (mPipMenuView == null || mPipMenuView.getViewRootImpl() == null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Not going to move PiP, either menu or its parent is not created.", TAG);
            return false;
        }

        if (mApplier == null) {
            mApplier = new SyncRtSurfaceTransactionApplier(mPipMenuView);
        }
        if (mBackgroundApplier == null) {
            mBackgroundApplier = new SyncRtSurfaceTransactionApplier(mPipBackgroundView);
        }
        return true;
    }

    private void detachPipMenu() {
        if (mPipMenuView != null) {
            mApplier = null;
            mSystemWindows.removeView(mPipMenuView);
            mPipMenuView = null;
        }

        if (mPipBackgroundView != null) {
            mBackgroundApplier = null;
            mSystemWindows.removeView(mPipBackgroundView);
            mPipBackgroundView = null;
        }
    }

    @Override
    public void updateMenuBounds(Rect destinationBounds) {
        final Rect menuBounds = calculateMenuSurfaceBounds(destinationBounds);
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: updateMenuBounds: %s", TAG, menuBounds.toShortString());
        }
        mSystemWindows.updateViewLayout(mPipBackgroundView,
                getPipMenuLayoutParams(BACKGROUND_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        mSystemWindows.updateViewLayout(mPipMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));

        if (mPipMenuView != null) {
            mPipMenuView.updateLayout(destinationBounds);
        }
    }

    @Override
    public void onFocusTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: onFocusTaskChanged", TAG);
    }

    @Override
    public void onBackPress() {
        if (!onExitMoveMode()) {
            closeMenu();
        }
    }

    @Override
    public void onCloseButtonClick() {
        mDelegate.closePip();
    }

    @Override
    public void onFullscreenButtonClick() {
        mDelegate.movePipToFullscreen();
    }

    @Override
    public void onToggleExpandedMode() {
        mDelegate.togglePipExpansion();
    }

    interface Delegate {
        void movePipToFullscreen();

        void movePip(int keycode);

        void onInMoveModeChanged();

        int getPipGravity();

        void togglePipExpansion();

        void onMenuClosed();

        void closeEduText();

        void closePip();
    }

    private void grantPipMenuFocus(boolean grantFocus) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: grantWindowFocus(%b)", TAG, grantFocus);
        }

        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    mSystemWindows.getFocusGrantToken(mPipMenuView), grantFocus);
        } catch (Exception e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Unable to update focus, %s", TAG, e);
        }
    }

    private class PipMenuSurfaceChangedCallback implements ViewRootImpl.SurfaceChangedCallback {
        private final View mView;
        private final int mZOrder;

        PipMenuSurfaceChangedCallback(View v, int zOrder) {
            mView = v;
            mZOrder = zOrder;
        }

        @Override
        public void surfaceCreated(SurfaceControl.Transaction t) {
            final SurfaceControl sc = getSurfaceControl(mView);
            if (sc != null) {
                t.setRelativeLayer(sc, mLeash, mZOrder);
            }
        }

        @Override
        public void surfaceReplaced(SurfaceControl.Transaction t) {
        }

        @Override
        public void surfaceDestroyed() {
        }
    }
}
