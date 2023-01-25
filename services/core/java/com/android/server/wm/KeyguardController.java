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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.KEYGUARD_STATE_AOD_SHOWN;
import static android.view.WindowManager.KEYGUARD_STATE_DREAMING;
import static android.view.WindowManager.KEYGUARD_STATE_GOING_AWAY;
import static android.view.WindowManager.KEYGUARD_STATE_KEYGUARD_TOP;
import static android.view.WindowManager.KEYGUARD_STATE_LOCKSCREEN_SHOWN;
import static android.view.WindowManager.KEYGUARD_STATE_OCCLUDED;
import static android.view.WindowManager.KEYGUARD_STATE_OFF;
import static android.view.WindowManager.KEYGUARD_STATE_ON;
import static android.view.WindowManager.KEYGUARD_STATE_ROOT;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TRANSIT_WAKE;
import static android.view.WindowManager.keyguardStateToString;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_OCCLUDES_KEYGUARD;


import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.KeyguardControllerProto.AOD_SHOWING;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_PER_DISPLAY;
import static com.android.server.wm.KeyguardControllerProto.KEYGUARD_SHOWING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.view.WindowManager.KeyguardState;
import android.window.TransitionInfo;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.StateMachine;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controls Keyguard occluding, dismissing and transitions depending on what kind of activities are
 * currently visible.
 * <p>
 * Note that everything in this class should only be accessed with the AM lock being held.
 */
class KeyguardController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "KeyguardController" : TAG_ATM;

    private static final boolean DEBUG = true;

    static final String KEYGUARD_SLEEP_TOKEN_TAG = "keyguard";

    private static final int DEFER_WAKE_TRANSITION_TIMEOUT_MS = 5000;

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mTaskSupervisor;
    private final ActivityTaskManagerInternal.SleepTokenAcquirer mSleepTokenAcquirer;
    private boolean mWaitingForWakeTransition;
    private WindowManagerService mWindowManager;

    private RootWindowContainer mRootWindowContainer;

    private final SparseArray<DisplayState> mDisplayStates = new SparseArray<>();

    @NonNull private final ServiceDelegate mServiceDelegate = new ServiceDelegate();

    KeyguardController(ActivityTaskManagerService service,
            ActivityTaskSupervisor taskSupervisor) {
        mService = service;
        mTaskSupervisor = taskSupervisor;
        mSleepTokenAcquirer = service.new SleepTokenAcquirerImpl(KEYGUARD_SLEEP_TOKEN_TAG);
    }

    void setWindowManager(WindowManagerService windowManager) {
        mWindowManager = windowManager;
        mRootWindowContainer = mService.mRootWindowContainer;
        mService.getTransitionController().registerLegacyListener(
                new WindowManagerInternal.AppTransitionListener() {
                    @Override
                    public int onAppTransitionStartingLocked(TransitionInfo info) {
                        final List<TransitionInfo.Change> changes = info.getChanges();
                        if (changes.size() == 0) {
                            Slog.e(TAG, "TransitionInfo doesn't contain change: " + info);
                            return 0;
                        }
                        final ActivityManager.RunningTaskInfo taskInfo =
                                changes.get(0).getTaskInfo();
                        if (taskInfo == null) {
                            Slog.e(TAG, "No RunningTaskInfo: " + info);
                            return 0;
                        }

                        // TODO(b/242856311): Filtering condition is defined here and in SysUI
                        // Keyguard service, which need to be in sync. For a long term, we should
                        // define a new API for notifying occlude status from WMS to SysUI, and
                        // the filtering logic should only exist in WM Shell.
                        if ((info.getFlags() & TRANSIT_FLAG_KEYGUARD_LOCKED) == 0) {
                            return 0;
                        }

                        boolean occludeOpeningApp = false;
                        boolean occludeClosingApp = false;
                        for (int i = 0; i < changes.size(); ++i) {
                            final TransitionInfo.Change change = changes.get(i);
                            if (change.hasFlags(FLAG_OCCLUDES_KEYGUARD)) {
                                if (change.getMode() == TRANSIT_OPEN
                                        || change.getMode() == TRANSIT_TO_FRONT) {
                                    occludeOpeningApp = true;
                                }
                                if (change.getMode() == TRANSIT_CLOSE
                                        || change.getMode() == TRANSIT_TO_BACK) {
                                    occludeClosingApp = true;
                                }
                            }
                        }
                        final DisplayState state = getDisplayState(taskInfo.displayId);
                        if (occludeOpeningApp && !occludeClosingApp) {
                            state.commitOccludedStatus(true /* occluded */);
                        } else if (!occludeOpeningApp && occludeClosingApp) {
                            state.commitOccludedStatus(false /* occluded */);
                        }
                        return 0;
                    }
                });
    }

    boolean isAodShowing(int displayId) {
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_AOD_SHOWN);
    }

    /**
     * @return {@code true} if either Keyguard or AOD are showing.
     */
    boolean isKeyguardOrAodShowing(int displayId) {
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_KEYGUARD_TOP);
    }

    /**
     * @return {@codd true} if lock screen is showing.
     */
    boolean isLocksScreenShowing(int displayId) {
        // NOTE: This is only used by WindowManagerService#notifyKeyguardTrustedChanged
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_LOCKSCREEN_SHOWN);
    }

    /**
     * @return {@code true} if Keyguard is either showing or occluded.
     */
    boolean isKeyguardLocked(int displayId) {
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_ON);
    }

    /**
     * @return true if the activity is controlling keyguard state.
     */
    boolean topActivityOccludesKeyguard(ActivityRecord r) {
        return getDisplayState(r.getDisplayId()).topActivityOccludesKeyguard(r);
    }

    /**
     * @return {@code true} if the keyguard is going away, {@code false} otherwise.
     */
    boolean isKeyguardGoingAway(int displayId) {
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_GOING_AWAY);
    }

    /**
     * Checks whether the top activity occludes the keyguard.
     */
    boolean isDisplayOccluded(int displayId) {
        return getDisplayState(displayId).isIn(KEYGUARD_STATE_OCCLUDED);
    }

    /**
     * @return Whether the dream activity is on top of default display.
     */
    boolean isShowingDream() {
        return getDisplayState(DEFAULT_DISPLAY).isIn(KEYGUARD_STATE_DREAMING);
    }

    /**
     * Checks whether {@param r} should be visible depending on Keyguard state.
     *
     * @return true if {@param r} is visible taken Keyguard state into account, false otherwise
     */
    boolean checkKeyguardVisibility(@NonNull ActivityRecord r) {
        if (r.mDisplayContent.canShowWithInsecureKeyguard() && canDismissKeyguard()) {
            return true;
        }
        return getDisplayState(r.mDisplayContent.getDisplayId()).checkKeyguardVisibility(r);
    }

    void onDisplayRemoved(int displayId) {
        final DisplayState state = mDisplayStates.get(displayId);
        if (state != null) {
            state.onRemoved();
            mDisplayStates.remove(displayId);
        }
    }

    private final Runnable mResetWaitTransition = () -> {
        synchronized (mWindowManager.mGlobalLock) {
            updateDeferTransitionForAod(false /* waiting */);
        }
    };

    /**
     * Update if app transition should be deferred until AOD state changes.
     *
     * <p>Note: This is used for defer app transition before the device fully wakes up, since during
     * wake up process, activities life cycle can be messed up due to a display sleep token.
     *
     * @param waiting {@code true} to defer an app transition, {@code false} to continue an app
     *        transition.
     */
    void updateDeferTransitionForAod(boolean waiting) {
        if (waiting == mWaitingForWakeTransition) {
            return;
        }
        if (!mService.getTransitionController().isCollecting()) {
            return;
        }
        // if AOD is showing, defer the wake transition until AOD state changed.
        if (waiting && isAodShowing(DEFAULT_DISPLAY)) {
            mWaitingForWakeTransition = true;
            mService.getTransitionController().deferTransitionReady();
            mWindowManager.mH.postDelayed(mResetWaitTransition, DEFER_WAKE_TRANSITION_TIMEOUT_MS);
        } else if (!waiting) {
            // dismiss the deferring if the AOD state change or cancel awake.
            mWaitingForWakeTransition = false;
            mService.getTransitionController().continueTransitionReady();
            mWindowManager.mH.removeCallbacks(mResetWaitTransition);
        }
    }

    /**
     * TODO(b/242851358): Remove this function once SysUI migrate to the new API.
     */
    @KeyguardState private static int convertToState(int displayId, boolean keyguardShowing,
            boolean aodShowing) {
        if (displayId == DEFAULT_DISPLAY) {
            if (aodShowing) {
                return KEYGUARD_STATE_AOD_SHOWN;
            } else if (keyguardShowing) {
                return KEYGUARD_STATE_LOCKSCREEN_SHOWN;
            } else {
                return KEYGUARD_STATE_OFF;
            }
        } else {
            if (keyguardShowing || aodShowing) {
                return KEYGUARD_STATE_LOCKSCREEN_SHOWN;
            } else {
                return KEYGUARD_STATE_OFF;
            }
        }
    }

    /**
     * Update the Keyguard showing state.
     *
     * @deprecated Use {@link #setKeyguardState(int, int)} instead. See b/242851358
     */
    @Deprecated
    void setKeyguardShown(int displayId, boolean keyguardShowing, boolean aodShowing) {
        final DisplayState state = getDisplayState(displayId);
        EventLogTags.writeWmSetKeyguardShown(
                displayId,
                keyguardShowing ? 1 : 0,
                aodShowing ? 1 : 0,
                state.isIn(KEYGUARD_STATE_GOING_AWAY) ? 1 : 0,
                "setKeyguardShown");
        setKeyguardState(displayId, convertToState(displayId, keyguardShowing, aodShowing));
    }

    /**
     * Set keyguard state.
     */
    private void setKeyguardState(int displayId, @KeyguardState int newState) {
        if (mRootWindowContainer.getDisplayContent(displayId).isKeyguardAlwaysUnlocked()) {
            Slog.i(TAG, "setKeyguardShown ignoring always unlocked display " + displayId);
            return;
        }
        if (newState != KEYGUARD_STATE_LOCKSCREEN_SHOWN
                && newState != KEYGUARD_STATE_AOD_SHOWN
                && newState != KEYGUARD_STATE_OFF
                && newState != KEYGUARD_STATE_GOING_AWAY) {
            Slog.i(TAG, "Invalid state is requested: displayId=" + displayId
                    + ", state=" + keyguardStateToString(newState)
                    + ", stack=" + Debug.getCallers(30));
            return;
        }
        if (isKeyguardLocked(displayId) && newState == KEYGUARD_STATE_OFF) {
            newState = KEYGUARD_STATE_GOING_AWAY;
        }

        final DisplayState state = getDisplayState(displayId);
        // SysUI requests to show LOCKSCREEN, but the keyguard is already occluded. Ignore the
        // requests.
        if (state.isIn(KEYGUARD_STATE_OCCLUDED)
                && StateMachine.isIn(newState, KEYGUARD_STATE_LOCKSCREEN_SHOWN)) {
            Slog.w(TAG, "Ignore setKeyguardState request: OCCLUDE -> LOCK_SCREEN_SHOWN");
            return;
        }
        // SysUI requests to show AOD_SHOWN again. This can happen when SysUI still uses the old
        // API and enables AOD first, then lock screen, i.e. #setLockScreenShown(false, true), then
        // #setLockScreenShown(true, true)
        if (state.isIn(KEYGUARD_STATE_AOD_SHOWN)
                && StateMachine.isIn(newState, KEYGUARD_STATE_AOD_SHOWN)) {
            Slog.w(TAG, "Ignore setKeyguardState request: AOD_SHOWN -> AOD_SHOWN");
            return;
        }
        if (state.isIn(KEYGUARD_STATE_OFF)
                && StateMachine.isIn(newState, KEYGUARD_STATE_GOING_AWAY)) {
            Slog.w(TAG, "Ignore setKeyguardState request: OFF -> GOING_AWAY");
            return;
        }
        if (state.isIn(KEYGUARD_STATE_AOD_SHOWN)
                && StateMachine.isIn(newState, KEYGUARD_STATE_LOCKSCREEN_SHOWN)) {
            ActivityRecord top = getTopNonFinishingActivity(displayId);
            if (canOcclude(top)) {
                newState = isTopActivityDreaming(displayId) ? KEYGUARD_STATE_DREAMING
                        : KEYGUARD_STATE_OCCLUDED;
            }
        }
        state.setKeyguardState(newState);
    }

    /**
     * Called when Keyguard is going away.
     *
     * @param flags See {@link WindowManagerPolicy#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     *
     * @deprecated Use {@link #setKeyguardState(int, int)}
     */
    void keyguardGoingAway(int displayId, int flags) {
        // TODO(b/242851358): Remove IActivityTaskManagerService#keyguardGoingAway and SysUI should
        // request the state change via #setKeyguardState.
        final DisplayState state = getDisplayState(displayId);
        EventLogTags.writeWmSetKeyguardShown(
                displayId,
                state.isIn(KEYGUARD_STATE_LOCKSCREEN_SHOWN) ? 1 : 0,
                state.isIn(KEYGUARD_STATE_AOD_SHOWN) ? 1 : 0,
                1 /* keyguardGoingAway */,
                "keyguardGoingAway");
        setKeyguardState(displayId, KEYGUARD_STATE_GOING_AWAY);
    }

    /**
     * Makes sure to update lockscreen state if needed before completing set all visibility
     * ({@link ActivityTaskSupervisor#beginActivityVisibilityUpdate}).
     */
    void updateVisibility() {
        for (int displayNdx = mRootWindowContainer.getChildCount() - 1;
                displayNdx >= 0; displayNdx--) {
            final DisplayContent display = mRootWindowContainer.getChildAt(displayNdx);
            if (display.isRemoving() || display.isRemoved()) continue;
            final DisplayState state = getDisplayState(display.mDisplayId);
            state.updateVisibility();
        }
    }

    void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback, CharSequence message) {
        boolean ok;
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            ok = false;
        } else {
            final DisplayState state = getDisplayState(r.getDisplayId());
            ok = state.dismissKeyguard(r, callback, message);
        }

        if (!ok) {
            try {
                callback.onDismissError();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call callback", e);
            }
        }
    }

    private boolean isKeyguardSecure() {
        return mWindowManager.isKeyguardSecure(mService.getCurrentUserId());
    }

    /**
     * @return true if Keyguard can be currently dismissed without entering credentials.
     */
    private boolean canDismissKeyguard() {
        return mWindowManager.mPolicy.isKeyguardTrustedLw() || !isKeyguardSecure();
    }

    private boolean canOcclude(@Nullable ActivityRecord r) {
        if (r == null) {
            return false;
        }
        // FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD only apply for secondary display.
        if (r.getDisplayId() != DEFAULT_DISPLAY) {
            final DisplayContent dc = r.getDisplayContent();
            if (dc != null && dc.canShowWithInsecureKeyguard() && canDismissKeyguard()) {
                return true;
            }
        }
        // FLAG_DISMISS_KEYGUARD activity
        //   Insecure: Treat as FLAG_SHOW_WHEN_LOCKED
        //   Trusted: Actually dismiss Keyguard.
        //   Secure: Show bouncer.
        return r.canShowWhenLocked() || (r.containsDismissKeyguardWindow() && !isKeyguardSecure());
    }

    private boolean isTopActivityDreaming(int displayId) {
        final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);
        final ActivityRecord top = getTopNonFinishingActivity(displayId);
        return dc.getDisplayPolicy().isShowingDreamLw()
                && top != null && top.getActivityType() == ACTIVITY_TYPE_DREAM;
    }

    @Nullable private ActivityRecord getTopNonFinishingActivity(int displayId) {
        final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);
        final Task rootTask = dc == null ? null : dc.getRootTask(t ->
                t != null && t.isFocusableAndVisible() && !t.inPinnedWindowingMode());
        return rootTask != null ? rootTask.getTopNonFinishingActivity() : null;
    }

    private DisplayState getDisplayState(int displayId) {
        DisplayState state = mDisplayStates.get(displayId);
        if (state == null) {
            state = new DisplayState(displayId, mServiceDelegate);
            mDisplayStates.append(displayId, state);
        }
        return state;
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final DisplayState default_state = getDisplayState(DEFAULT_DISPLAY);
        final long token = proto.start(fieldId);
        proto.write(AOD_SHOWING, default_state.isIn(KEYGUARD_STATE_AOD_SHOWN));
        proto.write(KEYGUARD_SHOWING, default_state.isIn(KEYGUARD_STATE_LOCKSCREEN_SHOWN));
        writeDisplayStatesToProto(proto, KEYGUARD_PER_DISPLAY);
        proto.end(token);
    }

    private void writeDisplayStatesToProto(ProtoOutputStream proto, long fieldId) {
        for (int i = 0; i < mDisplayStates.size(); i++) {
            mDisplayStates.valueAt(i).dumpDebug(proto, fieldId);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("KeyguardController:");
        for (int i = 0; i < mDisplayStates.size(); i++) {
            mDisplayStates.valueAt(i).dump(pw, prefix);
        }
        pw.println();
    }

    /**
     * Interface for {@link DisplayState} to access non-local information.
     * <p>
     * Keep this interface as small as possible, and don't let {@link DisplayState} access arbitrary
     * large classes such as ActivityTaskSupervisor, which makes managing dependency complicated.
     */
    private final class ServiceDelegate {
        boolean isKeyguardSecure() {
            return KeyguardController.this.isKeyguardSecure();
        }

        boolean canOcclude(@Nullable ActivityRecord r) {
            return KeyguardController.this.canOcclude(r);
        }

        boolean canDismissKeyguard() {
            return KeyguardController.this.canDismissKeyguard();
        }

        boolean isDeviceInteractive() {
            return mService.mWindowManager.mPowerManager.isInteractive();
        }

        void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) {
            mWindowManager.dismissKeyguard(callback, message);
        }

        @Nullable
        ActivityRecord getTopNonFinishingActivity(int displayId) {
            return KeyguardController.this.getTopNonFinishingActivity(displayId);
        }

        boolean isTopActivityDreaming(int displayId) {
            return KeyguardController.this.isTopActivityDreaming(displayId);
        }

        void wakeUp(String reason) {
            mTaskSupervisor.wakeUp(reason);
        }

        void forceSyncOccludedStatus(boolean occluded) {
            if (DEBUG) {
                Slog.d(TAG, "forceSyncOccludedStatus: occluded=" + occluded);
            }
            mWindowManager.mPolicy.onKeyguardOccludedChangedLw(occluded);
            mWindowManager.mPolicy.applyKeyguardOcclusionChange(true /* notify */);
        }

        void snapshotForSleeping(int displayId) {
            if (displayId == DEFAULT_DISPLAY) {
                mWindowManager.mTaskSnapshotController.snapshotForSleeping(displayId);
            }
        }

        void notifyKeyguardOccludeChanged(boolean occluded) {
            // TODO: This updates status of KeyguardDelegate. Once we delete occlude status from
            // KeyguardDelegate, we should remove WindowManagerPolicy#onKeyguardOccludedChangedLw.
            mWindowManager.mPolicy.onKeyguardOccludedChangedLw(occluded);
        }

        void collect(@NonNull WindowContainer wc) {
            mService.getTransitionController().collect(wc);
        }

        void requestTransitionIfNeeded(int displayId, @WindowManager.TransitionType int transit,
                @WindowManager.TransitionFlags int flags) {
            if (displayId != DEFAULT_DISPLAY) {
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "requestTransitionIfNeeded: display=" + displayId + ", transit="
                        + transitTypeToString(transit));
            }

            final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);
            if (dc == null) {
                Slog.e(TAG, "No DisplayContent exists: displayId=" + displayId);
                return;
            }

            if (transit == TRANSIT_KEYGUARD_GOING_AWAY) {
                dc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, flags);
                // We are deprecating TRANSIT_KEYGUARD_GOING_AWAY for Shell transition and use
                // TRANSIT_FLAG_KEYGUARD_GOING_AWAY to indicate that it should animate keyguard
                // going away.
                mService.getTransitionController().requestTransitionIfNeeded(
                        TRANSIT_TO_BACK, flags, null /* trigger */, dc);
            } else {
                dc.requestTransitionAndLegacyPrepare(transit, flags);
            }
        }

        void acquireSleepToken(int displayId, boolean ensureActivitiesVisible) {
            mSleepTokenAcquirer.acquire(displayId);
            if (ensureActivitiesVisible) {
                mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
            }
        }

        void releaseSleepToken(int displayId, boolean resumeTopActivities) {
            mSleepTokenAcquirer.release(displayId);
            if (resumeTopActivities) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
                mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
                mRootWindowContainer.addStartingWindowsForVisibleActivities();

            }
        }

        void deferWindowLayout() {
            mService.deferWindowLayout();
        }

        void continueWindowLayout() {
            mService.continueWindowLayout();
        }

        void executeAppTransition() {
            mWindowManager.executeAppTransition();
        }

        private void updateDeferTransitionForAod(boolean waiting) {
            KeyguardController.this.updateDeferTransitionForAod(waiting);
        }

        private void setWakeTransitionReady() {
            if (mService.getTransitionController().getCollectingTransitionType() == TRANSIT_WAKE) {
                mService.getTransitionController().setReady(
                        mRootWindowContainer.getDefaultDisplay());
            }
        }

        void requestLayoutRedoWallpaper(int displayId) {
            final DisplayContent dc = mRootWindowContainer.getDisplayContent(displayId);
            if (!dc.isSleeping() && dc.mWallpaperController.getWallpaperTarget() != null) {
                dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            }
        }
    };

    private static class KeyguardDisplayStateMachine extends StateMachine {
        static final int EVENT_DISMISS_KEYGUARD_ACTIVITY = 1;
        static final int EVENT_SHOW_WHEN_LOCKED_ACTIVITY = 2;
        static final int EVENT_CHECK_KEYGUARD_VISIBILITY = 3;
        static final int EVENT_TOP_ACTIVITY_OCCLUDES_KEYGUARD = 4;
        static final int EVENT_LAYOUT_CHANGES = 5;
        static final int EVENT_DUMP = 6;
        static final int EVENT_DISMISS_KEYGUARD_API = 7;
        static final int EVENT_TURN_SCREEN_ON_ACTIVITY = 8;
        final int mDisplayId;

        static final class CheckKeyguardVisibilityParam {
            boolean mRet;
            @NonNull final ActivityRecord mActivity;

            CheckKeyguardVisibilityParam(@NonNull ActivityRecord activity) {
                mActivity = activity;
            }
        }

        static final class DismissKeyguardParam {
            boolean mRet;
            @NonNull final ActivityRecord mActivity;
            @Nullable final IKeyguardDismissCallback mCallback;
            @Nullable final CharSequence mMessage;

            DismissKeyguardParam(@NonNull ActivityRecord activity,
                    @Nullable IKeyguardDismissCallback callback, @Nullable CharSequence message) {
                mActivity = activity;
                mCallback = callback;
                mMessage = message;
            }
        }

        static final class TopActivityOccludesKeyguardParam {
            boolean mRet;
            @NonNull final ActivityRecord mActivity;

            TopActivityOccludesKeyguardParam(@NonNull ActivityRecord activity) {
                mActivity = activity;
            }
        }

        static final class DumpParam {
            ArrayList<String> mRet = new ArrayList<>();
            String mPrefix;

            DumpParam(@NonNull String prefix) {
                mPrefix = prefix;
            }
        }

        KeyguardDisplayStateMachine(int displayId, @KeyguardState int initialState) {
            super(initialState);
            mDisplayId = displayId;
        }

        @Nullable
        @Override
        public Handler addStateHandler(int state, Handler handler) {
            Handler prevHandler = super.addStateHandler(state, handler);
            if (prevHandler != null) {
                throw new IllegalStateException(
                        "Duplicate state handler registration: display=" + mDisplayId
                                + ", state=" + state);
            }
            return null;
        }

        @Override
        public void transit(@KeyguardState int newState) {
            if (DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("[ ");
                for (Command cmd : getCommands()) {
                    sb.append(cmd);
                    sb.append(' ');
                }
                sb.append(" ]");
                Slog.d(TAG, "State change: display=" + mDisplayId
                        + ", current=" + keyguardStateToString(getCurrentState())
                        + ", lastRequested=" + keyguardStateToString(getState())
                        + ", newState=" + keyguardStateToString(newState)
                        + ", command=" + sb
                        + ", stack=" + Debug.getCallers(30));
            }
            super.transit(newState);
        }

        @Override
        public void enter(@KeyguardState int state) {
            if (DEBUG) {
                Slog.d(TAG, "enter: display=" + mDisplayId + ", state="
                        + keyguardStateToString(state));
            }
            super.enter(state);
        }

        @Override
        public void exit(@KeyguardState int state) {
            if (DEBUG) {
                Slog.d(TAG, "exit: display=" + mDisplayId + ", state="
                        + keyguardStateToString(state));
            }
            super.exit(state);
        }

        void handleDismissKeyguardActivity() {
            handle(EVENT_DISMISS_KEYGUARD_ACTIVITY, null /* param */);
        }

        void handleTurnScreenOnActivity() {
            handle(EVENT_TURN_SCREEN_ON_ACTIVITY, null /* param */);
        }

        boolean handleDismissKeyguard(@NonNull ActivityRecord r,
                @Nullable IKeyguardDismissCallback callback, @Nullable CharSequence message) {
            DismissKeyguardParam param = new DismissKeyguardParam(r, callback, message);
            handle(EVENT_DISMISS_KEYGUARD_API, param);
            return param.mRet;
        }

        void handleShowWhenLockedActivity() {
            handle(EVENT_SHOW_WHEN_LOCKED_ACTIVITY, null /* param */);
        }

        void handleLayoutChanges() {
            handle(EVENT_LAYOUT_CHANGES, null /* param */);
        }

        boolean checkKeyguardVisibility(@NonNull ActivityRecord r) {
            final CheckKeyguardVisibilityParam param = new CheckKeyguardVisibilityParam(r);
            handle(EVENT_CHECK_KEYGUARD_VISIBILITY, param);
            return param.mRet;
        }

        boolean topActivityOccludesKeyguard(@NonNull ActivityRecord r) {
            final TopActivityOccludesKeyguardParam param = new TopActivityOccludesKeyguardParam(r);
            handle(EVENT_TOP_ACTIVITY_OCCLUDES_KEYGUARD, param);
            return param.mRet;
        }

        ArrayList<String> handleDump(String prefix) {
            final DumpParam param = new DumpParam(prefix);
            handle(EVENT_DUMP, param);
            return param.mRet;
        }
    }

    /**
     * Helper class for implementing handler in type-safe way.
     */
    private abstract static class Handler implements StateMachine.Handler {
        @Override
        public final boolean handle(int event, @Nullable Object param) {
            switch (event) {
                case KeyguardDisplayStateMachine.EVENT_DISMISS_KEYGUARD_ACTIVITY:
                    return handleDismissKeyguardActivity();
                case KeyguardDisplayStateMachine.EVENT_TURN_SCREEN_ON_ACTIVITY:
                    return handleTurnScreenOnActivity();
                case KeyguardDisplayStateMachine.EVENT_DISMISS_KEYGUARD_API: {
                    final KeyguardDisplayStateMachine.DismissKeyguardParam typedParam =
                            (KeyguardDisplayStateMachine.DismissKeyguardParam) param;
                    Optional<Boolean> ret = handleDismissKeyguard(typedParam.mActivity,
                            typedParam.mCallback, typedParam.mMessage);
                    if (ret.isPresent()) {
                        typedParam.mRet = ret.get();
                        return true;
                    }
                    return false;
                }
                case KeyguardDisplayStateMachine.EVENT_SHOW_WHEN_LOCKED_ACTIVITY:
                    return handleShowWhenLockedActivity();
                case KeyguardDisplayStateMachine.EVENT_CHECK_KEYGUARD_VISIBILITY: {
                    final KeyguardDisplayStateMachine.CheckKeyguardVisibilityParam typedParam =
                            (KeyguardDisplayStateMachine.CheckKeyguardVisibilityParam) param;
                    Optional<Boolean> ret = checkKeyguardVisibility(typedParam.mActivity);
                    if (ret.isPresent()) {
                        typedParam.mRet = ret.get();
                        return true;
                    }
                    return false;
                }
                case KeyguardDisplayStateMachine.EVENT_TOP_ACTIVITY_OCCLUDES_KEYGUARD: {
                    final KeyguardDisplayStateMachine.TopActivityOccludesKeyguardParam typedParam =
                            (KeyguardDisplayStateMachine.TopActivityOccludesKeyguardParam) param;
                    Optional<Boolean> ret = topActivityOccludesKeyguardParam(typedParam.mActivity);
                    if (ret.isPresent()) {
                        typedParam.mRet = ret.get();
                        return true;
                    }
                    return false;
                }
                case KeyguardDisplayStateMachine.EVENT_LAYOUT_CHANGES:
                    return handleLayoutChanges();
                case KeyguardDisplayStateMachine.EVENT_DUMP:
                    final KeyguardDisplayStateMachine.DumpParam typedParam =
                            (KeyguardDisplayStateMachine.DumpParam) param;
                    String dumpInfo = handleDump(typedParam.mPrefix);
                    if (dumpInfo != null) {
                        typedParam.mRet.add(dumpInfo);
                    }
                    // keep collecting information for dump up to top status.
                    return false;
                default:
                    Slog.e(TAG, "Handler.handle(): Unknown event(" + event + ")");
                    return false;
            }
        }

        Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord activity) {
            return Optional.empty();
        }

        Optional<Boolean> topActivityOccludesKeyguardParam(@NonNull ActivityRecord r) {
            return Optional.empty();
        }

        /**
         * Handle flags in the activity which request to dismiss the keyguard.
         *
         * @see ActivityOptions#setDismissKeyguard()
         * @see WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD
         */
        boolean handleDismissKeyguardActivity() {
            return false;
        }

        /**
         * Handle flags in the activity which request to turn the screen on. This must be called
         * after dismiss keyguard flag is handled.
         */
        boolean handleTurnScreenOnActivity() {
            return false;
        }
        /**
         * Handle flags in the activity which decides if the activity can be shown on top of the
         * keyguard.
         *
         * @see android.app.Activity#setShowWhenLocked(boolean)
         * @see android.app.Activity#setInheritShowWhenLocked(boolean)
         */
        boolean handleShowWhenLockedActivity() {
            return false;
        }

        /**
         * Request relayout if necessary.
         */
        boolean handleLayoutChanges() {
            return false;
        }

        /**
         * Called when the activity requests to dismiss the keyguard via KeyguardManager APIs.
         *
         * @param r The activity which requested to dismiss the keyguard.
         * @return Present if the state handles, delegate to its parent state otherwise. When the
         *         value is present, the value is {@code true} if the keyguard dismiss request is
         *         processed, {@code false} otherwise.
         */
        Optional<Boolean> handleDismissKeyguard(@NonNull ActivityRecord r,
                @Nullable IKeyguardDismissCallback callback, @Nullable CharSequence message) {
            return Optional.empty();
        }

        @Nullable String handleDump(@NonNull String prefix) {
            return null;
        }
    }

    private static class DisplayState {
        private final int mDisplayId;
        @NonNull private final ServiceDelegate mServiceDelegate;
        private final KeyguardDisplayStateMachine mStateMachine;

        // TODO: Set watchdog timer to sync mLastNotifiedOccludedState == isIn(OCCLUDED)
        private boolean mLastNotifiedOccludedState = false;

        // Top activity which has a window with FLAG_DISMISS_KEYGUARD flag. Valid only when the
        // current state is KEYGUARD_STATE_ON or one of its sub states.
        @Nullable private ActivityRecord mDismissingKeyguardActivity;

        // KeyguardController has requested to dismiss keyguard via IWindowManager#dismissKeyguard.
        // Reset this to false again, once the KeyguardController status is updated.
        private boolean mDismissalRequested = false;

        DisplayState(int displayId, @NonNull ServiceDelegate serviceDelegate) {
            mDisplayId = displayId;
            mServiceDelegate = serviceDelegate;
            mStateMachine = new KeyguardDisplayStateMachine(displayId, KEYGUARD_STATE_OFF);

            mStateMachine.addStateHandler(KEYGUARD_STATE_ROOT, new Handler() {
                @Override
                Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord activity) {
                    return Optional.of(false);
                }

                @Override
                Optional<Boolean> topActivityOccludesKeyguardParam(@NonNull ActivityRecord r) {
                    return Optional.of(false);
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_OFF, new Handler() {
                @Override
                public Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord r) {
                    return Optional.of(true);
                }

                @Override
                Optional<Boolean> handleDismissKeyguard(
                        @NonNull ActivityRecord r, @Nullable IKeyguardDismissCallback callback,
                        @Nullable CharSequence message) {
                    // Keyguard is not shown, so we don't handle the request to dismiss the
                    // keyguard.
                    return Optional.of(false);
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_GOING_AWAY, new Handler() {
                @Override
                public void enter() {
                    mServiceDelegate.deferWindowLayout();
                    try {
                        mServiceDelegate.requestTransitionIfNeeded(mDisplayId,
                                TRANSIT_KEYGUARD_GOING_AWAY,
                                TRANSIT_FLAG_KEYGUARD_GOING_AWAY
                                        | TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER);
                        // Some stack visibility might change (e.g. docked stack)
                        mServiceDelegate.releaseSleepToken(mDisplayId,
                                true /* resumeTopActivities */);
                        mServiceDelegate.executeAppTransition();
                    } finally {
                        mServiceDelegate.continueWindowLayout();
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_ON, new Handler() {
                public boolean handleDismissKeyguardActivity() {
                    final ActivityRecord lastDismissingKeyguardActivity =
                            mDismissingKeyguardActivity;
                    final ActivityRecord top = mServiceDelegate.getTopNonFinishingActivity(
                            mDisplayId);
                    mDismissingKeyguardActivity =
                            (top != null && top.containsDismissKeyguardWindow()) ? top : null;
                    if (lastDismissingKeyguardActivity != mDismissingKeyguardActivity
                            && mDismissingKeyguardActivity != null
                            && mServiceDelegate.isKeyguardSecure()) {
                        // We only allow dismissing Keyguard via the flag when Keyguard is secure
                        // for legacy reasons, because that's how apps used to dismiss Keyguard in
                        // the secure case. In the insecure case, we actually show it on top of the
                        // lockscreen. See #canShowWhileOccluded.
                        mDismissalRequested = true;
                        mServiceDelegate.dismissKeyguard(null, null);
                    }
                    return true;
                }

                @Override
                Optional<Boolean> handleDismissKeyguard(@NonNull ActivityRecord r,
                        @Nullable IKeyguardDismissCallback callback,
                        @Nullable CharSequence message) {
                    if (!r.visibleIgnoringKeyguard) {
                        return Optional.of(false);
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "Activity requesting to dismiss Keyguard: " + r);
                    }
                    // If the client has requested to dismiss the keyguard and the Activity has the
                    // flag to turn the screen on, wakeup the screen if it's the top Activity.
                    // Note that it's possible that the client requests to dismiss the keyguard
                    // before the activity adds a window. In this case the flag set on the window
                    // is not yet visible from ActivityRecord, so we need to check the flag again
                    // when the activity adds a window later. See #handleTurnScreenOnActivity().
                    if (r.getTurnScreenOnFlag() && r.isTopRunningActivity()) {
                        mServiceDelegate.wakeUp("ON/handleDismissKeyguard");
                        r.setCurrentLaunchCanTurnScreenOn(false);
                    }
                    mDismissalRequested = true;
                    mServiceDelegate.dismissKeyguard(callback, message);
                    return Optional.of(true);
                }

                @Override
                public void enter() {
                    // Update the task snapshot if the screen will not be turned off. To make sure
                    // that the unlocking animation can animate consistent content.
                    mServiceDelegate.snapshotForSleeping(mDisplayId);
                }

                @Nullable
                @Override
                String handleDump(@NonNull String prefix) {
                    StringBuffer sb = new StringBuffer();
                    sb.append(prefix)
                            .append("  mDismissingKeyguardActivity=")
                            .append(mDismissingKeyguardActivity)
                            .append("\n");
                    return sb.toString();
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_OCCLUDED, new Handler() {
                ActivityRecord mTopOccludesActivity;

                @Override
                Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord activity) {
                    return Optional.of(mServiceDelegate.canOcclude(activity));
                }

                @Override
                public boolean handleShowWhenLockedActivity() {
                    final ActivityRecord top = mServiceDelegate.getTopNonFinishingActivity(
                            mDisplayId);
                    final ActivityRecord topOccludesActivity = mServiceDelegate.canOcclude(top)
                            ? top : null;
                    if (mTopOccludesActivity == topOccludesActivity) {
                        return true;
                    }
                    // Launch SHOW_WHEN_LOCKED or INHERIT_SHOW_WHEN_LOCKED activity on top of an
                    // occluding activity.
                    mTopOccludesActivity = topOccludesActivity;
                    if (mServiceDelegate.isTopActivityDreaming(mDisplayId)) {
                        // Dream activity is launched on top of the previous SHOW_WHEN_LOCKED
                        // activity.
                        setKeyguardState(KEYGUARD_STATE_DREAMING);
                    } else if (topOccludesActivity == null) {
                        // SHOW_WHEN_LOCKED activity finishes.
                        setKeyguardState(KEYGUARD_STATE_LOCKSCREEN_SHOWN);
                    }
                    return true;
                }

                @Override
                boolean handleLayoutChanges() {
                    // The occluding activity may be translucent or not fill screen. Then let
                    // wallpaper to check whether it should set itself as target to avoid blank
                    // background.
                    if (!mTopOccludesActivity.fillsParent()) {
                        mServiceDelegate.requestLayoutRedoWallpaper(mDisplayId);
                    }
                    return true;
                }

                @Override
                Optional<Boolean> topActivityOccludesKeyguardParam(@NonNull ActivityRecord r) {
                    return Optional.of(mTopOccludesActivity == r);
                }

                @Override
                public void enter() {
                    mTopOccludesActivity = mServiceDelegate.getTopNonFinishingActivity(mDisplayId);
                    if (!mServiceDelegate.canOcclude(mTopOccludesActivity)) {
                        Slog.e(TAG, "enter(OCCLUDE): no occluding activity");
                        setKeyguardState(KEYGUARD_STATE_LOCKSCREEN_SHOWN);
                        return;
                    }

                    if (DEBUG) {
                        Slog.d(TAG, "handleOccludedChanged: display=" + mDisplayId
                                + ", topActivity=" + mTopOccludesActivity);
                    }
                    // Collect the participates for shell transition, so that transition won't
                    // happen too early since the transition was set ready.
                    mServiceDelegate.collect(mTopOccludesActivity);
                    // TODO(b/113840485): Handle app transition for individual display, and apply
                    // occluded state change to secondary displays. For now, only default display
                    // fully supports occluded change. Other displays only updates keyguard sleep
                    // token on that display.
                    if (mDisplayId != DEFAULT_DISPLAY) {
                        mServiceDelegate.releaseSleepToken(mDisplayId,
                                false /* resumeTopActivities */);
                        return;
                    }

                    if (mTopOccludesActivity.getTurnScreenOnFlag()
                            && mTopOccludesActivity.currentLaunchCanTurnScreenOn()
                            && !mServiceDelegate.isDeviceInteractive()) {
                        mServiceDelegate.wakeUp("OCCLUDE/enter");
                        mTopOccludesActivity.setCurrentLaunchCanTurnScreenOn(false);
                    }

                    mServiceDelegate.notifyKeyguardOccludeChanged(true /* occluded */);
                    mServiceDelegate.deferWindowLayout();
                    try {
                        mServiceDelegate.requestTransitionIfNeeded(mDisplayId,
                                TRANSIT_KEYGUARD_OCCLUDE, 0 /* flags */);
                        mServiceDelegate.releaseSleepToken(mDisplayId,
                                false /* resumeTopActivities */);
                        mServiceDelegate.executeAppTransition();
                    } finally {
                        mServiceDelegate.continueWindowLayout();
                    }
                    // Dismiss freeform windowing mode
                    final Task currentTaskControllingOcclusion = mTopOccludesActivity.getRootTask();
                    if (currentTaskControllingOcclusion != null
                            && currentTaskControllingOcclusion.inFreeformWindowingMode()) {
                        currentTaskControllingOcclusion.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
                    }
                }

                @Override
                public void exit() {
                    mTopOccludesActivity = null;
                    if (DEBUG) {
                        Slog.d(TAG, "handleOccludedChanged: topActivity=" + null);
                    }
                    // TODO(b/113840485): Handle app transition for individual display, and apply
                    // occluded state change to secondary displays.
                    // For now, only default display fully supports occluded change. Other displays
                    // only updates keyguard sleep token on that display.
                    if (mDisplayId != DEFAULT_DISPLAY) {
                        mServiceDelegate.acquireSleepToken(
                                mDisplayId, false /* ensureActivitiesVisible */);
                        return;
                    }

                    mServiceDelegate.notifyKeyguardOccludeChanged(false /* occluded */);
                    mServiceDelegate.deferWindowLayout();
                    try {
                        mServiceDelegate.requestTransitionIfNeeded(mDisplayId,
                                TRANSIT_KEYGUARD_UNOCCLUDE, 0 /* flags */);
                        mServiceDelegate.acquireSleepToken(
                                mDisplayId, false /* ensureActivitiesVisible */);
                        mServiceDelegate.executeAppTransition();
                    } finally {
                        mServiceDelegate.continueWindowLayout();
                    }
                }

                @Nullable
                @Override
                String handleDump(@NonNull String prefix) {
                    StringBuffer sb = new StringBuffer();
                    sb.append(prefix)
                            .append("  mTopOccludesActivity=")
                            .append(mTopOccludesActivity)
                            .append("\n");
                    return sb.toString();
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_KEYGUARD_TOP, new Handler() {
                @Override
                public boolean handleDismissKeyguardActivity() {
                    final ActivityRecord top = mServiceDelegate.getTopNonFinishingActivity(
                            mDisplayId);
                    if (top != null && top.mDismissKeyguard) {
                        // Top activity has been launched with ActivityOptions#setDismissKeyguard.
                        // Authentication has already been passed, so we can turn off the keyguard
                        // immediately.
                        top.mDismissKeyguard = false;
                        setKeyguardState(KEYGUARD_STATE_GOING_AWAY);
                        // Collect the participates for shell transition, so that transition won't
                        // happen too early since the transition was set ready.
                        mServiceDelegate.collect(top);
                        return true;
                    }
                    return false;
                }

                @Override
                public void enter() {
                    mServiceDelegate.acquireSleepToken(mDisplayId,
                            true /* ensureActivitiesVisible */);
                    InputMethodManagerInternal.get().updateImeWindowStatus(
                            false /* disableImeIcon */);
                    mServiceDelegate.setWakeTransitionReady();
                }

                @Override
                public void exit() {
                    // Sleep token is released in enter() action in other states, since we need
                    // to call requestTransition() before updating visibility of the activities.
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_LOCKSCREEN_SHOWN, new Handler() {
                @Override
                public Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord r) {
                    // If lock screen is showing, nothing is visible, except if we are able to
                    // dismiss Keyguard right away. This isn't allowed if r is already the
                    // dismissing activity, in which case we don't allow it to repeatedly
                    // dismiss Keyguard.
                    return Optional.of(r.containsDismissKeyguardWindow()
                            && mServiceDelegate.canDismissKeyguard()
                            && (mDismissalRequested
                            || (r.canShowWhenLocked() && mDismissingKeyguardActivity != r)));
                }

                @Override
                public boolean handleShowWhenLockedActivity() {
                    final ActivityRecord top = mServiceDelegate.getTopNonFinishingActivity(
                            mDisplayId);
                    final ActivityRecord topOccludesActivity = mServiceDelegate.canOcclude(top)
                            ? top : null;
                    if (topOccludesActivity != null) {
                        setKeyguardState(mServiceDelegate.isTopActivityDreaming(mDisplayId)
                                ? KEYGUARD_STATE_DREAMING : KEYGUARD_STATE_OCCLUDED);
                    }
                    return true;
                }
            });

            mStateMachine.addStateHandler(KEYGUARD_STATE_AOD_SHOWN, new Handler() {
                // Top activity which has FLAG_TURN_SCREEN_ON flag.
                @Nullable private ActivityRecord mTopTurnScreenOnActivity;

                @Override
                public Optional<Boolean> checkKeyguardVisibility(@NonNull ActivityRecord r) {
                    return Optional.of(false);
                }

                @Override
                boolean handleTurnScreenOnActivity() {
                    final ActivityRecord lastTopTurnScreenOnActivity = mTopTurnScreenOnActivity;
                    final ActivityRecord top = mServiceDelegate.getTopNonFinishingActivity(
                            mDisplayId);
                    mTopTurnScreenOnActivity = (top != null && top.getTurnScreenOnFlag()
                            && top.currentLaunchCanTurnScreenOn()) ? top : null;
                    if (mTopTurnScreenOnActivity != lastTopTurnScreenOnActivity
                            && mTopTurnScreenOnActivity != null
                            && !mServiceDelegate.isDeviceInteractive()
                            && mDismissalRequested) {
                        mServiceDelegate.wakeUp("AOD_SHOWN/handleTurnScreenOnActivity");
                        mTopTurnScreenOnActivity.setCurrentLaunchCanTurnScreenOn(false);
                    }
                    return true;
                }

                @Override
                public void enter() {
                    if (mLastNotifiedOccludedState) {
                        if (mDisplayId == DEFAULT_DISPLAY) {
                            mServiceDelegate.forceSyncOccludedStatus(false);
                        }
                        commitOccludedStatus(false);
                    }
                }

                @Override
                public void exit() {
                    mServiceDelegate.updateDeferTransitionForAod(false /* waiting */);
                }

                @Nullable
                @Override
                String handleDump(@NonNull String prefix) {
                    StringBuffer sb = new StringBuffer();
                    sb.append(prefix)
                            .append("  mTopTurnScreenOnActivity=")
                            .append(mTopTurnScreenOnActivity)
                            .append("\n");
                    return sb.toString();
                }
            });
        }

        void onRemoved() {
            mServiceDelegate.releaseSleepToken(mDisplayId, false /* resumeTopActivities */);
        }

        void updateVisibility() {
            mStateMachine.handleDismissKeyguardActivity();
            mStateMachine.handleTurnScreenOnActivity();
            mStateMachine.handleShowWhenLockedActivity();
            mStateMachine.handleLayoutChanges();
        }

        boolean dismissKeyguard(@NonNull ActivityRecord r,
                @Nullable IKeyguardDismissCallback callback,
                @Nullable CharSequence message) {
            return mStateMachine.handleDismissKeyguard(r, callback, message);
        }

        void commitOccludedStatus(boolean occluded) {
            mLastNotifiedOccludedState = occluded;
        }

        void setKeyguardState(@KeyguardState int newState) {
            mDismissalRequested = false;
            mStateMachine.transit(newState);
        }

        boolean isKeyguardTop() {
            return mStateMachine.isIn(KEYGUARD_STATE_KEYGUARD_TOP);
        }

        boolean isIn(@KeyguardState int category) {
            return mStateMachine.isIn(category);
        }

        boolean topActivityOccludesKeyguard(@NonNull ActivityRecord r) {
            return mStateMachine.topActivityOccludesKeyguard(r);
        }

        boolean checkKeyguardVisibility(@NonNull ActivityRecord r) {
            return mStateMachine.checkKeyguardVisibility(r);
        }

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(KeyguardPerDisplayProto.DISPLAY_ID, mDisplayId);
            proto.write(KeyguardPerDisplayProto.KEYGUARD_SHOWING,
                    isIn(KEYGUARD_STATE_LOCKSCREEN_SHOWN));
            proto.write(KeyguardPerDisplayProto.AOD_SHOWING, isIn(KEYGUARD_STATE_AOD_SHOWN));
            proto.write(KeyguardPerDisplayProto.KEYGUARD_OCCLUDED, isIn(KEYGUARD_STATE_OCCLUDED));
            proto.end(token);
        }

        void dump(PrintWriter pw, String prefix) {
            StringBuffer sb = new StringBuffer();
            sb.append(prefix)
                    .append("* display=")
                    .append(mDisplayId)
                    .append("\n");
            sb.append(prefix)
                    .append("  state=")
                    .append(keyguardStateToString(mStateMachine.getState()))
                    .append("\n");
            sb.append(prefix)
                    .append("  mLastNotifiedOccludedState=")
                    .append(mLastNotifiedOccludedState)
                    .append("\n");
            sb.append(prefix)
                    .append("  mDismissalRequested=")
                    .append(mDismissalRequested)
                    .append("\n");
            pw.print(sb.toString());

            ArrayList<String> dumpInfo = mStateMachine.handleDump(prefix);
            for (int i = dumpInfo.size() - 1; i >= 0; --i) {
                pw.print(dumpInfo.get(i));
            }
        }
    }
}
