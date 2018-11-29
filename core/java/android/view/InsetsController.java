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

package android.view;

import android.graphics.Rect;

import java.io.PrintWriter;

/**
 * Implements {@link WindowInsetsController} on the client.
 */
class InsetsController {

    private final InsetsState mState = new InsetsState();
    private final Rect mFrame = new Rect();

    void onFrameChanged(Rect frame) {
        mFrame.set(frame);
    }

    public InsetsState getState() {
        return mState;
    }

    public void setState(InsetsState state) {
        mState.set(state);
    }

    /**
     * @see InsetsState#calculateInsets
     */
    WindowInsets calculateInsets(boolean isScreenRound,
            boolean alwaysConsumeNavBar, DisplayCutout cutout) {
        return mState.calculateInsets(mFrame, isScreenRound, alwaysConsumeNavBar, cutout);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix); pw.println("InsetsController:");
        mState.dump(prefix + "  ", pw);
    }
}
