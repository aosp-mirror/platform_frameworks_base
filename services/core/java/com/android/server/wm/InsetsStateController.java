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
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.ViewRootImpl.sNewInsetsMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.ViewRootImpl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Manages global window inset state in the system represented by {@link InsetsState}.
 */
class InsetsStateController {

    private final InsetsState mLastState = new InsetsState();
    private final InsetsState mState = new InsetsState();
    private final DisplayContent mDisplayContent;

    private final ArrayMap<Integer, InsetsSourceProvider> mControllers = new ArrayMap<>();
    private final ArrayMap<WindowState, ArrayList<Integer>> mWinControlTypeMap = new ArrayMap<>();
    private final SparseArray<WindowState> mTypeWinControlMap = new SparseArray<>();
    private final ArraySet<WindowState> mPendingControlChanged = new ArraySet<>();

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

    @Nullable InsetsSourceControl[] getControlsForDispatch(WindowState target) {
        ArrayList<Integer> controlled = mWinControlTypeMap.get(target);
        if (controlled == null) {
            return null;
        }
        final int size = controlled.size();
        final InsetsSourceControl[] result = new InsetsSourceControl[size];
        for (int i = 0; i < size; i++) {
            result[i] = mControllers.get(controlled.get(i)).getControl();
        }
        return result;
    }

    /**
     * @return The provider of a specific type.
     */
    InsetsSourceProvider getSourceProvider(int type) {
        return mControllers.computeIfAbsent(type,
                key -> new InsetsSourceProvider(mState.getSource(key), this, mDisplayContent));
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        mState.setDisplayFrame(mDisplayContent.getBounds());
        for (int i = mControllers.size() - 1; i>= 0; i--) {
            mControllers.valueAt(i).onPostLayout();
        }
        if (!mLastState.equals(mState)) {
            mLastState.set(mState, true /* copySources */);
            notifyInsetsChanged();
        }
    }

    void onInsetsModified(WindowState windowState, InsetsState state) {
        boolean changed = false;
        for (int i = state.getSourcesCount() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            final InsetsSourceProvider provider = mControllers.get(source.getType());
            if (provider == null) {
                continue;
            }
            changed |= provider.onInsetsModified(windowState, source);
        }
        if (changed) {
            notifyInsetsChanged();
        }
    }

    void onImeTargetChanged(@Nullable WindowState imeTarget) {
        onControlChanged(TYPE_IME, imeTarget);
        notifyPendingInsetsControlChanged();
    }

    /**
     * Called when the top opaque fullscreen window that is able to control the system bars changes.
     *
     * @param controllingWindow The window that is now able to control the system bars appearance
     *                          and visibility.
     */
    void onBarControllingWindowChanged(@Nullable WindowState controllingWindow) {
        // TODO: Apply policy that determines whether controllingWindow is able to control system
        // bars

        // TODO: Depending on the form factor, mapping is different
        onControlChanged(TYPE_TOP_BAR, controllingWindow);
        onControlChanged(TYPE_NAVIGATION_BAR, controllingWindow);
        notifyPendingInsetsControlChanged();
    }

    void notifyControlRevoked(@NonNull WindowState previousControllingWin,
            InsetsSourceProvider provider) {
        removeFromControlMaps(previousControllingWin, provider.getSource().getType());
    }

    private void onControlChanged(int type, @Nullable WindowState win) {
        final WindowState previous = mTypeWinControlMap.get(type);
        if (win == previous) {
            return;
        }
        final InsetsSourceProvider controller = getSourceProvider(type);
        if (controller == null) {
            return;
        }
        if (!controller.isControllable()) {
            return;
        }
        controller.updateControlForTarget(win, false /* force */);
        if (previous != null) {
            removeFromControlMaps(previous, type);
            mPendingControlChanged.add(previous);
        }
        if (win != null) {
            addToControlMaps(win, type);
            mPendingControlChanged.add(win);
        }
    }

    private void removeFromControlMaps(@NonNull WindowState win, int type) {
        final ArrayList<Integer> array = mWinControlTypeMap.get(win);
        if (array == null) {
            return;
        }
        array.remove((Integer) type);
        if (array.isEmpty()) {
            mWinControlTypeMap.remove(win);
        }
        mTypeWinControlMap.remove(type);
    }

    private void addToControlMaps(@NonNull WindowState win, int type) {
        final ArrayList<Integer> array = mWinControlTypeMap.computeIfAbsent(win,
                key -> new ArrayList<>());
        array.add(type);
        mTypeWinControlMap.put(type, win);
    }

    void notifyControlChanged(WindowState target) {
        mPendingControlChanged.add(target);
        notifyPendingInsetsControlChanged();
    }

    private void notifyPendingInsetsControlChanged() {
        if (mPendingControlChanged.isEmpty()) {
            return;
        }
        mDisplayContent.mWmService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            for (int i = mPendingControlChanged.size() - 1; i >= 0; i--) {
                final WindowState controllingWin = mPendingControlChanged.valueAt(i);
                controllingWin.notifyInsetsControlChanged();
            }
            mPendingControlChanged.clear();
        });
    }

    private void notifyInsetsChanged() {
        mDisplayContent.forAllWindows(mDispatchInsetsChanged, true /* traverseTopToBottom */);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "WindowInsetsStateController");
        mState.dump(prefix + "  ", pw);
        pw.println(prefix + "  " + "Control map:");
        for (int i = mTypeWinControlMap.size() - 1; i >= 0; i--) {
            pw.print(prefix + "  ");
            pw.println(InsetsState.typeToString(mTypeWinControlMap.keyAt(i)) + " -> "
                    + mTypeWinControlMap.valueAt(i));
        }
    }
}
