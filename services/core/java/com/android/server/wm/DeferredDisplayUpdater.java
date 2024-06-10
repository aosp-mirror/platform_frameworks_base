/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.view.WindowManager.TRANSIT_CHANGE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS;
import static com.android.server.wm.ActivityTaskManagerService.POWER_MODE_REASON_CHANGE_DISPLAY;
import static com.android.server.wm.utils.DisplayInfoOverrides.WM_OVERRIDE_FIELDS;
import static com.android.server.wm.utils.DisplayInfoOverrides.copyDisplayInfoFields;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Message;
import android.os.Trace;
import android.util.Slog;
import android.view.DisplayInfo;
import android.window.DisplayAreaInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.wm.utils.DisplayInfoOverrides.DisplayInfoFieldsUpdater;
import com.android.window.flags.Flags;

import java.util.Arrays;
import java.util.Objects;

/**
 * A DisplayUpdater that could defer and queue display updates coming from DisplayManager to
 * WindowManager. It allows to defer pending display updates if WindowManager is currently not
 * ready to apply them.
 * For example, this might happen if there is a Shell transition running and physical display
 * changed. We can't immediately apply the display updates because we want to start a separate
 * display change transition. In this case, we will queue all display updates until the current
 * transition's collection finishes and then apply them afterwards.
 */
public class DeferredDisplayUpdater implements DisplayUpdater {

    /**
     * List of fields that could be deferred before applying to DisplayContent.
     * This should be kept in sync with {@link DeferredDisplayUpdater#calculateDisplayInfoDiff}
     */
    @VisibleForTesting
    static final DisplayInfoFieldsUpdater DEFERRABLE_FIELDS = (out, override) -> {
        // Treat unique id and address change as WM-specific display change as we re-query display
        // settings and parameters based on it which could cause window changes
        out.uniqueId = override.uniqueId;
        out.address = override.address;

        // Also apply WM-override fields, since they might produce differences in window hierarchy
        WM_OVERRIDE_FIELDS.setFields(out, override);
    };

    private static final String TAG = "DeferredDisplayUpdater";

    private static final String TRACE_TAG_WAIT_FOR_TRANSITION =
            "Screen unblock: wait for transition";
    private static final int WAIT_FOR_TRANSITION_TIMEOUT = 1000;

    private final DisplayContent mDisplayContent;

    @NonNull
    private final DisplayInfo mNonOverrideDisplayInfo = new DisplayInfo();

    /**
     * The last known display parameters from DisplayManager, some WM-specific fields in this object
     * might not be applied to the DisplayContent yet
     */
    @Nullable
    private DisplayInfo mLastDisplayInfo;

    /**
     * The last DisplayInfo that was applied to DisplayContent, only WM-specific parameters must be
     * used from this object. This object is used to store old values of DisplayInfo while these
     * fields are pending to be applied to DisplayContent.
     */
    @Nullable
    private DisplayInfo mLastWmDisplayInfo;

    @NonNull
    private final DisplayInfo mOutputDisplayInfo = new DisplayInfo();

    /** Whether {@link #mScreenUnblocker} should wait for transition to be ready. */
    private boolean mShouldWaitForTransitionWhenScreenOn;

    /** The message to notify PhoneWindowManager#finishWindowsDrawn. */
    @Nullable
    private Message mScreenUnblocker;

    private final Runnable mScreenUnblockTimeoutRunnable = () -> {
        Slog.e(TAG, "Timeout waiting for the display switch transition to start");
        continueScreenUnblocking();
    };

    public DeferredDisplayUpdater(@NonNull DisplayContent displayContent) {
        mDisplayContent = displayContent;
        mNonOverrideDisplayInfo.copyFrom(mDisplayContent.getDisplayInfo());
    }

    /**
     * Reads the latest display parameters from the display manager and returns them in a callback.
     * If there are pending display updates, it will wait for them to finish first and only then it
     * will call the callback with the latest display parameters.
     *
     * @param finishCallback is called when all pending display updates are finished
     */
    @Override
    public void updateDisplayInfo(@NonNull Runnable finishCallback) {
        // Get the latest display parameters from the DisplayManager
        final DisplayInfo displayInfo = getCurrentDisplayInfo();

        final int displayInfoDiff = calculateDisplayInfoDiff(mLastDisplayInfo, displayInfo);
        final boolean physicalDisplayUpdated = isPhysicalDisplayUpdated(mLastDisplayInfo,
                displayInfo);

        mLastDisplayInfo = displayInfo;

        // Apply whole display info immediately as is if either:
        // * it is the first display update
        // * the display doesn't have visible content
        // * shell transitions are disabled or temporary unavailable
        if (displayInfoDiff == DIFF_EVERYTHING
                || !mDisplayContent.getLastHasContent()
                || !mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            ProtoLog.d(WM_DEBUG_WINDOW_TRANSITIONS,
                    "DeferredDisplayUpdater: applying DisplayInfo immediately");

            mLastWmDisplayInfo = displayInfo;
            applyLatestDisplayInfo();
            finishCallback.run();
            return;
        }

        // If there are non WM-specific display info changes, apply only these fields immediately
        if ((displayInfoDiff & DIFF_NOT_WM_DEFERRABLE) > 0) {
            ProtoLog.d(WM_DEBUG_WINDOW_TRANSITIONS,
                    "DeferredDisplayUpdater: partially applying DisplayInfo immediately");
            applyLatestDisplayInfo();
        }

        // If there are WM-specific display info changes, apply them through a Shell transition
        if ((displayInfoDiff & DIFF_WM_DEFERRABLE) > 0) {
            ProtoLog.d(WM_DEBUG_WINDOW_TRANSITIONS,
                    "DeferredDisplayUpdater: deferring DisplayInfo update");

            requestDisplayChangeTransition(physicalDisplayUpdated, () -> {
                // Apply deferrable fields to DisplayContent only when the transition
                // starts collecting, non-deferrable fields are ignored in mLastWmDisplayInfo
                mLastWmDisplayInfo = displayInfo;
                applyLatestDisplayInfo();
                finishCallback.run();
            });
        } else {
            // There are no WM-specific updates, so we can immediately notify that all display
            // info changes are applied
            finishCallback.run();
        }
    }

    /**
     * Requests a display change Shell transition
     *
     * @param physicalDisplayUpdated if true also starts remote display change
     * @param onStartCollect         called when the Shell transition starts collecting
     */
    private void requestDisplayChangeTransition(boolean physicalDisplayUpdated,
            @NonNull Runnable onStartCollect) {

        final Transition transition = new Transition(TRANSIT_CHANGE, /* flags= */ 0,
                mDisplayContent.mTransitionController,
                mDisplayContent.mTransitionController.mSyncEngine);

        mDisplayContent.mAtmService.startPowerMode(POWER_MODE_REASON_CHANGE_DISPLAY);

        mDisplayContent.mTransitionController.startCollectOrQueue(transition, deferred -> {
            final Rect startBounds = new Rect(0, 0, mDisplayContent.mInitialDisplayWidth,
                    mDisplayContent.mInitialDisplayHeight);
            final int fromRotation = mDisplayContent.getRotation();
            if (Flags.blastSyncNotificationShadeOnDisplaySwitch() && physicalDisplayUpdated) {
                final WindowState notificationShade =
                        mDisplayContent.getDisplayPolicy().getNotificationShade();
                if (notificationShade != null && notificationShade.isVisible()
                        && mDisplayContent.mAtmService.mKeyguardController.isKeyguardOrAodShowing(
                                mDisplayContent.mDisplayId)) {
                    Slog.i(TAG, notificationShade + " uses blast for display switch");
                    notificationShade.mSyncMethodOverride = BLASTSyncEngine.METHOD_BLAST;
                }
            }

            mDisplayContent.mAtmService.deferWindowLayout();
            try {
                onStartCollect.run();

                ProtoLog.d(WM_DEBUG_WINDOW_TRANSITIONS,
                        "DeferredDisplayUpdater: applied DisplayInfo after deferring");

                if (physicalDisplayUpdated) {
                    onDisplayUpdated(transition, fromRotation, startBounds);
                } else {
                    final TransitionRequestInfo.DisplayChange displayChange =
                            getCurrentDisplayChange(fromRotation, startBounds);
                    mDisplayContent.mTransitionController.requestStartTransition(transition,
                            /* startTask= */ null, /* remoteTransition= */ null, displayChange);
                }
            } finally {
                // Run surface placement after requestStartTransition, so shell side can receive
                // the transition request before handling task info changes.
                mDisplayContent.mAtmService.continueWindowLayout();
            }
        });
    }

    /**
     * Applies current DisplayInfo to DisplayContent, DisplayContent is merged from two parts:
     * - non-deferrable fields are set from the most recent values received from DisplayManager
     * (uses {@link mLastDisplayInfo} field)
     * - deferrable fields are set from the latest values that we could apply to WM
     * (uses {@link mLastWmDisplayInfo} field)
     */
    private void applyLatestDisplayInfo() {
        copyDisplayInfoFields(mOutputDisplayInfo, /* base= */ mLastDisplayInfo,
                /* override= */ mLastWmDisplayInfo, /* fields= */ DEFERRABLE_FIELDS);
        mDisplayContent.onDisplayInfoUpdated(mOutputDisplayInfo);
    }

    @NonNull
    private DisplayInfo getCurrentDisplayInfo() {
        mDisplayContent.mWmService.mDisplayManagerInternal.getNonOverrideDisplayInfo(
                mDisplayContent.mDisplayId, mNonOverrideDisplayInfo);
        return new DisplayInfo(mNonOverrideDisplayInfo);
    }

    @NonNull
    private TransitionRequestInfo.DisplayChange getCurrentDisplayChange(int fromRotation,
            @NonNull Rect startBounds) {
        final Rect endBounds = new Rect(0, 0, mDisplayContent.mInitialDisplayWidth,
                mDisplayContent.mInitialDisplayHeight);
        final int toRotation = mDisplayContent.getRotation();

        final TransitionRequestInfo.DisplayChange displayChange =
                new TransitionRequestInfo.DisplayChange(mDisplayContent.getDisplayId());
        displayChange.setStartAbsBounds(startBounds);
        displayChange.setEndAbsBounds(endBounds);
        displayChange.setStartRotation(fromRotation);
        displayChange.setEndRotation(toRotation);
        return displayChange;
    }

    /**
     * Called when physical display is updated, this could happen e.g. on foldable
     * devices when the physical underlying display is replaced. This method should be called
     * when the new display info is already applied to the WM hierarchy.
     *
     * @param fromRotation rotation before the display change
     * @param startBounds  display bounds before the display change
     */
    private void onDisplayUpdated(@NonNull Transition transition, int fromRotation,
            @NonNull Rect startBounds) {
        final int toRotation = mDisplayContent.getRotation();

        final TransitionRequestInfo.DisplayChange displayChange =
                getCurrentDisplayChange(fromRotation, startBounds);
        displayChange.setPhysicalDisplayChanged(true);

        transition.addTransactionCompletedListener(this::continueScreenUnblocking);
        mDisplayContent.mTransitionController.requestStartTransition(transition,
                /* startTask= */ null, /* remoteTransition= */ null, displayChange);

        final DisplayAreaInfo newDisplayAreaInfo = mDisplayContent.getDisplayAreaInfo();

        final boolean startedRemoteChange = mDisplayContent.mRemoteDisplayChangeController
                .performRemoteDisplayChange(fromRotation, toRotation, newDisplayAreaInfo,
                        transaction -> finishDisplayUpdate(transaction, transition));

        if (!startedRemoteChange) {
            finishDisplayUpdate(/* wct= */ null, transition);
        }
    }

    private void finishDisplayUpdate(@Nullable WindowContainerTransaction wct,
            @NonNull Transition transition) {
        if (wct != null) {
            mDisplayContent.mAtmService.mWindowOrganizerController.applyTransaction(
                    wct);
        }
        transition.setAllReady();
    }

    private boolean isPhysicalDisplayUpdated(@Nullable DisplayInfo first,
            @Nullable DisplayInfo second) {
        if (first == null || second == null) return true;
        return !Objects.equals(first.uniqueId, second.uniqueId);
    }

    @Override
    public void onDisplayContentDisplayPropertiesPostChanged(int previousRotation, int newRotation,
            DisplayAreaInfo newDisplayAreaInfo) {
        // Unblock immediately in case there is no transition. This is unlikely to happen.
        if (mScreenUnblocker != null && !mDisplayContent.mTransitionController.inTransition()) {
            mScreenUnblocker.sendToTarget();
            mScreenUnblocker = null;
        }
    }

    @Override
    public void onDisplaySwitching(boolean switching) {
        mShouldWaitForTransitionWhenScreenOn = switching;
    }

    @Override
    public boolean waitForTransition(@NonNull Message screenUnblocker) {
        if (!Flags.waitForTransitionOnDisplaySwitch()) return false;
        if (!mShouldWaitForTransitionWhenScreenOn) {
            return false;
        }
        mScreenUnblocker = screenUnblocker;
        if (Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
            Trace.beginAsyncSection(TRACE_TAG_WAIT_FOR_TRANSITION, screenUnblocker.hashCode());
        }

        mDisplayContent.mWmService.mH.removeCallbacks(mScreenUnblockTimeoutRunnable);
        mDisplayContent.mWmService.mH.postDelayed(mScreenUnblockTimeoutRunnable,
                WAIT_FOR_TRANSITION_TIMEOUT);
        return true;
    }

    /**
     * Continues the screen unblocking flow, could be called either on a binder thread as
     * a result of surface transaction completed listener or from {@link WindowManagerService#mH}
     * handler in case of timeout
     */
    private void continueScreenUnblocking() {
        synchronized (mDisplayContent.mWmService.mGlobalLock) {
            mShouldWaitForTransitionWhenScreenOn = false;
            mDisplayContent.mWmService.mH.removeCallbacks(mScreenUnblockTimeoutRunnable);
            if (mScreenUnblocker == null) {
                return;
            }
            mScreenUnblocker.sendToTarget();
            if (Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
                Trace.endAsyncSection(TRACE_TAG_WAIT_FOR_TRANSITION, mScreenUnblocker.hashCode());
            }
            mScreenUnblocker = null;
        }
    }

    /**
     * Diff result: fields are the same
     */
    static final int DIFF_NONE = 0;

    /**
     * Diff result: fields that could be deferred in WM are different
     */
    static final int DIFF_WM_DEFERRABLE = 1 << 0;

    /**
     * Diff result: fields that could not be deferred in WM are different
     */
    static final int DIFF_NOT_WM_DEFERRABLE = 1 << 1;

    /**
     * Diff result: everything is different
     */
    static final int DIFF_EVERYTHING = 0XFFFFFFFF;

    @VisibleForTesting
    static int calculateDisplayInfoDiff(@Nullable DisplayInfo first, @Nullable DisplayInfo second) {
        int diff = DIFF_NONE;

        if (Objects.equals(first, second)) return diff;
        if (first == null || second == null) return DIFF_EVERYTHING;

        if (first.layerStack != second.layerStack
                || first.flags != second.flags
                || first.type != second.type
                || first.displayId != second.displayId
                || first.displayGroupId != second.displayGroupId
                || !Objects.equals(first.deviceProductInfo, second.deviceProductInfo)
                || first.modeId != second.modeId
                || first.renderFrameRate != second.renderFrameRate
                || first.defaultModeId != second.defaultModeId
                || first.userPreferredModeId != second.userPreferredModeId
                || !Arrays.equals(first.supportedModes, second.supportedModes)
                || !Arrays.equals(first.appsSupportedModes, second.appsSupportedModes)
                || first.colorMode != second.colorMode
                || !Arrays.equals(first.supportedColorModes, second.supportedColorModes)
                || !Objects.equals(first.hdrCapabilities, second.hdrCapabilities)
                || !Arrays.equals(first.userDisabledHdrTypes, second.userDisabledHdrTypes)
                || first.minimalPostProcessingSupported != second.minimalPostProcessingSupported
                || first.appVsyncOffsetNanos != second.appVsyncOffsetNanos
                || first.presentationDeadlineNanos != second.presentationDeadlineNanos
                || first.state != second.state
                || first.committedState != second.committedState
                || first.ownerUid != second.ownerUid
                || !Objects.equals(first.ownerPackageName, second.ownerPackageName)
                || first.removeMode != second.removeMode
                || first.getRefreshRate() != second.getRefreshRate()
                || first.brightnessMinimum != second.brightnessMinimum
                || first.brightnessMaximum != second.brightnessMaximum
                || first.brightnessDefault != second.brightnessDefault
                || first.installOrientation != second.installOrientation
                || !Objects.equals(first.layoutLimitedRefreshRate, second.layoutLimitedRefreshRate)
                || !BrightnessSynchronizer.floatEquals(first.hdrSdrRatio, second.hdrSdrRatio)
                || !first.thermalRefreshRateThrottling.contentEquals(
                second.thermalRefreshRateThrottling)
                || !Objects.equals(first.thermalBrightnessThrottlingDataId,
                second.thermalBrightnessThrottlingDataId)) {
            diff |= DIFF_NOT_WM_DEFERRABLE;
        }

        if (first.appWidth != second.appWidth
                || first.appHeight != second.appHeight
                || first.smallestNominalAppWidth != second.smallestNominalAppWidth
                || first.smallestNominalAppHeight != second.smallestNominalAppHeight
                || first.largestNominalAppWidth != second.largestNominalAppWidth
                || first.largestNominalAppHeight != second.largestNominalAppHeight
                || first.logicalWidth != second.logicalWidth
                || first.logicalHeight != second.logicalHeight
                || first.physicalXDpi != second.physicalXDpi
                || first.physicalYDpi != second.physicalYDpi
                || first.rotation != second.rotation
                || !Objects.equals(first.displayCutout, second.displayCutout)
                || first.logicalDensityDpi != second.logicalDensityDpi
                || !Objects.equals(first.roundedCorners, second.roundedCorners)
                || !Objects.equals(first.displayShape, second.displayShape)
                || !Objects.equals(first.uniqueId, second.uniqueId)
                || !Objects.equals(first.address, second.address)
        ) {
            diff |= DIFF_WM_DEFERRABLE;
        }

        return diff;
    }
}
