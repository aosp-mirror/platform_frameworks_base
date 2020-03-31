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
 * limitations under the License.
 */

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;

import com.android.server.UiThread;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the common state of the pinned stack between the system and SystemUI. If SystemUI ever
 * needs to be restarted, it will be notified with the last known state.
 *
 * Changes to the pinned stack also flow through this controller, and generally, the system only
 * changes the pinned stack bounds through this controller in two ways:
 *
 * 1) When first entering PiP: the controller returns the valid bounds given, taking aspect ratio
 *    and IME state into account.
 * 2) When rotating the device: the controller calculates the new bounds in the new orientation,
 *    taking the IME state into account. In this case, we currently ignore the
 *    SystemUI adjustments (ie. expanded for menu, interaction, etc).
 *
 * Other changes in the system, including adjustment of IME, configuration change, and more are
 * handled by SystemUI (similar to the docked stack divider).
 */
class PinnedStackController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "PinnedStackController" : TAG_WM;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Handler mHandler = UiThread.getHandler();

    private IPinnedStackListener mPinnedStackListener;
    private final PinnedStackListenerDeathHandler mPinnedStackListenerDeathHandler =
            new PinnedStackListenerDeathHandler();

    private final PinnedStackControllerCallback mCallbacks = new PinnedStackControllerCallback();

    private boolean mIsImeShowing;
    private int mImeHeight;

    // The set of actions and aspect-ratio for the that are currently allowed on the PiP activity
    private ArrayList<RemoteAction> mActions = new ArrayList<>();
    private float mAspectRatio = -1f;

    // Used to calculate stack bounds across rotations
    private final DisplayInfo mDisplayInfo = new DisplayInfo();

    // The aspect ratio bounds of the PIP.
    private float mMinAspectRatio;
    private float mMaxAspectRatio;

    // Temp vars for calculation
    private final DisplayMetrics mTmpMetrics = new DisplayMetrics();

    /**
     * The callback object passed to listeners for them to notify the controller of state changes.
     */
    private class PinnedStackControllerCallback extends IPinnedStackController.Stub {
        @Override
        public int getDisplayRotation() {
            synchronized (mService.mGlobalLock) {
                return mDisplayInfo.rotation;
            }
        }
    }

    /**
     * Handler for the case where the listener dies.
     */
    private class PinnedStackListenerDeathHandler implements IBinder.DeathRecipient {

        @Override
        public void binderDied() {
            // Clean up the state if the listener dies
            if (mPinnedStackListener != null) {
                mPinnedStackListener.asBinder().unlinkToDeath(mPinnedStackListenerDeathHandler, 0);
            }
            mPinnedStackListener = null;
        }
    }

    PinnedStackController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mDisplayInfo.copyFrom(mDisplayContent.getDisplayInfo());
        reloadResources();
    }

    void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    private void reloadResources() {
        final Resources res = mService.mContext.getResources();
        mDisplayContent.getDisplay().getRealMetrics(mTmpMetrics);
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
    }

    /**
     * Registers a pinned stack listener.
     */
    void registerPinnedStackListener(IPinnedStackListener listener) {
        try {
            listener.asBinder().linkToDeath(mPinnedStackListenerDeathHandler, 0);
            listener.onListenerRegistered(mCallbacks);
            mPinnedStackListener = listener;
            notifyDisplayInfoChanged(mDisplayInfo);
            notifyImeVisibilityChanged(mIsImeShowing, mImeHeight);
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
            notifyActionsChanged(mActions);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    /**
     * @return whether the given {@param aspectRatio} is valid.
     */
    public boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) <= 0 &&
                Float.compare(aspectRatio, mMaxAspectRatio) <= 0;
    }

    /**
     * Activity is hidden (either stopped or removed), resets the last saved snap fraction
     * so that the default bounds will be returned for the next session.
     */
    void onActivityHidden(ComponentName componentName) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onActivityHidden(componentName);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering reset reentry fraction event.", e);
        }
    }

    private void setDisplayInfo(DisplayInfo displayInfo) {
        mDisplayInfo.copyFrom(displayInfo);
        notifyDisplayInfoChanged(mDisplayInfo);
    }

    /**
     * In the case where the display rotation is changed but there is no stack, we can't depend on
     * onTaskStackBoundsChanged() to be called.  But we still should update our known display info
     * with the new state so that we can update SystemUI.
     */
    void onDisplayInfoChanged(DisplayInfo displayInfo) {
        synchronized (mService.mGlobalLock) {
            setDisplayInfo(displayInfo);
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
        }
    }

    /**
     * Sets the Ime state and height.
     */
    void setAdjustedForIme(boolean adjustedForIme, int imeHeight) {
        // Due to the order of callbacks from the system, we may receive an ime height even when
        // {@param adjustedForIme} is false, and also a zero height when {@param adjustedForIme}
        // is true.  Instead, ensure that the ime state changes with the height and if the ime is
        // showing, then the height is non-zero.
        final boolean imeShowing = adjustedForIme && imeHeight > 0;
        imeHeight = imeShowing ? imeHeight : 0;
        if (imeShowing == mIsImeShowing && imeHeight == mImeHeight) {
            return;
        }

        mIsImeShowing = imeShowing;
        mImeHeight = imeHeight;
        notifyImeVisibilityChanged(imeShowing, imeHeight);
        notifyMovementBoundsChanged(true /* fromImeAdjustment */);
    }

    /**
     * Sets the current aspect ratio.
     */
    void setAspectRatio(float aspectRatio) {
        if (Float.compare(mAspectRatio, aspectRatio) != 0) {
            mAspectRatio = aspectRatio;
            notifyAspectRatioChanged(aspectRatio);
            notifyMovementBoundsChanged(false /* fromImeAdjustment */);
        }
    }

    /**
     * @return the current aspect ratio.
     */
    float getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * Sets the current set of actions.
     */
    void setActions(List<RemoteAction> actions) {
        mActions.clear();
        if (actions != null) {
            mActions.addAll(actions);
        }
        notifyActionsChanged(mActions);
    }

    /**
     * Notifies listeners that the PIP needs to be adjusted for the IME.
     */
    private void notifyImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onImeVisibilityChanged(imeVisible, imeHeight);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering bounds changed event.", e);
            }
        }
    }

    private void notifyAspectRatioChanged(float aspectRatio) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onAspectRatioChanged(aspectRatio);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering aspect ratio changed event.", e);
        }
    }

    /**
     * Notifies listeners that the PIP actions have changed.
     */
    private void notifyActionsChanged(List<RemoteAction> actions) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onActionsChanged(new ParceledListSlice(actions));
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP movement bounds have changed.
     */
    private void notifyMovementBoundsChanged(boolean fromImeAdjustment) {
        synchronized (mService.mGlobalLock) {
            if (mPinnedStackListener == null) {
                return;
            }
            try {
                final Rect animatingBounds = new Rect();
                final ActivityStack pinnedStack = mDisplayContent.getRootPinnedTask();
                if (pinnedStack != null) {
                    pinnedStack.getAnimationOrCurrentBounds(animatingBounds);
                }
                mPinnedStackListener.onMovementBoundsChanged(animatingBounds, fromImeAdjustment);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering actions changed event.", e);
            }
        }
    }

    /**
     * Notifies listeners that the PIP animation is about to happen.
     */
    private void notifyDisplayInfoChanged(DisplayInfo displayInfo) {
        if (mPinnedStackListener == null) return;
        try {
            mPinnedStackListener.onDisplayInfoChanged(displayInfo);
        } catch (RemoteException e) {
            Slog.e(TAG_WM, "Error delivering DisplayInfo changed event.", e);
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedStackController");
        pw.println(prefix + "  mIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "  mImeHeight=" + mImeHeight);
        pw.println(prefix + "  mAspectRatio=" + mAspectRatio);
        pw.println(prefix + "  mMinAspectRatio=" + mMinAspectRatio);
        pw.println(prefix + "  mMaxAspectRatio=" + mMaxAspectRatio);
        if (mActions.isEmpty()) {
            pw.println(prefix + "  mActions=[]");
        } else {
            pw.println(prefix + "  mActions=[");
            for (int i = 0; i < mActions.size(); i++) {
                RemoteAction action = mActions.get(i);
                pw.print(prefix + "    Action[" + i + "]: ");
                action.dump("", pw);
            }
            pw.println(prefix + "  ]");
        }
        pw.println(prefix + "  mDisplayInfo=" + mDisplayInfo);
    }
}
