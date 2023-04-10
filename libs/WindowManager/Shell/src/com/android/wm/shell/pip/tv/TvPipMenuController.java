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

import android.annotation.IntDef;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.window.SurfaceSyncGroup;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipMenuController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.List;

/**
 * Manages the visibility of the PiP Menu as user interacts with PiP.
 */
public class TvPipMenuController implements PipMenuController, TvPipMenuView.Listener {
    private static final String TAG = "TvPipMenuController";
    private static final String BACKGROUND_WINDOW_TITLE = "PipBackgroundView";

    private final Context mContext;
    private final SystemWindows mSystemWindows;
    private final TvPipBoundsState mTvPipBoundsState;
    private final Handler mMainHandler;
    private TvPipActionsProvider mTvPipActionsProvider;

    private Delegate mDelegate;
    private SurfaceControl mLeash;
    private TvPipMenuView mPipMenuView;
    private TvPipBackgroundView mPipBackgroundView;
    private boolean mMenuIsFocused;

    @TvPipMenuMode
    private int mCurrentMenuMode = MODE_NO_MENU;
    @TvPipMenuMode
    private int mPrevMenuMode = MODE_NO_MENU;

    @IntDef(prefix = { "MODE_" }, value = {
        MODE_NO_MENU,
        MODE_MOVE_MENU,
        MODE_ALL_ACTIONS_MENU,
    })
    public @interface TvPipMenuMode {}

    /**
     * In this mode the PiP menu is not focused and no user controls are displayed.
     */
    static final int MODE_NO_MENU = 0;

    /**
     * In this mode the PiP menu is focused and the user can use the DPAD controls to move the PiP
     * to a different position on the screen. We draw arrows in all possible movement directions.
     */
    static final int MODE_MOVE_MENU = 1;

    /**
     * In this mode the PiP menu is focused and we display an array of actions that the user can
     * select. See {@link TvPipActionsProvider} for the types of available actions.
     */
    static final int MODE_ALL_ACTIONS_MENU = 2;

    public TvPipMenuController(Context context, TvPipBoundsState tvPipBoundsState,
            SystemWindows systemWindows, Handler mainHandler) {
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
    }

    void setDelegate(Delegate delegate) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: setDelegate(), delegate=%s", TAG, delegate);
        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    void setTvPipActionsProvider(TvPipActionsProvider tvPipActionsProvider) {
        mTvPipActionsProvider = tvPipActionsProvider;
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
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: attachPipMenu()", TAG);

        if (mPipMenuView != null) {
            detachPipMenu();
        }

        attachPipBackgroundView();
        attachPipMenuView();

        int pipEduTextHeight = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_edu_text_view_height);
        int pipMenuBorderWidth = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_border_width);
        mTvPipBoundsState.setPipMenuPermanentDecorInsets(Insets.of(-pipMenuBorderWidth,
                -pipMenuBorderWidth, -pipMenuBorderWidth, -pipMenuBorderWidth));
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.of(0, 0, 0, -pipEduTextHeight));
    }

    private void attachPipMenuView() {
        if (mTvPipActionsProvider == null) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Actions provider is not set", TAG);
            return;
        }
        mPipMenuView = createTvPipMenuView();
        setUpViewSurfaceZOrder(mPipMenuView, 1);
        addPipMenuViewToSystemWindows(mPipMenuView, MENU_WINDOW_TITLE);
    }

    @VisibleForTesting
    TvPipMenuView createTvPipMenuView() {
        return new TvPipMenuView(mContext, mMainHandler, this, mTvPipActionsProvider);
    }

    private void attachPipBackgroundView() {
        mPipBackgroundView = createTvPipBackgroundView();
        setUpViewSurfaceZOrder(mPipBackgroundView, -1);
        addPipMenuViewToSystemWindows(mPipBackgroundView, BACKGROUND_WINDOW_TITLE);
    }

    @VisibleForTesting
    TvPipBackgroundView createTvPipBackgroundView() {
        return new TvPipBackgroundView(mContext);
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
        mSystemWindows.addView(v, getPipMenuLayoutParams(mContext, title, 0 /* width */,
                0 /* height */), 0 /* displayId */, SHELL_ROOT_LAYER_PIP);
    }

    void onPipTransitionFinished(boolean enterTransition) {
        // There is a race between when this is called and when the last frame of the pip transition
        // is drawn. To ensure that view updates are applied only when the animation has fully drawn
        // and the menu view has been fully remeasured and relaid out, we add a small delay here by
        // posting on the handler.
        mMainHandler.post(() -> {
            if (mPipMenuView != null) {
                mPipMenuView.onPipTransitionFinished(enterTransition);
            }
        });
    }

    void showMovementMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: showMovementMenu()", TAG);
        switchToMenuMode(MODE_MOVE_MENU);
    }

    @Override
    public void showMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showMenu()", TAG);
        switchToMenuMode(MODE_ALL_ACTIONS_MENU, true);
    }

    void onPipTransitionToTargetBoundsStarted(Rect targetBounds) {
        if (mPipMenuView != null) {
            mPipMenuView.onPipTransitionToTargetBoundsStarted(targetBounds);
        }
    }

    void updateGravity(int gravity) {
        mPipMenuView.setPipGravity(gravity);
    }

    private Rect calculateMenuSurfaceBounds(Rect pipBounds) {
        return mPipMenuView.getPipMenuContainerBounds(pipBounds);
    }

    void closeMenu() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: closeMenu()", TAG);
        switchToMenuMode(MODE_NO_MENU);
    }

    @Override
    public void detach() {
        closeMenu();
        detachPipMenu();
        mLeash = null;
    }

    @Override
    public void setAppActions(List<RemoteAction> actions, RemoteAction closeAction) {
        // NOOP - handled via the TvPipActionsProvider
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
            @Nullable SurfaceControl.Transaction pipTx,
            Rect pipBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: resizePipMenu: %s", TAG, pipBounds.toShortString());

        if (pipBounds.isEmpty()) {
            return;
        }
        if (!isMenuAttached()) {
            return;
        }

        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final Rect menuBounds = calculateMenuSurfaceBounds(pipBounds);
        if (pipTx == null) {
            pipTx = new SurfaceControl.Transaction();
        }
        pipTx.setWindowCrop(frontSurface, menuBounds.width(), menuBounds.height());
        pipTx.setWindowCrop(backSurface, menuBounds.width(), menuBounds.height());

        // Synchronize drawing the content in the front and back surfaces together with the pip
        // transaction and the window crop for the front and back surfaces
        final SurfaceSyncGroup syncGroup = new SurfaceSyncGroup("TvPip");
        syncGroup.add(mPipMenuView.getRootSurfaceControl(), null);
        syncGroup.add(mPipBackgroundView.getRootSurfaceControl(), null);
        updateMenuBounds(pipBounds);
        syncGroup.addTransaction(pipTx);
        syncGroup.markSyncReady();
    }

    private SurfaceControl getSurfaceControl(View v) {
        return mSystemWindows.getViewSurface(v);
    }

    @Override
    public void movePipMenu(SurfaceControl pipLeash, SurfaceControl.Transaction pipTx,
            Rect pipBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: movePipMenu: %s", TAG, pipBounds.toShortString());

        if (pipBounds.isEmpty()) {
            if (pipTx == null) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: no transaction given", TAG);
            }
            return;
        }
        if (!isMenuAttached()) {
            return;
        }

        final SurfaceControl frontSurface = getSurfaceControl(mPipMenuView);
        final SurfaceControl backSurface = getSurfaceControl(mPipBackgroundView);
        final Rect menuDestBounds = calculateMenuSurfaceBounds(pipBounds);
        if (pipTx == null) {
            pipTx = new SurfaceControl.Transaction();
        }
        pipTx.setPosition(frontSurface, menuDestBounds.left, menuDestBounds.top);
        pipTx.setPosition(backSurface, menuDestBounds.left, menuDestBounds.top);

        // Synchronize drawing the content in the front and back surfaces together with the pip
        // transaction and the position change for the front and back surfaces
        final SurfaceSyncGroup syncGroup = new SurfaceSyncGroup("TvPip");
        syncGroup.add(mPipMenuView.getRootSurfaceControl(), null);
        syncGroup.add(mPipBackgroundView.getRootSurfaceControl(), null);
        updateMenuBounds(pipBounds);
        syncGroup.addTransaction(pipTx);
        syncGroup.markSyncReady();
    }

    private boolean isMenuAttached() {
        final boolean ready = mPipMenuView != null && mPipMenuView.getViewRootImpl() != null
                && mPipBackgroundView != null && mPipBackgroundView.getViewRootImpl() != null;
        if (!ready) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: the menu surfaces are not attached.", TAG);
        }
        return ready;
    }

    private void detachPipMenu() {
        if (mPipMenuView != null) {
            mSystemWindows.removeView(mPipMenuView);
            mPipMenuView = null;
        }

        if (mPipBackgroundView != null) {
            mSystemWindows.removeView(mPipBackgroundView);
            mPipBackgroundView = null;
        }
    }

    @Override
    public void updateMenuBounds(Rect pipBounds) {
        if (!isMenuAttached()) {
            return;
        }
        final Rect menuBounds = calculateMenuSurfaceBounds(pipBounds);
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: updateMenuBounds: %s", TAG, menuBounds.toShortString());
        mSystemWindows.updateViewLayout(mPipBackgroundView,
                getPipMenuLayoutParams(mContext, BACKGROUND_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        mSystemWindows.updateViewLayout(mPipMenuView,
                getPipMenuLayoutParams(mContext, MENU_WINDOW_TITLE, menuBounds.width(),
                        menuBounds.height()));
        if (mPipMenuView != null) {
            mPipMenuView.setPipBounds(pipBounds);
        }
    }

    // Start methods handling {@link TvPipMenuMode}

    @VisibleForTesting
    boolean isMenuOpen() {
        return mCurrentMenuMode != MODE_NO_MENU;
    }

    @VisibleForTesting
    boolean isInMoveMode() {
        return mCurrentMenuMode == MODE_MOVE_MENU;
    }

    @VisibleForTesting
    boolean isInAllActionsMode() {
        return mCurrentMenuMode == MODE_ALL_ACTIONS_MENU;
    }

    private void switchToMenuMode(@TvPipMenuMode int menuMode) {
        switchToMenuMode(menuMode, false);
    }

    private void switchToMenuMode(@TvPipMenuMode int menuMode, boolean resetMenu) {
        // Note: we intentionally don't return early here, because the TvPipMenuView needs to
        // refresh the Ui even if there is no menu mode change.
        mPrevMenuMode = mCurrentMenuMode;
        mCurrentMenuMode = menuMode;

        ProtoLog.i(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: switchToMenuMode: setting mCurrentMenuMode=%s, mPrevMenuMode=%s", TAG,
                getMenuModeString(), getMenuModeString(mPrevMenuMode));

        updateUiOnNewMenuModeRequest(resetMenu);
        updateDelegateOnNewMenuModeRequest();
    }

    private void updateUiOnNewMenuModeRequest(boolean resetMenu) {
        if (mPipMenuView == null || mPipBackgroundView == null) return;

        mPipMenuView.setPipGravity(mTvPipBoundsState.getTvPipGravity());
        mPipMenuView.transitionToMenuMode(mCurrentMenuMode, resetMenu);
        mPipBackgroundView.transitionToMenuMode(mCurrentMenuMode);
        grantPipMenuFocus(mCurrentMenuMode != MODE_NO_MENU);
    }

    private void updateDelegateOnNewMenuModeRequest() {
        if (mPrevMenuMode == mCurrentMenuMode) return;
        if (mDelegate == null) return;

        if (mPrevMenuMode == MODE_MOVE_MENU || isInMoveMode()) {
            mDelegate.onInMoveModeChanged();
        }

        if (mCurrentMenuMode == MODE_NO_MENU) {
            mDelegate.onMenuClosed();
        }
    }

    @VisibleForTesting
    String getMenuModeString() {
        return getMenuModeString(mCurrentMenuMode);
    }

    static String getMenuModeString(@TvPipMenuMode int menuMode) {
        switch(menuMode) {
            case MODE_NO_MENU:
                return "MODE_NO_MENU";
            case MODE_MOVE_MENU:
                return "MODE_MOVE_MENU";
            case MODE_ALL_ACTIONS_MENU:
                return "MODE_ALL_ACTIONS_MENU";
            default:
                return "Unknown";
        }
    }

    // Start {@link TvPipMenuView.Delegate} methods

    @Override
    public void onCloseEduText() {
        mTvPipBoundsState.setPipMenuTemporaryDecorInsets(Insets.NONE);
        mDelegate.closeEduText();
    }

    @Override
    public void onBackPress() {
        if (!onExitMoveMode()) {
            closeMenu();
        }
    }

    @Override
    public boolean onExitMoveMode() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onExitMoveMode - mCurrentMenuMode=%s", TAG, getMenuModeString());

        final int saveMenuMode = mCurrentMenuMode;
        if (isInMoveMode()) {
            switchToMenuMode(mPrevMenuMode);
        }
        return saveMenuMode == MODE_MOVE_MENU;
    }

    @Override
    public boolean onPipMovement(int keycode) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onPipMovement - mCurrentMenuMode=%s", TAG, getMenuModeString());
        if (isInMoveMode()) {
            mDelegate.movePip(keycode);
        }
        return isInMoveMode();
    }

    @Override
    public void onPipWindowFocusChanged(boolean focused) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onPipWindowFocusChanged - focused=%b", TAG, focused);
        mMenuIsFocused = focused;
        if (!focused && isMenuOpen()) {
            closeMenu();
        }
    }

    interface Delegate {
        void movePip(int keycode);

        void onInMoveModeChanged();

        void onMenuClosed();

        void closeEduText();
    }

    private void grantPipMenuFocus(boolean grantFocus) {
        if (mMenuIsFocused == grantFocus) return;

        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: grantWindowFocus(%b)", TAG, grantFocus);

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
