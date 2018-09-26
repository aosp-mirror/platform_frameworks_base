/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.InsetsSource;

import com.android.internal.util.function.TriConsumer;
import com.android.server.policy.WindowManagerPolicy;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
class InsetsSourceProvider {

    private final Rect mTmpRect = new Rect();
    private final @NonNull InsetsSource mSource;
    private WindowState mWin;
    private TriConsumer<DisplayFrames, WindowState, Rect> mFrameProvider;

    InsetsSourceProvider(InsetsSource source) {
        mSource = source;
    }

    InsetsSource getSource() {
        return mSource;
    }

    /**
     * Updates the window that currently backs this source.
     *
     * @param win The window that links to this source.
     * @param frameProvider Based on display frame state and the window, calculates the resulting
     *                      frame that should be reported to clients.
     */
    void setWindow(@Nullable WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider) {
        if (mWin != null) {
            mWin.setInsetProvider(null);
        }
        mWin = win;
        mFrameProvider = frameProvider;
        if (win == null) {
            mSource.setVisible(false);
            mSource.setFrame(new Rect());
        } else {
            mSource.setVisible(true);
            mWin.setInsetProvider(this);
        }
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        if (mWin == null) {
            return;
        }

        mTmpRect.set(mWin.getFrameLw());
        if (mFrameProvider != null) {
            mFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin, mTmpRect);
        } else {
            mTmpRect.inset(mWin.mGivenContentInsets);
        }
        mSource.setFrame(mTmpRect);
        mSource.setVisible(mWin.isVisible() && !mWin.mGivenInsetsPending);

    }
}
