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

package com.android.wm.shell.pip.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowManager.SHELL_ROOT_LAYER_PIP;

import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.RemoteAction;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.WindowManagerGlobal;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipMediaController.ActionListener;
import com.android.wm.shell.pip.PipMenuController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the PiP menu view which can show menu options or a scrim.
 *
 * The current media session provides actions whenever there are no valid actions provided by the
 * current PiP activity. Otherwise, those actions always take precedence.
 */
public class PhonePipMenuController implements PipMenuController {

    private static final String TAG = "PipMenuActController";
    private static final boolean DEBUG = false;

    public static final int MENU_STATE_NONE = 0;
    public static final int MENU_STATE_CLOSE = 1;
    public static final int MENU_STATE_FULL = 2;

    /**
     * A listener interface to receive notification on changes in PIP.
     */
    public interface Listener {
        /**
         * Called when the PIP menu visibility changes.
         *
         * @param menuState the current state of the menu
         * @param resize whether or not to resize the PiP with the state change
         */
        void onPipMenuStateChanged(int menuState, boolean resize, Runnable callback);

        /**
         * Called when the PIP requested to be expanded.
         */
        void onPipExpand();

        /**
         * Called when the PIP requested to be dismissed.
         */
        void onPipDismiss();

        /**
         * Called when the PIP requested to show the menu.
         */
        void onPipShowMenu();
    }

    private final Matrix mMoveTransform = new Matrix();
    private final Rect mTmpSourceBounds = new Rect();
    private final RectF mTmpSourceRectF = new RectF();
    private final RectF mTmpDestinationRectF = new RectF();
    private final Context mContext;
    private final PipMediaController mMediaController;
    private final ShellExecutor mMainExecutor;
    private final Handler mMainHandler;

    private final ArrayList<Listener> mListeners = new ArrayList<>();
    private final SystemWindows mSystemWindows;
    private ParceledListSlice<RemoteAction> mAppActions;
    private ParceledListSlice<RemoteAction> mMediaActions;
    private SyncRtSurfaceTransactionApplier mApplier;
    private int mMenuState;

    private PipMenuView mPipMenuView;
    private IBinder mPipMenuInputToken;

    private ActionListener mMediaActionListener = new ActionListener() {
        @Override
        public void onMediaActionsChanged(List<RemoteAction> mediaActions) {
            mMediaActions = new ParceledListSlice<>(mediaActions);
            updateMenuActions();
        }
    };

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
        }
    };

    public PhonePipMenuController(Context context, PipMediaController mediaController,
            SystemWindows systemWindows, ShellExecutor mainExecutor,
            Handler mainHandler) {
        mContext = context;
        mMediaController = mediaController;
        mSystemWindows = systemWindows;
        mMainExecutor = mainExecutor;
        mMainHandler = mainHandler;
    }

    public boolean isMenuVisible() {
        return mPipMenuView != null && mMenuState != MENU_STATE_NONE;
    }

    /**
     * Attach the menu when the PiP task first appears.
     */
    @Override
    public void attach(SurfaceControl leash) {
        attachPipMenuView();
    }

    /**
     * Detach the menu when the PiP task is gone.
     */
    @Override
    public void detach() {
        hideMenu();
        detachPipMenuView();
    }


    void onPinnedStackAnimationEnded() {
        if (isMenuVisible()) {
            mPipMenuView.onPipAnimationEnded();
        }
    }

    private void attachPipMenuView() {
        // In case detach was not called (e.g. PIP unexpectedly closed)
        if (mPipMenuView != null) {
            detachPipMenuView();
        }
        mPipMenuView = new PipMenuView(mContext, this, mMainExecutor, mMainHandler);
        mSystemWindows.addView(mPipMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, 0 /* width */, 0 /* height */),
                0, SHELL_ROOT_LAYER_PIP);
    }

    private void detachPipMenuView() {
        if (mPipMenuView == null) {
            return;
        }

        mApplier = null;
        mSystemWindows.removeView(mPipMenuView);
        mPipMenuView = null;
        mPipMenuInputToken = null;
    }

    /**
     * Updates the layout parameters of the menu.
     * @param destinationBounds New Menu bounds.
     */
    @Override
    public void updateMenuBounds(Rect destinationBounds) {
        mSystemWindows.updateViewLayout(mPipMenuView,
                getPipMenuLayoutParams(MENU_WINDOW_TITLE, destinationBounds.width(),
                        destinationBounds.height()));
    }

    /**
     * Tries to grab a surface control from {@link PipMenuView}. If this isn't available for some
     * reason (ie. the window isn't ready yet, thus {@link android.view.ViewRootImpl} is
     * {@code null}), it will get the leash that the WindowlessWM has assigned to it.
     */
    public SurfaceControl getSurfaceControl() {
        return mSystemWindows.getViewSurface(mPipMenuView);
    }

    /**
     * Adds a new menu activity listener.
     */
    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    @Nullable
    Size getEstimatedMinMenuSize() {
        return mPipMenuView == null ? null : mPipMenuView.getEstimatedMinMenuSize();
    }

    /**
     * When other components requests the menu controller directly to show the menu, we must
     * first fire off the request to the other listeners who will then propagate the call
     * back to the controller with the right parameters.
     */
    @Override
    public void showMenu() {
        mListeners.forEach(Listener::onPipShowMenu);
    }

    /**
     * Similar to {@link #showMenu(int, Rect, boolean, boolean, boolean)} but only show the menu
     * upon PiP window transition is finished.
     */
    public void showMenuWithPossibleDelay(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean showResizeHandle) {
        if (willResizeMenu) {
            // hide all visible controls including close button and etc. first, this is to ensure
            // menu is totally invisible during the transition to eliminate unpleasant artifacts
            fadeOutMenu();
        }
        showMenuInternal(menuState, stackBounds, allowMenuTimeout, willResizeMenu,
                willResizeMenu /* withDelay=willResizeMenu here */, showResizeHandle);
    }

    /**
     * Shows the menu activity immediately.
     */
    public void showMenu(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean showResizeHandle) {
        showMenuInternal(menuState, stackBounds, allowMenuTimeout, willResizeMenu,
                false /* withDelay */, showResizeHandle);
    }

    private void showMenuInternal(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean willResizeMenu, boolean withDelay, boolean showResizeHandle) {
        if (DEBUG) {
            Log.d(TAG, "showMenu() state=" + menuState
                    + " isMenuVisible=" + isMenuVisible()
                    + " allowMenuTimeout=" + allowMenuTimeout
                    + " willResizeMenu=" + willResizeMenu
                    + " withDelay=" + withDelay
                    + " showResizeHandle=" + showResizeHandle
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }

        if (!maybeCreateSyncApplier()) {
            return;
        }

        mPipMenuView.showMenu(menuState, stackBounds, allowMenuTimeout, willResizeMenu, withDelay,
                showResizeHandle);
    }

    /**
     * Move the PiP menu, which does a translation and possibly a scale transformation.
     */
    @Override
    public void movePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {
        if (destinationBounds.isEmpty()) {
            return;
        }

        if (!maybeCreateSyncApplier()) {
            return;
        }

        // If there is no pip leash supplied, that means the PiP leash is already finalized
        // resizing and the PiP menu is also resized. We then want to do a scale from the current
        // new menu bounds.
        if (pipLeash != null && t != null) {
            mPipMenuView.getBoundsOnScreen(mTmpSourceBounds);
        } else {
            mTmpSourceBounds.set(0, 0, destinationBounds.width(), destinationBounds.height());
        }

        mTmpSourceRectF.set(mTmpSourceBounds);
        mTmpDestinationRectF.set(destinationBounds);
        mMoveTransform.setRectToRect(mTmpSourceRectF, mTmpDestinationRectF, Matrix.ScaleToFit.FILL);
        SurfaceControl surfaceControl = getSurfaceControl();
        SurfaceParams params = new SurfaceParams.Builder(surfaceControl)
                .withMatrix(mMoveTransform)
                .build();
        if (pipLeash != null && t != null) {
            SurfaceParams pipParams = new SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(t)
                    .build();
            mApplier.scheduleApply(params, pipParams);
        } else {
            mApplier.scheduleApply(params);
        }

        if (mPipMenuView.getViewRootImpl() != null) {
            mPipMenuView.getHandler().removeCallbacks(mUpdateEmbeddedMatrix);
            mPipMenuView.getHandler().post(mUpdateEmbeddedMatrix);
        }
    }

    /**
     * Does an immediate window crop of the PiP menu.
     */
    @Override
    public void resizePipMenu(@Nullable SurfaceControl pipLeash,
            @Nullable SurfaceControl.Transaction t,
            Rect destinationBounds) {
        if (destinationBounds.isEmpty()) {
            return;
        }

        if (!maybeCreateSyncApplier()) {
            return;
        }

        SurfaceControl surfaceControl = getSurfaceControl();
        SurfaceParams params = new SurfaceParams.Builder(surfaceControl)
                .withWindowCrop(destinationBounds)
                .build();
        if (pipLeash != null && t != null) {
            SurfaceParams pipParams = new SurfaceParams.Builder(pipLeash)
                    .withMergeTransaction(t)
                    .build();
            mApplier.scheduleApply(params, pipParams);
        } else {
            mApplier.scheduleApply(params);
        }
    }

    private boolean maybeCreateSyncApplier() {
        if (mPipMenuView == null || mPipMenuView.getViewRootImpl() == null) {
            Log.v(TAG, "Not going to move PiP, either menu or its parent is not created.");
            return false;
        }

        if (mApplier == null) {
            mApplier = new SyncRtSurfaceTransactionApplier(mPipMenuView);
            mPipMenuInputToken = mPipMenuView.getViewRootImpl().getInputToken();
        }

        return mApplier != null;
    }

    /**
     * Pokes the menu, indicating that the user is interacting with it.
     */
    public void pokeMenu() {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "pokeMenu() isMenuVisible=" + isMenuVisible);
        }
        if (isMenuVisible) {
            mPipMenuView.pokeMenu();
        }
    }

    private void fadeOutMenu() {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "fadeOutMenu() isMenuVisible=" + isMenuVisible);
        }
        if (isMenuVisible) {
            mPipMenuView.fadeOutMenu();
        }
    }

    /**
     * Hides the menu view.
     */
    public void hideMenu() {
        hideMenu(true /* animate */, true /* resize */);
    }

    /**
     * Hides the menu view.
     *
     * @param animate whether to animate the menu fadeout
     * @param resize whether or not to resize the PiP with the state change
     */
    public void hideMenu(boolean animate, boolean resize) {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "hideMenu() state=" + mMenuState
                    + " isMenuVisible=" + isMenuVisible
                    + " animate=" + animate
                    + " resize=" + resize
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (isMenuVisible) {
            mPipMenuView.hideMenu(animate, resize);
        }
    }

    /**
     * Hides the menu activity.
     */
    public void hideMenu(Runnable onStartCallback, Runnable onEndCallback) {
        if (isMenuVisible()) {
            // If the menu is visible in either the closed or full state, then hide the menu and
            // trigger the animation trigger afterwards
            if (onStartCallback != null) {
                onStartCallback.run();
            }
            mPipMenuView.hideMenu(onEndCallback);
        }
    }

    /**
     * Sets the menu actions to the actions provided by the current PiP menu.
     */
    @Override
    public void setAppActions(ParceledListSlice<RemoteAction> appActions) {
        mAppActions = appActions;
        updateMenuActions();
    }

    void onPipExpand() {
        mListeners.forEach(Listener::onPipExpand);
    }

    void onPipDismiss() {
        mListeners.forEach(Listener::onPipDismiss);
    }

    /**
     * @return the best set of actions to show in the PiP menu.
     */
    private ParceledListSlice<RemoteAction> resolveMenuActions() {
        if (isValidActions(mAppActions)) {
            return mAppActions;
        }
        return mMediaActions;
    }

    /**
     * Updates the PiP menu with the best set of actions provided.
     */
    private void updateMenuActions() {
        if (isMenuVisible()) {
            // Fetch the pinned stack bounds
            Rect stackBounds = null;
            try {
                RootTaskInfo pinnedTaskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                        WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
                if (pinnedTaskInfo != null) {
                    stackBounds = pinnedTaskInfo.bounds;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error showing PIP menu", e);
            }

            mPipMenuView.setActions(stackBounds, resolveMenuActions().getList());
        }
    }

    /**
     * Returns whether the set of actions are valid.
     */
    private static boolean isValidActions(ParceledListSlice<?> actions) {
        return actions != null && actions.getList().size() > 0;
    }

    /**
     * Handles changes in menu visibility.
     */
    void onMenuStateChanged(int menuState, boolean resize, Runnable callback) {
        if (DEBUG) {
            Log.d(TAG, "onMenuStateChanged() mMenuState=" + mMenuState
                    + " menuState=" + menuState + " resize=" + resize
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }

        if (menuState != mMenuState) {
            mListeners.forEach(l -> l.onPipMenuStateChanged(menuState, resize, callback));
            if (menuState == MENU_STATE_FULL) {
                // Once visible, start listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.addActionListener(mMediaActionListener);
            } else {
                // Once hidden, stop listening for media action changes. This call will trigger
                // the menu actions to be updated again.
                mMediaController.removeActionListener(mMediaActionListener);
            }

            try {
                WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                        mPipMenuInputToken, menuState != MENU_STATE_NONE /* grantFocus */);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update focus as menu appears/disappears", e);
            }
        }
        mMenuState = menuState;
    }

    /**
     * Handles a pointer event sent from pip input consumer.
     */
    void handlePointerEvent(MotionEvent ev) {
        if (ev.isTouchEvent()) {
            mPipMenuView.dispatchTouchEvent(ev);
        } else {
            mPipMenuView.dispatchGenericMotionEvent(ev);
        }
    }

    /**
     * Tell the PIP Menu to recalculate its layout given its current position on the display.
     */
    public void updateMenuLayout(Rect bounds) {
        final boolean isMenuVisible = isMenuVisible();
        if (DEBUG) {
            Log.d(TAG, "updateMenuLayout() state=" + mMenuState
                    + " isMenuVisible=" + isMenuVisible
                    + " callers=\n" + Debug.getCallers(5, "    "));
        }
        if (isMenuVisible) {
            mPipMenuView.updateMenuLayout(bounds);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mMenuState=" + mMenuState);
        pw.println(innerPrefix + "mPipMenuView=" + mPipMenuView);
        pw.println(innerPrefix + "mListeners=" + mListeners.size());
    }
}
