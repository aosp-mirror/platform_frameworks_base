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

import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;

import android.util.ArrayMap;
import android.view.InsetsState;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Manages global window inset state in the system represented by {@link InsetsState}.
 */
class InsetsStateController {

    private final InsetsState mLastState = new InsetsState();
    private final InsetsState mState = new InsetsState();
    private final DisplayContent mDisplayContent;
    private ArrayMap<Integer, InsetsSourceProvider> mControllers = new ArrayMap<>();

    private final Consumer<WindowState> mDispatchInsetsChanged = w -> {
        if (w.isVisible()) {
            w.notifyInsetsChanged();
        }
    };

    InsetsStateController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /**
     * When dispatching window state to the client, we'll need to exclude the source that represents
     * the window that is being dispatched.
     *
     * @param target The client we dispatch the state to.
     * @return The state stripped of the necessary information.
     */
    InsetsState getInsetsForDispatch(WindowState target) {
        final InsetsSourceProvider provider = target.getInsetProvider();
        if (provider == null) {
            return mState;
        }

        final InsetsState state = new InsetsState();
        state.set(mState);
        final int type = provider.getSource().getType();
        state.removeSource(type);

        // Navigation bar doesn't get influenced by anything else
        if (type == TYPE_NAVIGATION_BAR) {
            state.removeSource(TYPE_IME);
            state.removeSource(TYPE_TOP_BAR);
        }
        return state;
    }

    /**
     * @return The provider of a specific type.
     */
    InsetsSourceProvider getSourceProvider(int type) {
        return mControllers.computeIfAbsent(type,
                key -> new InsetsSourceProvider(mState.getSource(key)));
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        for (int i = mControllers.size() - 1; i>= 0; i--) {
            mControllers.valueAt(i).onPostLayout();
        }
        if (!mLastState.equals(mState)) {
            mLastState.set(mState, true /* copySources */);
            notifyInsetsChanged();
        }
    }

    private void notifyInsetsChanged() {
        mDisplayContent.forAllWindows(mDispatchInsetsChanged, true /* traverseTopToBottom */);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "WindowInsetsStateController");
        mState.dump(prefix + "  ", pw);
    }
}
