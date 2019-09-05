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

import static android.view.InsetsState.InternalInsetType;
import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

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

    private final ArrayMap<Integer, InsetsSourceProvider> mProviders = new ArrayMap<>();
    private final ArrayMap<InsetsControlTarget, ArrayList<Integer>> mControlTargetTypeMap =
            new ArrayMap<>();
    private final SparseArray<InsetsControlTarget> mTypeControlTargetMap = new SparseArray<>();
    private final ArraySet<InsetsControlTarget> mPendingControlChanged = new ArraySet<>();

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

    @Nullable InsetsSourceControl[] getControlsForDispatch(InsetsControlTarget target) {
        ArrayList<Integer> controlled = mControlTargetTypeMap.get(target);
        if (controlled == null) {
            return null;
        }
        final int size = controlled.size();
        final InsetsSourceControl[] result = new InsetsSourceControl[size];
        for (int i = 0; i < size; i++) {
            result[i] = mProviders.get(controlled.get(i)).getControl();
        }
        return result;
    }

    /**
     * @return The provider of a specific type.
     */
    InsetsSourceProvider getSourceProvider(@InternalInsetType int type) {
        return mProviders.computeIfAbsent(type,
                key -> new InsetsSourceProvider(mState.getSource(key), this, mDisplayContent));
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        mState.setDisplayFrame(mDisplayContent.getBounds());
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.valueAt(i).onPostLayout();
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
            final InsetsSourceProvider provider = mProviders.get(source.getType());
            if (provider == null) {
                continue;
            }
            changed |= provider.onInsetsModified(windowState, source);
        }
        if (changed) {
            notifyInsetsChanged();
        }
    }

    void onImeTargetChanged(@Nullable InsetsControlTarget imeTarget) {
        onControlChanged(TYPE_IME, imeTarget);
        notifyPendingInsetsControlChanged();
    }

    /**
     * Called when the focused window that is able to control the system bars changes.
     *
     * @param topControlling The target that is now able to control the top bar appearance
     *                       and visibility.
     * @param navControlling The target that is now able to control the nav bar appearance
     *                       and visibility.
     */
    void onBarControlTargetChanged(@Nullable InsetsControlTarget topControlling,
            @Nullable InsetsControlTarget navControlling) {
        onControlChanged(TYPE_TOP_BAR, topControlling);
        onControlChanged(TYPE_NAVIGATION_BAR, navControlling);
        notifyPendingInsetsControlChanged();
    }

    void notifyControlRevoked(@NonNull InsetsControlTarget previousControlTarget,
            InsetsSourceProvider provider) {
        removeFromControlMaps(previousControlTarget, provider.getSource().getType());
    }

    private void onControlChanged(@InternalInsetType int type,
            @Nullable InsetsControlTarget target) {
        final InsetsControlTarget previous = mTypeControlTargetMap.get(type);
        if (target == previous) {
            return;
        }
        final InsetsSourceProvider provider = getSourceProvider(type);
        if (provider == null) {
            return;
        }
        if (!provider.isControllable()) {
            return;
        }
        provider.updateControlForTarget(target, false /* force */);
        if (previous != null) {
            removeFromControlMaps(previous, type);
            mPendingControlChanged.add(previous);
        }
        if (target != null) {
            addToControlMaps(target, type);
            mPendingControlChanged.add(target);
        }
    }

    private void removeFromControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetType int type) {
        final ArrayList<Integer> array = mControlTargetTypeMap.get(target);
        if (array == null) {
            return;
        }
        array.remove((Integer) type);
        if (array.isEmpty()) {
            mControlTargetTypeMap.remove(target);
        }
        mTypeControlTargetMap.remove(type);
    }

    private void addToControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetType int type) {
        final ArrayList<Integer> array = mControlTargetTypeMap.computeIfAbsent(target,
                key -> new ArrayList<>());
        array.add(type);
        mTypeControlTargetMap.put(type, target);
    }

    void notifyControlChanged(InsetsControlTarget target) {
        mPendingControlChanged.add(target);
        notifyPendingInsetsControlChanged();
    }

    private void notifyPendingInsetsControlChanged() {
        if (mPendingControlChanged.isEmpty()) {
            return;
        }
        mDisplayContent.mWmService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            for (int i = mPendingControlChanged.size() - 1; i >= 0; i--) {
                final InsetsControlTarget controlTarget = mPendingControlChanged.valueAt(i);
                controlTarget.notifyInsetsControlChanged();
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
        for (int i = mTypeControlTargetMap.size() - 1; i >= 0; i--) {
            pw.print(prefix + "  ");
            pw.println(InsetsState.typeToString(mTypeControlTargetMap.keyAt(i)) + " -> "
                    + mTypeControlTargetMap.valueAt(i));
        }
    }
}
