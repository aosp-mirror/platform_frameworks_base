/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.InsetsSource.ID_IME;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_IME;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.ImeInsetsSourceProviderProto.IME_TARGET_FROM_IME;
import static com.android.server.wm.ImeInsetsSourceProviderProto.INSETS_SOURCE_PROVIDER;
import static com.android.server.wm.WindowManagerService.H.UPDATE_MULTI_WINDOW_STACKS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceConsumer;
import android.view.InsetsSourceControl;
import android.view.WindowInsets;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

import java.io.PrintWriter;

/**
 * Controller for IME inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
final class ImeInsetsSourceProvider extends InsetsSourceProvider {

    private static final String TAG = ImeInsetsSourceProvider.class.getSimpleName();

    /** The token tracking the show IME request, non-null only while a show request is pending. */
    @Nullable
    private ImeTracker.Token mStatsToken;
    /** The target that requested to show the IME, non-null only while a show request is pending. */
    @Nullable
    private InsetsControlTarget mImeRequester;
    /** @see #isImeShowing() */
    private boolean mImeShowing;
    /** The latest received insets source. */
    private final InsetsSource mLastSource = new InsetsSource(ID_IME, WindowInsets.Type.ime());

    /** @see #setFrozen(boolean) */
    private boolean mFrozen;

    /**
     * The server visibility of the source provider's window container. This is out of sync with
     * {@link InsetsSourceProvider#mServerVisible} while {@link #mFrozen} is {@code true}.
     *
     * @see #setServerVisible
     */
    private boolean mServerVisible;

    /**
     * When the IME is not ready, it has givenInsetsPending. However, this could happen again,
     * after it became serverVisible. This flag indicates is used to determine if it is
     * readyForDispatching
     */
    private boolean mGivenInsetsReady = false;

    ImeInsetsSourceProvider(@NonNull InsetsSource source,
            @NonNull InsetsStateController stateController,
            @NonNull DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }

    @Override
    void onPostLayout() {
        boolean wasServerVisible = mServerVisible;
        super.onPostLayout();

        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            final WindowState ws =
                    mWindowContainer != null ? mWindowContainer.asWindowState() : null;
            final boolean givenInsetsPending = ws != null && ws.mGivenInsetsPending;

            // isLeashReadyForDispatching (used to dispatch the leash of the control) is
            // depending on mGivenInsetsReady. Therefore, triggering notifyControlChanged here
            // again, so that the control with leash can be eventually dispatched
            if (!mGivenInsetsReady && mServerVisible && !givenInsetsPending) {
                mGivenInsetsReady = true;
                ImeTracker.forLogging().onProgress(mStatsToken,
                        ImeTracker.PHASE_WM_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED);
                mStateController.notifyControlChanged(mControlTarget, this);
                setImeShowing(true);
            } else if (wasServerVisible && mServerVisible && mGivenInsetsReady
                    && givenInsetsPending) {
                // If the server visibility didn't change (still visible), and mGivenInsetsReady
                // is set, we won't call into notifyControlChanged. Therefore, we can reset the
                // statsToken, if available.
                ImeTracker.forLogging().onCancelled(mStatsToken,
                        ImeTracker.PHASE_WM_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED);
                mStatsToken = null;
            } else if (wasServerVisible && !mServerVisible) {
                setImeShowing(false);
            }
        }
    }

    @Nullable
    ImeTracker.Token getAndClearStatsToken() {
        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            ImeTracker.Token statsToken = mStatsToken;
            mStatsToken = null;
            return statsToken;
        } else {
            return null;
        }
    }

    @Override
    protected boolean isLeashReadyForDispatching() {
        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            final WindowState ws =
                    mWindowContainer != null ? mWindowContainer.asWindowState() : null;
            final boolean isDrawn = ws != null && ws.isDrawn();
            return super.isLeashReadyForDispatching()
                    && mServerVisible && isDrawn && mGivenInsetsReady;
        } else {
            return super.isLeashReadyForDispatching();
        }
    }

    @Nullable
    @Override
    InsetsSourceControl getControl(InsetsControlTarget target) {
        final InsetsSourceControl control = super.getControl(target);
        if (control != null && target != null && target.getWindow() != null) {
            final WindowState targetWin = target.getWindow();
            final Task task = targetWin.getTask();
            // If the control target changes during the app transition with the task snapshot
            // starting window and the IME snapshot is visible, in case not have duplicated IME
            // showing animation during transitioning, use a flag to inform IME source control to
            // skip showing animation once.
            StartingData startingData = null;
            if (task != null) {
                startingData = targetWin.mActivityRecord.mStartingData;
                if (startingData == null) {
                    final WindowState startingWin = task.topStartingWindow();
                    if (startingWin != null) {
                        startingData = startingWin.mStartingData;
                    }
                }
            }
            control.setSkipAnimationOnce(startingData != null && startingData.hasImeSurface());
        }
        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            if (control != null && control.getLeash() != null) {
                ImeTracker.Token statsToken = getAndClearStatsToken();
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_WM_GET_CONTROL_WITH_LEASH);
                control.setImeStatsToken(statsToken);
            }
        }
        return control;
    }

    @Override
    void setClientVisible(boolean clientVisible) {
        final boolean wasClientVisible = isClientVisible();
        super.setClientVisible(clientVisible);
        // The layer of ImePlaceholder needs to be updated on a higher z-order for
        // non-activity window (For activity window, IME is already on top of it).
        if (!wasClientVisible && isClientVisible()) {
            final InsetsControlTarget imeControlTarget = getControlTarget();
            if (imeControlTarget != null && imeControlTarget.getWindow() != null
                    && imeControlTarget.getWindow().mActivityRecord == null) {
                mDisplayContent.assignWindowLayers(false /* setLayoutNeeded */);
            }
        }
    }

    @Override
    void setServerVisible(boolean serverVisible) {
        if (mServerVisible != serverVisible) {
            mServerVisible = serverVisible;
            // reset the leash if the server visibility becomes hidden
            if (android.view.inputmethod.Flags.refactorInsetsController()) {
                if (!serverVisible && !mFrozen) {
                    mGivenInsetsReady = false;
                    updateControlForTarget(mControlTarget, true /* force */, null /* statsToken */);
                }
            }
        }
        if (!mFrozen) {
            super.setServerVisible(serverVisible);
        }
    }

    /**
     * Freeze IME insets source state when required.
     *
     * <p>When setting {@param frozen} as {@code true}, the IME insets provider will freeze the
     * current IME insets state and pending the IME insets state update until setting
     * {@param frozen} as {@code false}.</p>
     */
    void setFrozen(boolean frozen) {
        if (mFrozen == frozen) {
            return;
        }
        mFrozen = frozen;
        if (!frozen) {
            // Unfreeze and process the pending IME insets states.
            super.setServerVisible(mServerVisible);
        }
    }

    @Override
    void updateSourceFrame(Rect frame) {
        super.updateSourceFrame(frame);
        onSourceChanged();
    }

    @Override
    protected void updateVisibility() {
        boolean oldVisibility = mSource.isVisible();
        super.updateVisibility();
        if (Flags.refactorInsetsController()) {
            if (mSource.isVisible() && !oldVisibility && mImeRequester != null) {
                reportImeDrawnForOrganizerIfNeeded(mImeRequester);
            }
        }
        onSourceChanged();
    }

    @Override
    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force,
            @NonNull ImeTracker.Token statsToken) {
        if (target != null && target.getWindow() != null) {
            // ime control target could be a different window.
            // Refer WindowState#getImeControlTarget().
            target = target.getWindow().getImeControlTarget();
        }
        // TODO(b/329229469) make sure that the statsToken of all callers is non-null (currently
        //  not the case)
        super.updateControlForTarget(target, force, statsToken);
        if (Flags.refactorInsetsController()) {
            // TODO(b/353463205) investigate if we should fail the statsToken, or if it's only
            //  temporary null.
            if (target != null) {
                invokeOnImeRequestedChangedListener(target.getWindow(), statsToken);
            }
        }
    }

    // TODO(b/353463205) change statsToken to be NonNull, after the flag is permanently enabled
    @Override
    protected boolean updateClientVisibility(InsetsTarget caller,
            @Nullable ImeTracker.Token statsToken) {
        InsetsControlTarget controlTarget = getControlTarget();
        if (caller != controlTarget) {
            if (Flags.refactorInsetsController()) {
                if (isImeInputTarget(caller)) {
                    // In case of the multi window mode, update the requestedVisibleTypes from
                    // the controlTarget (=RemoteInsetsControlTarget) via DisplayImeController.
                    // Then, trigger onRequestedVisibleTypesChanged for the controlTarget with
                    // its new requested visibility for the IME
                    boolean imeVisible = caller.isRequestedVisible(WindowInsets.Type.ime());
                    if (controlTarget != null) {
                        ImeTracker.forLogging().onProgress(statsToken,
                                ImeTracker.PHASE_WM_SET_REMOTE_TARGET_IME_VISIBILITY);
                        controlTarget.setImeInputTargetRequestedVisibility(imeVisible);
                    } else if (caller instanceof InsetsControlTarget) {
                        // In case of a virtual display that cannot show the IME, the
                        // controlTarget will be null here, as no controlTarget was set yet. In
                        // that case, proceed similar to the multi window mode (fallback =
                        // RemoteInsetsControlTarget of the default display)
                        controlTarget = mDisplayContent.getImeHostOrFallback(
                                ((InsetsControlTarget) caller).getWindow());

                        if (controlTarget != caller) {
                            ImeTracker.forLogging().onProgress(statsToken,
                                    ImeTracker.PHASE_WM_SET_REMOTE_TARGET_IME_VISIBILITY);
                            controlTarget.setImeInputTargetRequestedVisibility(imeVisible);
                        } else {
                            ImeTracker.forLogging().onFailed(statsToken,
                                    ImeTracker.PHASE_WM_SET_REMOTE_TARGET_IME_VISIBILITY);
                        }
                    }

                    invokeOnImeRequestedChangedListener(caller, statsToken);
                } else {
                    // TODO(b/353463205) add ImeTracker?
                }
            }
            return false;
        }
        boolean changed = super.updateClientVisibility(caller, statsToken);
        if (!Flags.refactorInsetsController() && caller instanceof InsetsControlTarget) {
            if (changed && caller.isRequestedVisible(mSource.getType())) {
                reportImeDrawnForOrganizerIfNeeded((InsetsControlTarget) caller);
            }
        }
        changed |= mDisplayContent.onImeInsetsClientVisibilityUpdate();
        if (Flags.refactorInsetsController()) {
            if (changed) {
                invokeOnImeRequestedChangedListener(mDisplayContent.getImeInputTarget(),
                        statsToken);
            } else {
                // TODO(b/329229469) change phase and check cancelled / failed
                ImeTracker.forLogging().onCancelled(statsToken,
                        ImeTracker.PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES);
            }
        }
        return changed;
    }

    void onInputTargetChanged(InputTarget target) {
        if (Flags.refactorInsetsController() && target != null) {
            InsetsControlTarget imeControlTarget = getControlTarget();
            if (target != imeControlTarget) {
                // If the targetWin is not the imeControlTarget (=RemoteInsetsControlTarget) let it
                // know about the new requestedVisibleTypes for the IME.
                if (imeControlTarget != null) {
                    imeControlTarget.setImeInputTargetRequestedVisibility(
                            (target.getRequestedVisibleTypes() & WindowInsets.Type.ime()) != 0);
                }
            }
        }
    }

    // TODO(b/353463205) check callers to see if we can make statsToken @NonNull
    private void invokeOnImeRequestedChangedListener(InsetsTarget insetsTarget,
            @Nullable ImeTracker.Token statsToken) {
        final var imeListener = mDisplayContent.mWmService.mOnImeRequestedChangedListener;
        if (imeListener != null) {
            if (insetsTarget != null) {
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_WM_POSTING_CHANGED_IME_VISIBILITY);
                mDisplayContent.mWmService.mH.post(() -> {
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_WM_INVOKING_IME_REQUESTED_LISTENER);
                    imeListener.onImeRequestedChanged(insetsTarget.getWindowToken(),
                            insetsTarget.isRequestedVisible(WindowInsets.Type.ime()), statsToken);
                });
            } else {
                ImeTracker.forLogging().onFailed(statsToken,
                        ImeTracker.PHASE_WM_POSTING_CHANGED_IME_VISIBILITY);
            }
        } else {
            // TODO(b/353463205) We could combine the upper if's and remove the additional phase.
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_WM_DISPATCH_IME_REQUESTED_CHANGED);
        }
    }

    private void reportImeDrawnForOrganizerIfNeeded(@NonNull InsetsControlTarget caller) {
        final WindowState callerWindow = caller.getWindow();
        if (callerWindow == null) {
            return;
        }
        WindowToken imeToken = mWindowContainer.asWindowState() != null
                ? mWindowContainer.asWindowState().mToken : null;
        final var rotationController = mDisplayContent.getAsyncRotationController();
        if ((rotationController != null && rotationController.isTargetToken(imeToken))
                || (imeToken != null && imeToken.isSelfAnimating(
                        0 /* flags */, SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM))) {
            // Skip reporting IME drawn state when the control target is in fixed
            // rotation, AsyncRotationController will report after the animation finished.
            return;
        }
        reportImeDrawnForOrganizer(caller);
    }

    private void reportImeDrawnForOrganizer(@NonNull InsetsControlTarget caller) {
        final WindowState callerWindow = caller.getWindow();
        if (callerWindow == null || callerWindow.getTask() == null) {
            return;
        }
        if (callerWindow.getTask().isOrganized()) {
            mWindowContainer.mWmService.mAtmService.mTaskOrganizerController
                    .reportImeDrawnOnTask(caller.getWindow().getTask());
        }
    }

    /** Report the IME has drawn on the current IME control target for its task organizer */
    void reportImeDrawnForOrganizer() {
        final InsetsControlTarget imeControlTarget = getControlTarget();
        if (imeControlTarget != null) {
            reportImeDrawnForOrganizer(imeControlTarget);
        }
    }

    private void onSourceChanged() {
        if (mLastSource.equals(mSource)) {
            return;
        }
        mLastSource.set(mSource);
        mDisplayContent.mWmService.mH.obtainMessage(
                UPDATE_MULTI_WINDOW_STACKS, mDisplayContent).sendToTarget();
    }

    /**
     * Called from {@link WindowManagerInternal#showImePostLayout}
     * when {@link android.inputmethodservice.InputMethodService} requests to show IME
     * on the given control target.
     *
     * @param imeTarget  the control target on which the IME request is coming from.
     * @param statsToken the token tracking the current IME request.
     */
    void scheduleShowImePostLayout(@NonNull InsetsControlTarget imeTarget,
            @NonNull ImeTracker.Token statsToken) {
        if (mImeRequester == null) {
            // Start tracing only on initial scheduled show IME request, to record end-to-end time.
            Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, "WMS.showImePostLayout", 0);
        } else {
            // We already have a scheduled show IME request, cancel the previous statsToken and
            // continue with the new one.
            logIsScheduledAndReadyToShowIme(false /* aborted */);
            ImeTracker.forLogging().onCancelled(mStatsToken, ImeTracker.PHASE_WM_SHOW_IME_RUNNER);
        }
        final boolean targetChanged = isTargetChangedWithinActivity(imeTarget);
        mImeRequester = imeTarget;
        mStatsToken = statsToken;
        if (targetChanged) {
            // target changed, check if new target can show IME.
            ProtoLog.d(WM_DEBUG_IME, "IME target changed within ActivityRecord");
            checkAndStartShowImePostLayout();
            // if IME cannot be shown at this time, it is scheduled to be shown.
            // once window that called IMM.showSoftInput() and DisplayContent's ImeTarget match,
            // it will be shown.
            return;
        }

        ProtoLog.d(WM_DEBUG_IME, "Schedule IME show for %s", mImeRequester.getWindow() == null
                ? mImeRequester : mImeRequester.getWindow().getName());
        mDisplayContent.mWmService.requestTraversal();
    }

    /**
     * Checks whether there is a previously scheduled show IME request and we are ready to show,
     * in which case also start handling the request.
     */
    void checkAndStartShowImePostLayout() {
        if (!isScheduledAndReadyToShowIme()) {
            // This can later become ready, so we don't want to cancel the pending request here.
            return;
        }
        // TODO(b/329229469) check if this is still triggered, as we don't go into STATE_SHOW_IME
        //  (DefaultImeVisibilityApplier)
        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            // The IME is drawn, so call into {@link WindowState#notifyInsetsControlChanged}
            // if we have a leash
            if (mControl != null && mControl.getLeash() != null
                    && mControlTarget.getWindow() != null
                    && !mControlTarget.getWindow().mGivenInsetsPending) {
                int displayId = mDisplayContent.getDisplayId();
                mControlTarget.notifyInsetsControlChanged(displayId);
            }
            return;
        }

        ImeTracker.forLogging().onProgress(mStatsToken, ImeTracker.PHASE_WM_SHOW_IME_RUNNER);
        ProtoLog.d(WM_DEBUG_IME, "Run showImeRunner");

        final InsetsControlTarget target = getControlTarget();

        ProtoLog.i(WM_DEBUG_IME, "call showInsets(ime) on %s",
                target.getWindow() != null ? target.getWindow().getName() : "");
        setImeShowing(true);
        target.showInsets(WindowInsets.Type.ime(), true /* fromIme */, mStatsToken);
        Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, "WMS.showImePostLayout", 0);
        if (target != mImeRequester) {
            ProtoLog.w(WM_DEBUG_IME, "showInsets(ime) was requested by different window: %s ",
                    (mImeRequester.getWindow() != null ? mImeRequester.getWindow().getName() : ""));
        }
        resetShowImePostLayout();
    }

    /** Aborts the previously scheduled show IME request. */
    void abortShowImePostLayout() {
        if (mImeRequester == null) {
            return;
        }
        ProtoLog.d(WM_DEBUG_IME, "abortShowImePostLayout");
        Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, "WMS.showImePostLayout", 0);
        logIsScheduledAndReadyToShowIme(true /* aborted */);
        ImeTracker.forLogging().onFailed(
                mStatsToken, ImeTracker.PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT);
        resetShowImePostLayout();
    }

    /** Resets the state of the previously scheduled show IME request. */
    private void resetShowImePostLayout() {
        mImeRequester = null;
        mStatsToken = null;
    }

    /** Checks whether there is a previously scheduled show IME request and we are ready to show. */
    @VisibleForTesting
    boolean isScheduledAndReadyToShowIme() {
        // IMMS#mLastImeTargetWindow always considers focused window as
        // IME target, however DisplayContent#computeImeTarget() can compute
        // a different IME target.
        // Refer to WindowManagerService#applyImeVisibility(token, false).
        // If IMMS's imeTarget is child of DisplayContent's imeTarget and child window
        // is above the parent, we will consider it as the same target for now.
        // Also, if imeTarget is closing, it would be considered as outdated target.
        // TODO(b/139861270): Remove the child & sublayer check once IMMS is aware of
        //  actual IME target.
        if (mImeRequester == null) {
            // No show IME request previously scheduled.
            return false;
        }
        if (!mServerVisible || mFrozen) {
            // The window container is not available and considered visible.
            // If frozen, the server visibility is not set until unfrozen.
            return false;
        }
        if (mWindowContainer == null) {
            // No window container set.
            return false;
        }
        final WindowState windowState = mWindowContainer.asWindowState();
        if (windowState == null) {
            throw new IllegalArgumentException("IME insets must be provided by a window.");
        }
        if (!windowState.isDrawn() || windowState.mGivenInsetsPending) {
            // The window is not drawn, or it has pending insets.
            return false;
        }
        final InsetsControlTarget dcTarget = mDisplayContent.getImeTarget(IME_TARGET_LAYERING);
        if (dcTarget == null) {
            // No IME layering target.
            return false;
        }
        final InsetsControlTarget controlTarget = getControlTarget();
        if (controlTarget == null) {
            // No IME control target.
            return false;
        }
        if (controlTarget != mDisplayContent.getImeTarget(IME_TARGET_CONTROL)) {
            // The control target does not match the one in DisplayContent.
            return false;
        }
        if (mStateController.hasPendingControls(controlTarget)) {
            // The control target has pending controls.
            return false;
        }
        if (getLeash(controlTarget) == null) {
            // The control target has no source control leash (or it is not ready for dispatching).
            return false;
        }

        ProtoLog.d(WM_DEBUG_IME, "dcTarget: %s mImeRequester: %s",
                dcTarget.getWindow().getName(), mImeRequester.getWindow() == null
                        ? mImeRequester : mImeRequester.getWindow().getName());

        return isImeLayeringTarget(mImeRequester, dcTarget)
                || isAboveImeLayeringTarget(mImeRequester, dcTarget)
                || isImeFallbackTarget(mImeRequester)
                || isImeInputTarget(mImeRequester)
                || sameAsImeControlTarget(mImeRequester);
    }

    /**
     * Sets the statsToken before the IMS was shown/hidden.
     * @param visible {@code true} to make it visible, false to hide it.
     * @param statsToken the token tracking the current IME request.
     */
    void receiveImeStatsToken(boolean visible,
            @NonNull ImeTracker.Token statsToken) {
        if (!android.view.inputmethod.Flags.refactorInsetsController()) {
            return;
        }

        if (mStatsToken != null) {
            // We have an ongoing show request will be cancelled by the newly received show
            // request (cancelling the initial show) or hide request (aborting the initial show).
            logIsScheduledAndReadyToShowIme(!visible /* aborted */);
        }
        if (visible) {
            ImeTracker.forLogging().onCancelled(
                    mStatsToken, ImeTracker.PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT);
            mStatsToken = statsToken;
        } else {
            ImeTracker.forLogging().onFailed(
                    mStatsToken, ImeTracker.PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT);
            mStatsToken = null;
        }
    }

    /**
     * Logs the current state that can be checked by {@link #isScheduledAndReadyToShowIme}.
     *
     * @param aborted whether the scheduled show IME request was aborted or cancelled.
     */
    private void logIsScheduledAndReadyToShowIme(boolean aborted) {
        final var windowState = mWindowContainer != null ? mWindowContainer.asWindowState() : null;
        final var dcTarget = mDisplayContent.getImeTarget(IME_TARGET_LAYERING);
        final var controlTarget = getControlTarget();
        final var sb = new StringBuilder();
        sb.append("showImePostLayout ").append(aborted ? "aborted" : "cancelled");
        sb.append(", isScheduledAndReadyToShowIme: ").append(isScheduledAndReadyToShowIme());
        sb.append(", mImeRequester: ").append(mImeRequester);
        sb.append(", serverVisible: ").append(mServerVisible);
        sb.append(", frozen: ").append(mFrozen);
        sb.append(", mWindowContainer is: ").append(mWindowContainer != null ? "non-null" : "null");
        sb.append(", windowState: ").append(windowState);
        if (windowState != null) {
            sb.append(", isDrawn: ").append(windowState.isDrawn());
            sb.append(", mGivenInsetsPending: ").append(windowState.mGivenInsetsPending);
        }
        sb.append(", dcTarget: ").append(dcTarget);
        sb.append(", controlTarget: ").append(controlTarget);
        if (mImeRequester != null && dcTarget != null && controlTarget != null) {
            sb.append("\n");
            sb.append("controlTarget == DisplayContent.controlTarget: ");
            sb.append(controlTarget == mDisplayContent.getImeTarget(IME_TARGET_CONTROL));
            sb.append(", hasPendingControls: ");
            sb.append(mStateController.hasPendingControls(controlTarget));
            final boolean hasLeash = getLeash(controlTarget) != null;
            sb.append(", leash is: ").append(hasLeash ? "non-null" : "null");
            if (!hasLeash) {
                sb.append(", control is: ").append(mControl != null ? "non-null" : "null");
                sb.append(", mIsLeashInitialized: ").append(mIsLeashInitialized);
            }
            sb.append(", isImeLayeringTarget: ");
            sb.append(isImeLayeringTarget(mImeRequester, dcTarget));
            sb.append(", isAboveImeLayeringTarget: ");
            sb.append(isAboveImeLayeringTarget(mImeRequester, dcTarget));
            sb.append(", isImeFallbackTarget: ");
            sb.append(isImeFallbackTarget(mImeRequester));
            sb.append(", isImeInputTarget: ");
            sb.append(isImeInputTarget(mImeRequester));
            sb.append(", sameAsImeControlTarget: ");
            sb.append(sameAsImeControlTarget(mImeRequester));
        }
        Slog.d(TAG, sb.toString());
    }

    // ---------------------------------------------------------------------------------------
    // Methods for checking IME insets target changing state.
    //
    private static boolean isImeLayeringTarget(@NonNull InsetsControlTarget target,
            @NonNull InsetsControlTarget dcTarget) {
        return !isImeTargetWindowClosing(dcTarget.getWindow()) && target == dcTarget;
    }

    private static boolean isAboveImeLayeringTarget(@NonNull InsetsControlTarget target,
            @NonNull InsetsControlTarget dcTarget) {
        return target.getWindow() != null
                && dcTarget.getWindow().getParentWindow() == target
                && dcTarget.getWindow().mSubLayer > target.getWindow().mSubLayer;
    }

    private boolean isImeFallbackTarget(@NonNull InsetsControlTarget target) {
        return target == mDisplayContent.getImeFallback();
    }

    private boolean isImeInputTarget(@NonNull InsetsTarget target) {
        return target == mDisplayContent.getImeInputTarget();
    }

    private boolean sameAsImeControlTarget(@NonNull InsetsControlTarget target) {
        final InsetsControlTarget controlTarget = getControlTarget();
        return controlTarget == target
                && (target.getWindow() == null || !isImeTargetWindowClosing(target.getWindow()));
    }

    private static boolean isImeTargetWindowClosing(@NonNull WindowState win) {
        return win.mAnimatingExit || win.mActivityRecord != null
                && (win.mActivityRecord.isInTransition()
                    && !win.mActivityRecord.isVisibleRequested()
                    || win.mActivityRecord.willCloseOrEnterPip());
    }

    private boolean isTargetChangedWithinActivity(@NonNull InsetsControlTarget target) {
        // We don't consider the target out of the activity.
        if (target.getWindow() == null) {
            return false;
        }
        return mImeRequester != target
                && mImeRequester != null
                && mImeRequester.getWindow() != null
                && mImeRequester.getWindow().mActivityRecord == target.getWindow().mActivityRecord;
    }
    // ---------------------------------------------------------------------------------------

    @Override
    public void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        prefix = prefix + "  ";
        pw.print(prefix);
        pw.print("mImeShowing=");
        pw.print(mImeShowing);
        if (mImeRequester != null) {
            pw.print(prefix);
            pw.print("showImePostLayout pending for mImeRequester=");
            pw.print(mImeRequester);
            pw.println();
        } else {
            pw.print(prefix);
            pw.print("showImePostLayout not scheduled, mImeRequester=null");
            pw.println();
        }
        pw.println();
    }

    @Override
    void dumpDebug(ProtoOutputStream proto, long fieldId, @WindowTracingLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        super.dumpDebug(proto, INSETS_SOURCE_PROVIDER, logLevel);
        final WindowState imeRequesterWindow =
                mImeRequester != null ? mImeRequester.getWindow() : null;
        if (imeRequesterWindow != null) {
            imeRequesterWindow.dumpDebug(proto, IME_TARGET_FROM_IME, logLevel);
        }
        proto.end(token);
    }

    /**
     * Sets whether the IME is currently supposed to be showing according to
     * InputMethodManagerService.
     */
    public void setImeShowing(boolean imeShowing) {
        mImeShowing = imeShowing;
    }

    /**
     * Returns whether the IME is currently supposed to be showing according to
     * InputMethodManagerService.
     */
    public boolean isImeShowing() {
        return mImeShowing;
    }
}
