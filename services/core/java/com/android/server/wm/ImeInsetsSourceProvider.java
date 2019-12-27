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

import android.view.InsetsSource;
import android.view.WindowInsets;

/**
 * Controller for IME inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
class ImeInsetsSourceProvider extends InsetsSourceProvider {

    private WindowState mImeTargetFromIme;
    private Runnable mShowImeRunner;
    private boolean mIsImeLayoutDrawn;

    ImeInsetsSourceProvider(InsetsSource source,
            InsetsStateController stateController, DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        super.onPostLayout();

        if (mImeTargetFromIme != null
                && isImeTargetFromDisplayContentAndImeSame()
                && mWin != null
                && mWin.isDrawnLw()
                && !mWin.mGivenInsetsPending) {
            mIsImeLayoutDrawn = true;
        }
    }

    /**
     * Called when Insets have been dispatched to client.
     */
    void onPostInsetsDispatched() {
        if (mIsImeLayoutDrawn && mShowImeRunner != null) {
            // Show IME if InputMethodService requested to be shown and it's layout has finished.
            mShowImeRunner.run();
        }
    }

    /**
     * Called from {@link WindowManagerInternal#showImePostLayout} when {@link InputMethodService}
     * requests to show IME on {@param imeTarget}.
     * @param imeTarget imeTarget on which IME request is coming from.
     */
    void scheduleShowImePostLayout(WindowState imeTarget) {
        mImeTargetFromIme = imeTarget;
        mShowImeRunner = () -> {
            // Target should still be the same.
            if (isImeTargetFromDisplayContentAndImeSame()) {
                mDisplayContent.mInputMethodTarget.showInsets(
                        WindowInsets.Type.ime(), true /* fromIme */);
            }
            abortShowImePostLayout();
        };
    }

    /**
     * Abort any pending request to show IME post layout.
     */
    void abortShowImePostLayout() {
        mImeTargetFromIme = null;
        mIsImeLayoutDrawn = false;
        mShowImeRunner = null;
    }

    private boolean isImeTargetFromDisplayContentAndImeSame() {
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
        if (dcTarget == null) {
            return false;
        }
        return (!dcTarget.isClosing() && mImeTargetFromIme == dcTarget)
                || (mImeTargetFromIme != null && dcTarget.getParentWindow() == mImeTargetFromIme
                        && dcTarget.mSubLayer > mImeTargetFromIme.mSubLayer);
    }

}
