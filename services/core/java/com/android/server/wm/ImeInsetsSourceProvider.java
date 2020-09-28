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

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_IME;

import android.view.InsetsSource;
import android.view.WindowInsets;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.protolog.common.ProtoLog;

import java.io.PrintWriter;

/**
 * Controller for IME inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
class ImeInsetsSourceProvider extends InsetsSourceProvider {

    private InsetsControlTarget mImeTargetFromIme;
    private Runnable mShowImeRunner;
    private boolean mIsImeLayoutDrawn;

    ImeInsetsSourceProvider(InsetsSource source,
            InsetsStateController stateController, DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }

    /**
     * Called from {@link WindowManagerInternal#showImePostLayout} when {@link InputMethodService}
     * requests to show IME on {@param imeTarget}.
     *
     * @param imeTarget imeTarget on which IME request is coming from.
     */
    void scheduleShowImePostLayout(InsetsControlTarget imeTarget) {
        boolean targetChanged = mImeTargetFromIme != imeTarget
                && mImeTargetFromIme != null && imeTarget != null && mShowImeRunner != null
                && imeTarget.getWindow() != null && mImeTargetFromIme.getWindow() != null
                && mImeTargetFromIme.getWindow().mActivityRecord
                        == imeTarget.getWindow().mActivityRecord;
        mImeTargetFromIme = imeTarget;
        if (targetChanged) {
            // target changed, check if new target can show IME.
            ProtoLog.d(WM_DEBUG_IME, "IME target changed within ActivityRecord");
            checkShowImePostLayout();
            // if IME cannot be shown at this time, it is scheduled to be shown.
            // once window that called IMM.showSoftInput() and DisplayContent's ImeTarget match,
            // it will be shown.
            return;
        }

        ProtoLog.d(WM_DEBUG_IME, "Schedule IME show for %s", mImeTargetFromIme.getWindow() == null
                ? mImeTargetFromIme : mImeTargetFromIme.getWindow().getName());
        mShowImeRunner = () -> {
            ProtoLog.d(WM_DEBUG_IME, "Run showImeRunner");
            // Target should still be the same.
            if (isImeTargetFromDisplayContentAndImeSame()) {
                final InsetsControlTarget target = mDisplayContent.mInputMethodControlTarget;

                ProtoLog.i(WM_DEBUG_IME, "call showInsets(ime) on %s",
                        target.getWindow() != null ? target.getWindow().getName() : "");
                target.showInsets(WindowInsets.Type.ime(), true /* fromIme */);
                if (target != mImeTargetFromIme && mImeTargetFromIme != null) {
                    ProtoLog.w(WM_DEBUG_IME,
                            "showInsets(ime) was requested by different window: %s ",
                            (mImeTargetFromIme.getWindow() != null
                                    ? mImeTargetFromIme.getWindow().getName() : ""));
                }
            }
            abortShowImePostLayout();
        };
        mDisplayContent.mWmService.requestTraversal();
    }

    void checkShowImePostLayout() {
        // check if IME is drawn
        if (mIsImeLayoutDrawn
                || (mImeTargetFromIme != null
                && isImeTargetFromDisplayContentAndImeSame()
                && mWin != null
                && mWin.isDrawnLw()
                && !mWin.mGivenInsetsPending)) {
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
        mImeTargetFromIme = null;
        mIsImeLayoutDrawn = false;
        mShowImeRunner = null;
    }

    @VisibleForTesting
    boolean isImeTargetFromDisplayContentAndImeSame() {
        // IMMS#mLastImeTargetWindow always considers focused window as
        // IME target, however DisplayContent#computeImeTarget() can compute
        // a different IME target.
        // Refer to WindowManagerService#applyImeVisibility(token, false).
        // If IMMS's imeTarget is child of DisplayContent's imeTarget and child window
        // is above the parent, we will consider it as the same target for now.
        // Also, if imeTarget is closing, it would be considered as outdated target.
        // TODO(b/139861270): Remove the child & sublayer check once IMMS is aware of
        //  actual IME target.
        final WindowState dcTarget = mDisplayContent.mInputMethodTarget;
        final InsetsControlTarget controlTarget = mDisplayContent.mInputMethodControlTarget;
        if (dcTarget == null || mImeTargetFromIme == null) {
            return false;
        }
        ProtoLog.d(WM_DEBUG_IME, "dcTarget: %s mImeTargetFromIme: %s",
                dcTarget.getName(), mImeTargetFromIme.getWindow() == null
                        ? mImeTargetFromIme : mImeTargetFromIme.getWindow().getName());

        return (!dcTarget.isClosing() && mImeTargetFromIme == dcTarget)
                || (mImeTargetFromIme != null && mImeTargetFromIme.getWindow() != null
                        && dcTarget.getParentWindow() == mImeTargetFromIme
                        && dcTarget.mSubLayer > mImeTargetFromIme.getWindow().mSubLayer)
                || mImeTargetFromIme == mDisplayContent.getImeFallback()
                || mImeTargetFromIme == mDisplayContent.mInputMethodInputTarget
                || controlTarget == mImeTargetFromIme
                        && (mImeTargetFromIme.getWindow() == null
                                || !mImeTargetFromIme.getWindow().isClosing());
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (mImeTargetFromIme != null) {
            pw.print(prefix);
            pw.print("showImePostLayout pending for mImeTargetFromIme=");
            pw.print(mImeTargetFromIme);
            pw.println();
        }
    }
}
