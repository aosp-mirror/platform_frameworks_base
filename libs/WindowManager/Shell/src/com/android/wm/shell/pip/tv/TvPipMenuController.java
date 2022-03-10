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
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowManagerGlobal;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipMenuController;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController, TvPipMenuView.Listener {
    private static final String TAG = "TvPipMenuController";
    private static final boolean DEBUG = TvPipController.DEBUG;

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final TvPipBoundsState mTvPipBoundsState;
    private final Handler mMainHandler;

    private Delegate mDelegate;
    private SurfaceControl mLeash;
    private TvPipMenuView mPipMenuView;

    // User can actively move the PiP via the DPAD.
    private boolean mInMoveMode;

    private final List<RemoteAction> mMediaActions = new ArrayList<>();
    private final List<RemoteAction> mAppActions = new ArrayList<>();

    private SyncRtSurfaceTransactionApplier mApplier;
    RectF mTmpSourceRectF = new RectF();
    RectF mTmpDestinationRectF = new RectF();
    Matrix mMoveTransform = new Matrix();

    private final float[] mTmpValues = new float[9];
    private final Runnable mUpdateEmbeddedMatrix = () -> {
        if (mPipMenuView == null || mPipMenuView.getViewRootImpl() == null) {
            return;
        }
        mMoveTransform.getValues(mTmpValues);
        try {
            mPipMenuView.getViewRootImpl().getAccessibilityEmbeddedConnection()
                    .setScreenMatrix(mTmpValues);
        } catch (RemoteException e) {
            if (DEBUG) e.printStackTrace();
        }
    };

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
                hideMenu();
            }
        };
        context.registerReceiverForAllUsers(closeSystemDialogsBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null /* permission */,
                mainHandler, Context.RECEIVER_EXPORTED);

        pipMediaController.addActionListener(this::onMediaActionsChanged);
    }

    void setDelegate(Delegate delegate) {
        if (DEBUG) Log.d(TAG, "setDelegate(), delegate=" + delegate);
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
        attachPipMenuView();
    }

    private void attachPipMenuView() {
        if (DEBUG) Log.d(TAG, "attachPipMenuView()");

        if (mPipMenuView != null) {
            detachPipMenuView();
        }

        mPipMenuView = new TvPipMenuView(mContext);
        mPipMenuView.setListener(this);
        mSystemWindows.addView(mPipMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, 0 /* width */, 0 /* height */),
                0, SHELL_ROOT_LAYER_PIP);
    }

    @Override
    public void showMenu() {
        if (DEBUG) Log.d(TAG, "showMenu()");

        if (mPipMenuView != null) {
            Rect menuBounds = getMenuBounds(mTvPipBoundsState.getBounds());
            mSystemWindows.updateViewLayout(mPipMenuView, getPipMenuLayoutParams(
                    MENU_WINDOW_TITLE, menuBounds.width(), menuBounds.height()));
            maybeUpdateMenuViewActions();
            updateExpansionState();

            SurfaceControl menuSurfaceControl = getSurfaceControl();
            if (menuSurfaceControl != null) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.setRelativeLayer(mPipMenuView.getWindowSurfaceControl(), mLeash, 1);
                t.setPosition(menuSurfaceControl, menuBounds.left, menuBounds.top);
                t.apply();
            }
            grantPipMenuFocus(true);
            mPipMenuView.show(mInMoveMode, mDelegate.getPipGravity());
        }
    }

    void updateGravity(int gravity) {
        mPipMenuView.showMovementHints(gravity);
    }

    void updateExpansionState() {
        mPipMenuView.setExpandedModeEnabled(mTvPipBoundsState.isTvExpandedPipSupported()
                && mTvPipBoundsState.getDesiredTvExpandedAspectRatio() != 0);
        mPipMenuView.setIsExpanded(mTvPipBoundsState.isTvPipExpanded());
    }

    private Rect getMenuBounds(Rect pipBounds) {
        int extraSpaceInPx = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_outer_space);
        Rect menuBounds = new Rect(pipBounds);
        menuBounds.inset(-extraSpaceInPx, -extraSpaceInPx);
        return menuBounds;
    }

    void hideMenu() {
        if (!isMenuVisible()) {
            if (DEBUG) Log.d(TAG, "hideMenu() - Menu isn't visible, so don't hide");
            return;
        } else {
            if (DEBUG) Log.d(TAG, "hideMenu()");
        }

        mPipMenuView.hide();
        if (!mInMoveMode) {
            grantPipMenuFocus(false);
            mDelegate.closeMenu();
        }
    }

    boolean isInMoveMode() {
        return mInMoveMode;
    }

    @Override
    public void onEnterMoveMode() {
        if (DEBUG) Log.d(TAG, "onEnterMoveMode - " + mInMoveMode);
        mInMoveMode = true;
        mPipMenuView.showMenuButtons(false);
        mPipMenuView.showMovementHints(mDelegate.getPipGravity());
        mDelegate.onInMoveModeChanged();
    }

    @Override
    public boolean onExitMoveMode() {
        if (DEBUG) Log.d(TAG, "onExitMoveMode - " + mInMoveMode);
        if (mInMoveMode) {
            mInMoveMode = false;
            mPipMenuView.showMenuButtons(true);
            mPipMenuView.hideMovementHints();
            mDelegate.onInMoveModeChanged();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPipMovement(int keycode) {
        if (DEBUG) Log.d(TAG, "onPipMovement - " + mInMoveMode);
        if (mInMoveMode) {
            mDelegate.movePip(keycode);
        }
        return mInMoveMode;
    }

    @Override
    public void detach() {
        hideMenu();
        detachPipMenuView();
        mLeash = null;
    }

    @Override
    public void setAppActions(ParceledListSlice<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "setAppActions()");
        updateAdditionalActionsList(mAppActions, actions.getList());
    }

    private void onMediaActionsChanged(List<RemoteAction> actions) {
        if (DEBUG) Log.d(TAG, "onMediaActionsChanged()");

        // Hide disabled actions.
        List<RemoteAction> enabledActions = new ArrayList<>();
        for (RemoteAction remoteAction : actions) {
            if (remoteAction.isEnabled()) {
                enabledActions.add(remoteAction);
            }
        }
        updateAdditionalActionsList(mMediaActions, enabledActions);
    }

    private void updateAdditionalActionsList(
            List<RemoteAction> destination, @Nullable List<RemoteAction> source) {
        final int number = source != null ? source.size() : 0;
        if (number == 0 && destination.isEmpty()) {
            // Nothing changed.
            return;
        }

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
            mPipMenuView.setAdditionalActions(mAppActions, mMainHandler);
        } else {
            mPipMenuView.setAdditionalActions(mMediaActions, mMainHandler);
        }
    }

    @Override
    public boolean isMenuVisible() {
        boolean isVisible = mPipMenuView != null && mPipMenuView.isVisible();
        if (DEBUG) Log.d(TAG, "isMenuVisible: " + isVisible);
        return isVisible;
    }

    /**
     * Does an immediate window crop of the PiP menu.
     */
    @Override
    public void resizePipMenu(@android.annotation.Nullable SurfaceControl pipLeash,
            @android.annotation.Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {
        if (DEBUG) Log.d(TAG, "resizePipMenu: " + destinationBounds.toShortString());
        if (destinationBounds.isEmpty()) {
            return;
        }

        if (!maybeCreateSyncApplier()) {
            return;
        }

        SurfaceControl surfaceControl = getSurfaceControl();
        SyncRtSurfaceTransactionApplier.SurfaceParams
                params = new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(surfaceControl)
                .withWindowCrop(getMenuBounds(destinationBounds))
                .build();
        if (pipLeash != null && t != null) {
            SyncRtSurfaceTransactionApplier.SurfaceParams
                    pipParams = new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(t)
                    .build();
            mApplier.scheduleApply(params, pipParams);
        } else {
            mApplier.scheduleApply(params);
        }
    }

    private SurfaceControl getSurfaceControl() {
        return mSystemWindows.getViewSurface(mPipMenuView);
    }

    @Override
    public void movePipMenu(SurfaceControl pipLeash, SurfaceControl.Transaction transaction,
            Rect pipDestBounds) {
        if (DEBUG) Log.d(TAG, "movePipMenu: " + pipDestBounds.toShortString());

        if (pipDestBounds.isEmpty()) {
            if (transaction == null && DEBUG) Log.d(TAG, "no transaction given");
            return;
        }
        if (!maybeCreateSyncApplier()) {
            return;
        }

        Rect menuDestBounds = getMenuBounds(pipDestBounds);
        Rect mTmpSourceBounds = new Rect();
        // If there is no pip leash supplied, that means the PiP leash is already finalized
        // resizing and the PiP menu is also resized. We then want to do a scale from the current
        // new menu bounds.
        if (pipLeash != null && transaction != null) {
            if (DEBUG) Log.d(TAG, "mTmpSourceBounds based on mPipMenuView.getBoundsOnScreen()");
            mPipMenuView.getBoundsOnScreen(mTmpSourceBounds);
        } else {
            if (DEBUG) Log.d(TAG, "mTmpSourceBounds based on menu width and height");
            mTmpSourceBounds.set(0, 0, menuDestBounds.width(), menuDestBounds.height());
        }

        mTmpSourceRectF.set(mTmpSourceBounds);
        mTmpDestinationRectF.set(menuDestBounds);
        mMoveTransform.setRectToRect(mTmpSourceRectF, mTmpDestinationRectF, Matrix.ScaleToFit.FILL);

        SurfaceControl surfaceControl = getSurfaceControl();
        SyncRtSurfaceTransactionApplier.SurfaceParams params =
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                        surfaceControl)
                        .withMatrix(mMoveTransform)
                        .build();

        if (pipLeash != null && transaction != null) {
            SyncRtSurfaceTransactionApplier.SurfaceParams
                    pipParams = new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(transaction)
                    .build();
            mApplier.scheduleApply(params, pipParams);
        } else {
            mApplier.scheduleApply(params);
        }

        if (mPipMenuView.getViewRootImpl() != null) {
            mPipMenuView.getHandler().removeCallbacks(mUpdateEmbeddedMatrix);
            mPipMenuView.getHandler().post(mUpdateEmbeddedMatrix);
        }

        updateMenuBounds(pipDestBounds);
    }

    private boolean maybeCreateSyncApplier() {
        if (mPipMenuView == null || mPipMenuView.getViewRootImpl() == null) {
            Log.v(TAG, "Not going to move PiP, either menu or its parent is not created.");
            return false;
        }

        if (mApplier == null) {
            mApplier = new SyncRtSurfaceTransactionApplier(mPipMenuView);
        }
        return true;
    }

    private void detachPipMenuView() {
        if (mPipMenuView == null) {
            return;
        }

        mApplier = null;
        mSystemWindows.removeView(mPipMenuView);
        mPipMenuView = null;
    }

    @Override
    public void updateMenuBounds(Rect destinationBounds) {
        Rect menuBounds = getMenuBounds(destinationBounds);
        if (DEBUG) Log.d(TAG, "updateMenuBounds: " + menuBounds.toShortString());
        mSystemWindows.updateViewLayout(mPipMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        if (mPipMenuView != null) {
            mPipMenuView.updateLayout(destinationBounds);
        }
    }

    @Override
    public void onFocusTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        Log.d(TAG, "onFocusTaskChanged");
    }

    @Override
    public void onBackPress() {
        if (!onExitMoveMode()) {
            hideMenu();
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

        void closeMenu();

        void closePip();
    }

    private void grantPipMenuFocus(boolean grantFocus) {
        if (DEBUG) Log.d(TAG, "grantWindowFocus(" + grantFocus + ")");

        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    mSystemWindows.getFocusGrantToken(mPipMenuView), grantFocus);
        } catch (Exception e) {
            Log.e(TAG, "Unable to update focus", e);
        }
    }
}
