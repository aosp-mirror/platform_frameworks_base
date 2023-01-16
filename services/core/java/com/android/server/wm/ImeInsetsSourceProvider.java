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
import static android.view.InsetsState.ITYPE_IME;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.ImeInsetsSourceProviderProto.IME_TARGET_FROM_IME;
import static com.android.server.wm.ImeInsetsSourceProviderProto.INSETS_SOURCE_PROVIDER;
import static com.android.server.wm.ImeInsetsSourceProviderProto.IS_IME_LAYOUT_DRAWN;
import static com.android.server.wm.WindowManagerService.H.UPDATE_MULTI_WINDOW_STACKS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Trace;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceConsumer;
import android.view.InsetsSourceControl;
import android.view.WindowInsets;
import android.view.inputmethod.ImeTracker;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;

/**
 * Controller for IME inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
final class ImeInsetsSourceProvider extends WindowContainerInsetsSourceProvider {

    /** The token tracking the current IME request or {@code null} otherwise. */
    @Nullable
    private ImeTracker.Token mImeRequesterStatsToken;
    private InsetsControlTarget mImeRequester;
    private Runnable mShowImeRunner;
    private boolean mIsImeLayoutDrawn;
    private boolean mImeShowing;
    private final InsetsSource mLastSource = new InsetsSource(ITYPE_IME, WindowInsets.Type.ime());

    /** @see #setFrozen(boolean) */
    private boolean mFrozen;

    /** @see #setServerVisible(boolean) */
    private boolean mServerVisible;

    ImeInsetsSourceProvider(InsetsSource source,
            InsetsStateController stateController, DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }

    @Override
    InsetsSourceControl getControl(InsetsControlTarget target) {
        final InsetsSourceControl control = super.getControl(target);
        if (control != null && target != null && target.getWindow() != null) {
            final WindowState targetWin = target.getWindow();
            // If the control target changes during the app transition with the task snapshot
            // starting window and the IME snapshot is visible, in case not have duplicated IME
            // showing animation during transitioning, use a flag to inform IME source control to
            // skip showing animation once.
            final TaskSnapshot snapshot = targetWin.getRootTask() != null
                    ? targetWin.mWmService.getTaskSnapshot(targetWin.getRootTask().mTaskId,
                        0 /* userId */, false /* isLowResolution */, false /* restoreFromDisk */)
                    : null;
            control.setSkipAnimationOnce(targetWin.mActivityRecord != null
                    && targetWin.mActivityRecord.hasStartingWindow()
                    && snapshot != null && snapshot.hasImeSurface());
        }
        return control;
    }

    @Override
    void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        if (!mFrozen) {
            super.setServerVisible(serverVisible);
        }
    }

    /**
     * Freeze IME insets source state when required.
     *
     * When setting {@param frozen} as {@code true}, the IME insets provider will freeze the
     * current IME insets state and pending the IME insets state update until setting
     * {@param frozen} as {@code false}.
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
        super.updateVisibility();
        onSourceChanged();
    }

    @Override
    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force) {
        if (target != null && target.getWindow() != null) {
            // ime control target could be a different window.
            // Refer WindowState#getImeControlTarget().
            target = target.getWindow().getImeControlTarget();
        }
        super.updateControlForTarget(target, force);
    }

    @Override
    protected boolean updateClientVisibility(InsetsControlTarget caller) {
        if (caller != getControlTarget()) {
            return false;
        }
        boolean changed = super.updateClientVisibility(caller);
        if (changed && caller.isRequestedVisible(mSource.getType())) {
            reportImeDrawnForOrganizer(caller);
        }
        changed |= mDisplayContent.onImeInsetsClientVisibilityUpdate();
        return changed;
    }

    private void reportImeDrawnForOrganizer(InsetsControlTarget caller) {
        if (caller.getWindow() != null && caller.getWindow().getTask() != null) {
            if (caller.getWindow().getTask().isOrganized()) {
                mWindowContainer.mWmService.mAtmService.mTaskOrganizerController
                        .reportImeDrawnOnTask(caller.getWindow().getTask());
            }
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
     * on {@param imeTarget}.
     *
     * @param imeTarget imeTarget on which IME show request is coming from.
     * @param statsToken the token tracking the current IME show request or {@code null} otherwise.
     */
    void scheduleShowImePostLayout(InsetsControlTarget imeTarget,
            @Nullable ImeTracker.Token statsToken) {
        boolean targetChanged = isTargetChangedWithinActivity(imeTarget);
        mImeRequester = imeTarget;
        // There was still a stats token, so that request presumably failed.
        ImeTracker.forLogging().onFailed(
                mImeRequesterStatsToken, ImeTracker.PHASE_WM_SHOW_IME_RUNNER);
        mImeRequesterStatsToken = statsToken;
        if (targetChanged) {
            // target changed, check if new target can show IME.
            ProtoLog.d(WM_DEBUG_IME, "IME target changed within ActivityRecord");
            checkShowImePostLayout();
            // if IME cannot be shown at this time, it is scheduled to be shown.
            // once window that called IMM.showSoftInput() and DisplayContent's ImeTarget match,
            // it will be shown.
            return;
        }

        ProtoLog.d(WM_DEBUG_IME, "Schedule IME show for %s", mImeRequester.getWindow() == null
                ? mImeRequester : mImeRequester.getWindow().getName());
        mShowImeRunner = () -> {
            ImeTracker.forLogging().onProgress(mImeRequesterStatsToken,
                    ImeTracker.PHASE_WM_SHOW_IME_RUNNER);
            ProtoLog.d(WM_DEBUG_IME, "Run showImeRunner");
            // Target should still be the same.
            if (isReadyToShowIme()) {
                ImeTracker.forLogging().onProgress(mImeRequesterStatsToken,
                        ImeTracker.PHASE_WM_SHOW_IME_READY);
                final InsetsControlTarget target = mDisplayContent.getImeTarget(IME_TARGET_CONTROL);

                ProtoLog.i(WM_DEBUG_IME, "call showInsets(ime) on %s",
                        target.getWindow() != null ? target.getWindow().getName() : "");
                setImeShowing(true);
                target.showInsets(WindowInsets.Type.ime(), true /* fromIme */,
                        mImeRequesterStatsToken);
                Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, "WMS.showImePostLayout", 0);
                if (target != mImeRequester && mImeRequester != null) {
                    ProtoLog.w(WM_DEBUG_IME,
                            "showInsets(ime) was requested by different window: %s ",
                            (mImeRequester.getWindow() != null
                                    ? mImeRequester.getWindow().getName() : ""));
                }
            } else {
                ImeTracker.forLogging().onFailed(mImeRequesterStatsToken,
                        ImeTracker.PHASE_WM_SHOW_IME_READY);
            }
            // Clear token here so we don't report an error in abortShowImePostLayout().
            mImeRequesterStatsToken = null;
            abortShowImePostLayout();
        };
        mDisplayContent.mWmService.requestTraversal();
    }

    void checkShowImePostLayout() {
        if (mWindowContainer == null) {
            return;
        }
        WindowState windowState =  mWindowContainer.asWindowState();
        if (windowState == null) {
            throw new IllegalArgumentException("IME insets must be provided by a window.");
        }
        // check if IME is drawn
        if (mIsImeLayoutDrawn
                || (isReadyToShowIme()
                && windowState.isDrawn()
                && !windowState.mGivenInsetsPending)) {
            mIsImeLayoutDrawn = true;
            // show IME if InputMethodService requested it to be shown.
            if (mShowImeRunner != null) {
                mShowImeRunner.run();
            }
        }
    }

    /**
     * Abort any pending request to show IME post layout.
     */
    void abortShowImePostLayout() {
        ProtoLog.d(WM_DEBUG_IME, "abortShowImePostLayout");
        mImeRequester = null;
        mIsImeLayoutDrawn = false;
        mShowImeRunner = null;
        ImeTracker.forLogging().onCancelled(
                mImeRequesterStatsToken, ImeTracker.PHASE_WM_SHOW_IME_RUNNER);
        mImeRequesterStatsToken = null;
    }

    @VisibleForTesting
    boolean isReadyToShowIme() {
        // IMMS#mLastImeTargetWindow always considers focused window as
        // IME target, however DisplayContent#computeImeTarget() can compute
        // a different IME target.
        // Refer to WindowManagerService#applyImeVisibility(token, false).
        // If IMMS's imeTarget is child of DisplayContent's imeTarget and child window
        // is above the parent, we will consider it as the same target for now.
        // Also, if imeTarget is closing, it would be considered as outdated target.
        // TODO(b/139861270): Remove the child & sublayer check once IMMS is aware of
        //  actual IME target.
        final InsetsControlTarget dcTarget = mDisplayContent.getImeTarget(IME_TARGET_LAYERING);
        if (dcTarget == null || mImeRequester == null) {
            return false;
        }
        // Not ready to show if there is no IME control target.
        final InsetsControlTarget controlTarget = mDisplayContent.getImeTarget(IME_TARGET_CONTROL);
        if (controlTarget == null) {
            return false;
        }

        ProtoLog.d(WM_DEBUG_IME, "dcTarget: %s mImeRequester: %s",
                dcTarget.getWindow().getName(), mImeRequester.getWindow() == null
                        ? mImeRequester : mImeRequester.getWindow().getName());

        return isImeLayeringTarget(mImeRequester, dcTarget)
                || isAboveImeLayeringTarget(mImeRequester, dcTarget)
                || isImeFallbackTarget(mImeRequester)
                || isImeInputTarget(mImeRequester)
                || sameAsImeControlTarget();
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

    private boolean isImeFallbackTarget(InsetsControlTarget target) {
        return target == mDisplayContent.getImeFallback();
    }

    private boolean isImeInputTarget(InsetsControlTarget target) {
        return target == mDisplayContent.getImeInputTarget();
    }

    private boolean sameAsImeControlTarget() {
        final InsetsControlTarget target = mDisplayContent.getImeTarget(IME_TARGET_CONTROL);
        return target == mImeRequester
                && (mImeRequester.getWindow() == null
                || !isImeTargetWindowClosing(mImeRequester.getWindow()));
    }

    private static boolean isImeTargetWindowClosing(@NonNull WindowState win) {
        return win.mAnimatingExit || win.mActivityRecord != null
                && (win.mActivityRecord.isInTransition()
                    && !win.mActivityRecord.isVisibleRequested()
                    || win.mActivityRecord.willCloseOrEnterPip());
    }

    private boolean isTargetChangedWithinActivity(InsetsControlTarget target) {
        // We don't consider the target out of the activity.
        if (target == null || target.getWindow() == null) {
            return false;
        }
        return mImeRequester != target
                && mImeRequester != null && mShowImeRunner != null
                && mImeRequester.getWindow() != null
                && mImeRequester.getWindow().mActivityRecord
                == target.getWindow().mActivityRecord;
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
        }
        pw.println();
    }

    @Override
    void dumpDebug(ProtoOutputStream proto, long fieldId, @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        super.dumpDebug(proto, INSETS_SOURCE_PROVIDER, logLevel);
        final WindowState imeRequesterWindow =
                mImeRequester != null ? mImeRequester.getWindow() : null;
        if (imeRequesterWindow != null) {
            imeRequesterWindow.dumpDebug(proto, IME_TARGET_FROM_IME, logLevel);
        }
        proto.write(IS_IME_LAYOUT_DRAWN, mIsImeLayoutDrawn);
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
