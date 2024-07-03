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
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.systemGestures;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.server.wm.DisplayContentProto.IME_INSETS_SOURCE_PROVIDER;
import static com.android.server.wm.DisplayContentProto.INSETS_SOURCE_PROVIDERS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;

import com.android.internal.protolog.ProtoLog;
import com.android.server.inputmethod.InputMethodManagerInternal;

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

    private final SparseArray<InsetsSourceProvider> mProviders = new SparseArray<>();
    private final ArrayMap<InsetsControlTarget, ArrayList<InsetsSourceProvider>>
            mControlTargetProvidersMap = new ArrayMap<>();
    private final SparseArray<InsetsControlTarget> mIdControlTargetMap = new SparseArray<>();
    private final SparseArray<InsetsControlTarget> mIdFakeControlTargetMap = new SparseArray<>();

    private final ArraySet<InsetsControlTarget> mPendingControlChanged = new ArraySet<>();

    private final Consumer<WindowState> mDispatchInsetsChanged = w -> {
        if (w.isReadyToDispatchInsetsState()) {
            w.notifyInsetsChanged();
        }
    };
    private final InsetsControlTarget mEmptyImeControlTarget = new InsetsControlTarget() {
        @Override
        public void notifyInsetsControlChanged(int displayId) {
            InsetsSourceControl[] controls = getControlsForDispatch(this);
            if (controls == null) {
                return;
            }
            for (InsetsSourceControl control : controls) {
                if (control.getType() == WindowInsets.Type.ime()) {
                    mDisplayContent.mWmService.mH.post(() ->
                            InputMethodManagerInternal.get().removeImeSurface(displayId));
                }
            }
        }
    };

    private @InsetsType int mForcedConsumingTypes;

    InsetsStateController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    InsetsState getRawInsetsState() {
        return mState;
    }

    @Nullable InsetsSourceControl[] getControlsForDispatch(InsetsControlTarget target) {
        final ArrayList<InsetsSourceProvider> controlled = mControlTargetProvidersMap.get(target);
        if (controlled == null) {
            return null;
        }
        final int size = controlled.size();
        final InsetsSourceControl[] result = new InsetsSourceControl[size];
        for (int i = 0; i < size; i++) {
            result[i] = controlled.get(i).getControl(target);
        }
        return result;
    }

    SparseArray<InsetsSourceProvider> getSourceProviders() {
        return mProviders;
    }

    /**
     * @return The provider of a specific source ID.
     */
    InsetsSourceProvider getOrCreateSourceProvider(int id, @InsetsType int type) {
        InsetsSourceProvider provider = mProviders.get(id);
        if (provider != null) {
            return provider;
        }
        final InsetsSource source = mState.getOrCreateSource(id, type);
        provider = id == ID_IME
                ? new ImeInsetsSourceProvider(source, this, mDisplayContent)
                : new InsetsSourceProvider(source, this, mDisplayContent);
        provider.setFlags(
                (mForcedConsumingTypes & type) != 0
                        ? FLAG_FORCE_CONSUMING
                        : 0,
                FLAG_FORCE_CONSUMING);
        mProviders.put(id, provider);
        return provider;
    }

    ImeInsetsSourceProvider getImeSourceProvider() {
        return (ImeInsetsSourceProvider) getOrCreateSourceProvider(ID_IME, ime());
    }

    void removeSourceProvider(int id) {
        if (id != ID_IME) {
            mState.removeSource(id);
            mProviders.remove(id);
        }
    }

    void setForcedConsumingTypes(@InsetsType int types) {
        if (mForcedConsumingTypes != types) {
            mForcedConsumingTypes = types;
            boolean changed = false;
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                final InsetsSourceProvider provider = mProviders.valueAt(i);
                changed |= provider.setFlags(
                        (types & provider.getSource().getType()) != 0
                                ? FLAG_FORCE_CONSUMING
                                : 0,
                        FLAG_FORCE_CONSUMING);
            }
            if (changed) {
                notifyInsetsChanged();
            }
        }
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
        final SparseArray<InsetsSource> localInsetsSourcesFromParent = new SparseArray<>();
        final ArraySet<WindowState> insetsChangedWindows = new ArraySet<>();

        // This method will iterate on the entire hierarchy in top to bottom z-order manner. The
        // aboveInsetsState will be modified as per the insets provided by the WindowState being
        // visited.
        mDisplayContent.updateAboveInsetsState(aboveInsetsState, localInsetsSourcesFromParent,
                insetsChangedWindows);

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

    void onRequestedVisibleTypesChanged(InsetsControlTarget caller) {
        boolean changed = false;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            changed |= mProviders.valueAt(i).updateClientVisibility(caller);
        }
        if (!android.view.inputmethod.Flags.refactorInsetsController()) {
            if (changed) {
                notifyInsetsChanged();
                mDisplayContent.updateSystemGestureExclusion();

                mDisplayContent.getDisplayPolicy().updateSystemBarAttributes();
            }
        }
    }

    @InsetsType int getFakeControllingTypes(InsetsControlTarget target) {
        @InsetsType int types = 0;
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider provider = mProviders.valueAt(i);
            final InsetsControlTarget fakeControlTarget = provider.getFakeControlTarget();
            if (target == fakeControlTarget) {
                types |= provider.getSource().getType();
            }
        }
        return types;
    }

    void onImeControlTargetChanged(@Nullable InsetsControlTarget imeTarget) {

        // Make sure that we always have a control target for the IME, even if the IME target is
        // null. Otherwise there is no leash that will hide it and IME becomes "randomly" visible.
        InsetsControlTarget target = imeTarget != null ? imeTarget : mEmptyImeControlTarget;
        onControlTargetChanged(getImeSourceProvider(), target, false /* fake */);
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
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider provider = mProviders.valueAt(i);
            final @InsetsType int type = provider.getSource().getType();
            if (type == WindowInsets.Type.statusBars()) {
                onControlTargetChanged(provider, statusControlling, false /* fake */);
                onControlTargetChanged(provider, fakeStatusControlling, true /* fake */);
            } else if (type == WindowInsets.Type.navigationBars()) {
                onControlTargetChanged(provider, navControlling, false /* fake */);
                onControlTargetChanged(provider, fakeNavControlling, true /* fake */);
            }
        }
        notifyPendingInsetsControlChanged();
    }

    void notifyControlTargetChanged(@Nullable InsetsControlTarget target,
            InsetsSourceProvider provider) {
        onControlTargetChanged(provider, target, false /* fake */);
        notifyPendingInsetsControlChanged();
    }

    void notifyControlRevoked(@NonNull InsetsControlTarget previousControlTarget,
            InsetsSourceProvider provider) {
        removeFromControlMaps(previousControlTarget, provider, false /* fake */);
    }

    private void onControlTargetChanged(InsetsSourceProvider provider,
            @Nullable InsetsControlTarget target, boolean fake) {
        final InsetsControlTarget lastTarget = fake
                ? mIdFakeControlTargetMap.get(provider.getSource().getId())
                : mIdControlTargetMap.get(provider.getSource().getId());
        if (target == lastTarget) {
            return;
        }
        if (!provider.isControllable()) {
            return;
        }
        if (fake) {
            // The fake target updated here will be used to pretend to the app that it's still under
            // control of the bars while it's not really, but we still need to find out the apps
            // intentions around showing/hiding. For example, when the transient bars are showing,
            // and the fake target requests to show system bars, the transient state will be
            // aborted.
            provider.updateFakeControlTarget(target);
        } else {
            provider.updateControlForTarget(target, false /* force */);

            // Get control target again in case the provider didn't accept the one we passed to it.
            target = provider.getControlTarget();
            if (target == lastTarget) {
                return;
            }
        }
        if (lastTarget != null) {
            removeFromControlMaps(lastTarget, provider, fake);
            mPendingControlChanged.add(lastTarget);
        }
        if (target != null) {
            addToControlMaps(target, provider, fake);
            mPendingControlChanged.add(target);
        }
    }

    private void removeFromControlMaps(@NonNull InsetsControlTarget target,
            InsetsSourceProvider provider, boolean fake) {
        final ArrayList<InsetsSourceProvider> array = mControlTargetProvidersMap.get(target);
        if (array == null) {
            return;
        }
        array.remove(provider);
        if (array.isEmpty()) {
            mControlTargetProvidersMap.remove(target);
        }
        if (fake) {
            mIdFakeControlTargetMap.remove(provider.getSource().getId());
        } else {
            mIdControlTargetMap.remove(provider.getSource().getId());
        }
    }

    private void addToControlMaps(@NonNull InsetsControlTarget target,
            InsetsSourceProvider provider, boolean fake) {
        final ArrayList<InsetsSourceProvider> array = mControlTargetProvidersMap.computeIfAbsent(
                target, key -> new ArrayList<>());
        array.add(provider);
        if (fake) {
            mIdFakeControlTargetMap.put(provider.getSource().getId(), target);
        } else {
            mIdControlTargetMap.put(provider.getSource().getId(), target);
        }
    }

    void notifyControlChanged(InsetsControlTarget target) {
        mPendingControlChanged.add(target);
        notifyPendingInsetsControlChanged();

        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            notifyInsetsChanged();
            mDisplayContent.updateSystemGestureExclusion();
            mDisplayContent.updateKeepClearAreas();
            mDisplayContent.getDisplayPolicy().updateSystemBarAttributes();
        }
    }

    private void notifyPendingInsetsControlChanged() {
        if (mPendingControlChanged.isEmpty()) {
            return;
        }
        mDisplayContent.mWmService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                final InsetsSourceProvider provider = mProviders.valueAt(i);
                provider.onSurfaceTransactionApplied();
            }
            final ArraySet<InsetsControlTarget> newControlTargets = new ArraySet<>();
            int displayId = mDisplayContent.getDisplayId();
            for (int i = mPendingControlChanged.size() - 1; i >= 0; i--) {
                final InsetsControlTarget controlTarget = mPendingControlChanged.valueAt(i);
                controlTarget.notifyInsetsControlChanged(displayId);
                if (mControlTargetProvidersMap.containsKey(controlTarget)) {
                    // We only collect targets who get controls, not lose controls.
                    newControlTargets.add(controlTarget);
                }
            }
            mPendingControlChanged.clear();

            // This updates the insets visibilities AFTER sending current insets state and controls
            // to the clients, so that the clients can change the current visibilities to the
            // requested visibilities with animations.
            for (int i = newControlTargets.size() - 1; i >= 0; i--) {
                onRequestedVisibleTypesChanged(newControlTargets.valueAt(i));
            }
            newControlTargets.clear();
            // Check for and try to run the scheduled show IME request (if it exists), as we
            // now applied the surface transaction and notified the target of the new control.
            getImeSourceProvider().checkAndStartShowImePostLayout();
        });
    }

    void notifyInsetsChanged() {
        mDisplayContent.notifyInsetsChanged(mDispatchInsetsChanged);
    }

    /**
     * Checks if the control target has pending controls.
     *
     * @param target the control target to check.
     */
    boolean hasPendingControls(@NonNull InsetsControlTarget target) {
        return mPendingControlChanged.contains(target);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "WindowInsetsStateController");
        prefix = prefix + "  ";
        mState.dump(prefix, pw);
        pw.println(prefix + "Control map:");
        for (int i = mControlTargetProvidersMap.size() - 1; i >= 0; i--) {
            final InsetsControlTarget controlTarget = mControlTargetProvidersMap.keyAt(i);
            pw.print(prefix + "  ");
            pw.print(controlTarget);
            pw.println(":");
            final ArrayList<InsetsSourceProvider> providers = mControlTargetProvidersMap.valueAt(i);
            for (int j = providers.size() - 1; j >= 0; j--) {
                final InsetsSourceProvider provider = providers.get(j);
                if (provider != null) {
                    pw.print(prefix + "    ");
                    if (controlTarget == provider.getFakeControlTarget()) {
                        pw.print("(fake) ");
                    }
                    pw.println(provider.getControl(controlTarget));
                }
            }
        }
        if (mControlTargetProvidersMap.isEmpty()) {
            pw.print(prefix + "  none");
        }
        pw.println(prefix + "InsetsSourceProviders:");
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            mProviders.valueAt(i).dump(pw, prefix + "  ");
        }
        if (mForcedConsumingTypes != 0) {
            pw.println(prefix + "mForcedConsumingTypes="
                    + WindowInsets.Type.toString(mForcedConsumingTypes));
        }
    }

    void dumpDebug(ProtoOutputStream proto, @WindowTraceLogLevel int logLevel) {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider provider = mProviders.valueAt(i);
            provider.dumpDebug(proto,
                    provider.getSource().getType() == ime()
                            ? IME_INSETS_SOURCE_PROVIDER
                            : INSETS_SOURCE_PROVIDERS,
                    logLevel);
        }
    }
}
