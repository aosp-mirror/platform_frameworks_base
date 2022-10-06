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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.systemGestures;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Manages global window inset state in the system represented by {@link InsetsState}.
 */
class InsetsStateController {

    private final InsetsState mLastState = new InsetsState();
    private final InsetsState mState = new InsetsState();
    private final DisplayContent mDisplayContent;

    private final ArrayMap<Integer, WindowContainerInsetsSourceProvider> mProviders =
            new ArrayMap<>();
    private final ArrayMap<InsetsControlTarget, ArrayList<Integer>> mControlTargetTypeMap =
            new ArrayMap<>();
    private final SparseArray<InsetsControlTarget> mTypeControlTargetMap = new SparseArray<>();

    /** @see #onControlFakeTargetChanged */
    private final SparseArray<InsetsControlTarget> mTypeFakeControlTargetMap = new SparseArray<>();

    private final ArraySet<InsetsControlTarget> mPendingControlChanged = new ArraySet<>();

    private final Consumer<WindowState> mDispatchInsetsChanged = w -> {
        if (w.isReadyToDispatchInsetsState()) {
            w.notifyInsetsChanged();
        }
    };
    private final InsetsControlTarget mEmptyImeControlTarget = new InsetsControlTarget() {
        @Override
        public void notifyInsetsControlChanged() {
            InsetsSourceControl[] controls = getControlsForDispatch(this);
            if (controls == null) {
                return;
            }
            for (InsetsSourceControl control : controls) {
                if (control.getType() == ITYPE_IME) {
                    mDisplayContent.mWmService.mH.post(() ->
                            InputMethodManagerInternal.get().removeImeSurface());
                }
            }
        }
    };

    private final Function<Integer, WindowContainerInsetsSourceProvider> mSourceProviderFunc;

    InsetsStateController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
        mSourceProviderFunc = type -> {
            final InsetsSource source = mState.getSource(type);
            if (type == ITYPE_IME) {
                return new ImeInsetsSourceProvider(source, this, mDisplayContent);
            }
            return new WindowContainerInsetsSourceProvider(source, this, mDisplayContent);
        };
    }

    InsetsState getRawInsetsState() {
        return mState;
    }

    @Nullable InsetsSourceControl[] getControlsForDispatch(InsetsControlTarget target) {
        ArrayList<Integer> controlled = mControlTargetTypeMap.get(target);
        if (controlled == null) {
            return null;
        }
        final int size = controlled.size();
        final InsetsSourceControl[] result = new InsetsSourceControl[size];
        for (int i = 0; i < size; i++) {
            result[i] = mProviders.get(controlled.get(i)).getControl(target);
        }
        return result;
    }

    ArrayMap<Integer, WindowContainerInsetsSourceProvider> getSourceProviders() {
        return mProviders;
    }

    /**
     * @return The provider of a specific type.
     */
    WindowContainerInsetsSourceProvider getSourceProvider(@InternalInsetsType int type) {
        return mProviders.computeIfAbsent(type, mSourceProviderFunc);
    }

    ImeInsetsSourceProvider getImeSourceProvider() {
        return (ImeInsetsSourceProvider) getSourceProvider(ITYPE_IME);
    }

    /**
     * @return The provider of a specific type or null if we don't have it.
     */
    @Nullable
    WindowContainerInsetsSourceProvider peekSourceProvider(@InternalInsetsType int type) {
        return mProviders.get(type);
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "ISC.onPostLayout");
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.valueAt(i).onPostLayout();
        }
        if (!mLastState.equals(mState)) {
            mLastState.set(mState, true /* copySources */);
            notifyInsetsChanged();
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    /**
     * Updates {@link WindowState#mAboveInsetsState} for all windows in the display.
     *
     * @param notifyInsetsChange {@code true} if the clients should be notified about the change.
     */
    void updateAboveInsetsState(boolean notifyInsetsChange) {
        final InsetsState aboveInsetsState = new InsetsState();
        aboveInsetsState.set(mState,
                displayCutout() | systemGestures() | mandatorySystemGestures());
        final ArraySet<WindowState> insetsChangedWindows = new ArraySet<>();
        final SparseArray<InsetsSourceProvider>
                localInsetsSourceProvidersFromParent = new SparseArray<>();
        // This method will iterate on the entire hierarchy in top to bottom z-order manner. The
        // aboveInsetsState will be modified as per the insets provided by the WindowState being
        // visited.
        mDisplayContent.updateAboveInsetsState(aboveInsetsState,
                localInsetsSourceProvidersFromParent, insetsChangedWindows);
        if (notifyInsetsChange) {
            for (int i = insetsChangedWindows.size() - 1; i >= 0; i--) {
                mDispatchInsetsChanged.accept(insetsChangedWindows.valueAt(i));
            }
        }
    }

    void onDisplayFramesUpdated(boolean notifyInsetsChange) {
        final ArrayList<WindowState> insetsChangedWindows = new ArrayList<>();
        mDisplayContent.forAllWindows(w -> {
            w.mAboveInsetsState.set(mState, displayCutout());
            insetsChangedWindows.add(w);
        }, true /* traverseTopToBottom */);
        if (notifyInsetsChange) {
            for (int i = insetsChangedWindows.size() - 1; i >= 0; i--) {
                mDispatchInsetsChanged.accept(insetsChangedWindows.get(i));
            }
        }
    }

    void onInsetsModified(InsetsControlTarget caller) {
        boolean changed = false;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            changed |= mProviders.valueAt(i).updateClientVisibility(caller);
        }
        if (changed) {
            notifyInsetsChanged();
            mDisplayContent.updateSystemGestureExclusion();
            mDisplayContent.updateKeepClearAreas();
            mDisplayContent.getDisplayPolicy().updateSystemBarAttributes();
        }
    }

    boolean isFakeTarget(@InternalInsetsType int type, InsetsControlTarget target) {
        return mTypeFakeControlTargetMap.get(type) == target;
    }

    void onImeControlTargetChanged(@Nullable InsetsControlTarget imeTarget) {

        // Make sure that we always have a control target for the IME, even if the IME target is
        // null. Otherwise there is no leash that will hide it and IME becomes "randomly" visible.
        InsetsControlTarget target = imeTarget != null ? imeTarget : mEmptyImeControlTarget;
        onControlChanged(ITYPE_IME, target);
        ProtoLog.d(WM_DEBUG_IME, "onImeControlTargetChanged %s",
                target != null ? target.getWindow() : "null");
        notifyPendingInsetsControlChanged();
    }

    /**
     * Called when the focused window that is able to control the system bars changes.
     *
     * @param statusControlling The target that is now able to control the status bar appearance
     *                          and visibility.
     * @param navControlling The target that is now able to control the nav bar appearance
     *                       and visibility.
     */
    void onBarControlTargetChanged(@Nullable InsetsControlTarget statusControlling,
            @Nullable InsetsControlTarget fakeStatusControlling,
            @Nullable InsetsControlTarget navControlling,
            @Nullable InsetsControlTarget fakeNavControlling) {
        onControlChanged(ITYPE_STATUS_BAR, statusControlling);
        onControlChanged(ITYPE_NAVIGATION_BAR, navControlling);
        onControlChanged(ITYPE_CLIMATE_BAR, statusControlling);
        onControlChanged(ITYPE_EXTRA_NAVIGATION_BAR, navControlling);
        onControlFakeTargetChanged(ITYPE_STATUS_BAR, fakeStatusControlling);
        onControlFakeTargetChanged(ITYPE_NAVIGATION_BAR, fakeNavControlling);
        onControlFakeTargetChanged(ITYPE_CLIMATE_BAR, fakeStatusControlling);
        onControlFakeTargetChanged(ITYPE_EXTRA_NAVIGATION_BAR, fakeNavControlling);
        notifyPendingInsetsControlChanged();
    }

    void notifyControlRevoked(@NonNull InsetsControlTarget previousControlTarget,
            InsetsSourceProvider provider) {
        removeFromControlMaps(previousControlTarget, provider.getSource().getType(),
                false /* fake */);
    }

    private void onControlChanged(@InternalInsetsType int type,
            @Nullable InsetsControlTarget target) {
        final InsetsControlTarget previous = mTypeControlTargetMap.get(type);
        if (target == previous) {
            return;
        }
        final WindowContainerInsetsSourceProvider provider = mProviders.get(type);
        if (provider == null) {
            return;
        }
        if (!provider.isControllable()) {
            return;
        }
        provider.updateControlForTarget(target, false /* force */);
        target = provider.getControlTarget();
        if (previous != null) {
            removeFromControlMaps(previous, type, false /* fake */);
            mPendingControlChanged.add(previous);
        }
        if (target != null) {
            addToControlMaps(target, type, false /* fake */);
            mPendingControlChanged.add(target);
        }
    }

    /**
     * The fake target saved here will be used to pretend to the app that it's still under control
     * of the bars while it's not really, but we still need to find out the apps intentions around
     * showing/hiding. For example, when the transient bars are showing, and the fake target
     * requests to show system bars, the transient state will be aborted.
     */
    void onControlFakeTargetChanged(@InternalInsetsType int type,
            @Nullable InsetsControlTarget fakeTarget) {
        final InsetsControlTarget previous = mTypeFakeControlTargetMap.get(type);
        if (fakeTarget == previous) {
            return;
        }
        final WindowContainerInsetsSourceProvider provider = mProviders.get(type);
        if (provider == null) {
            return;
        }
        provider.updateControlForFakeTarget(fakeTarget);
        if (previous != null) {
            removeFromControlMaps(previous, type, true /* fake */);
            mPendingControlChanged.add(previous);
        }
        if (fakeTarget != null) {
            addToControlMaps(fakeTarget, type, true /* fake */);
            mPendingControlChanged.add(fakeTarget);
        }
    }

    private void removeFromControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetsType int type, boolean fake) {
        final ArrayList<Integer> array = mControlTargetTypeMap.get(target);
        if (array == null) {
            return;
        }
        array.remove((Integer) type);
        if (array.isEmpty()) {
            mControlTargetTypeMap.remove(target);
        }
        if (fake) {
            mTypeFakeControlTargetMap.remove(type);
        } else {
            mTypeControlTargetMap.remove(type);
        }
    }

    private void addToControlMaps(@NonNull InsetsControlTarget target,
            @InternalInsetsType int type, boolean fake) {
        final ArrayList<Integer> array = mControlTargetTypeMap.computeIfAbsent(target,
                key -> new ArrayList<>());
        array.add(type);
        if (fake) {
            mTypeFakeControlTargetMap.put(type, target);
        } else {
            mTypeControlTargetMap.put(type, target);
        }
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
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                final WindowContainerInsetsSourceProvider provider = mProviders.valueAt(i);
                provider.onSurfaceTransactionApplied();
            }
            final ArraySet<InsetsControlTarget> newControlTargets = new ArraySet<>();
            for (int i = mPendingControlChanged.size() - 1; i >= 0; i--) {
                final InsetsControlTarget controlTarget = mPendingControlChanged.valueAt(i);
                controlTarget.notifyInsetsControlChanged();
                if (mControlTargetTypeMap.containsKey(controlTarget)) {
                    // We only collect targets who get controls, not lose controls.
                    newControlTargets.add(controlTarget);
                }
            }
            mPendingControlChanged.clear();

            // This updates the insets visibilities AFTER sending current insets state and controls
            // to the clients, so that the clients can change the current visibilities to the
            // requested visibilities with animations.
            for (int i = newControlTargets.size() - 1; i >= 0; i--) {
                onInsetsModified(newControlTargets.valueAt(i));
            }
            newControlTargets.clear();
        });
    }

    void notifyInsetsChanged() {
        mDisplayContent.notifyInsetsChanged(mDispatchInsetsChanged);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "WindowInsetsStateController");
        prefix = prefix + "  ";
        mState.dump(prefix, pw);
        pw.println(prefix + "Control map:");
        for (int i = mTypeControlTargetMap.size() - 1; i >= 0; i--) {
            pw.print(prefix + "  ");
            pw.println(InsetsState.typeToString(mTypeControlTargetMap.keyAt(i)) + " -> "
                    + mTypeControlTargetMap.valueAt(i));
        }
        pw.println(prefix + "InsetsSourceProviders:");
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.valueAt(i).dump(pw, prefix + "  ");
        }
    }
}
