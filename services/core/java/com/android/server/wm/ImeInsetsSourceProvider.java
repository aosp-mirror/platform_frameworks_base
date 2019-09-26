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

    private WindowState mCurImeTarget;
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

        if (mCurImeTarget != null
                && mCurImeTarget == mDisplayContent.mInputMethodTarget
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
            mIsImeLayoutDrawn = false;
            mShowImeRunner = null;
        }
    }

    /**
     * Called from {@link WindowManagerInternal#showImePostLayout} when {@link InputMethodService}
     * requests to show IME on {@param imeTarget}.
     * @param imeTarget imeTarget on which IME is displayed.
     */
    void scheduleShowImePostLayout(WindowState imeTarget) {
        mCurImeTarget = imeTarget;
        mShowImeRunner = () -> {
            // Target should still be the same.
            if (mCurImeTarget == mDisplayContent.mInputMethodTarget) {
                mDisplayContent.mInputMethodTarget.showInsets(
                        WindowInsets.Type.ime(), true /* fromIme */);
            }
            mCurImeTarget = null;
        };
    }

}
