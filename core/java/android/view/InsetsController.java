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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type.InsetType;
import android.view.InsetsState.InternalInsetType;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Implements {@link WindowInsetsController} on the client.
 * @hide
 */
public class InsetsController implements WindowInsetsController {

    private final String TAG = "InsetsControllerImpl";

    private final InsetsState mState = new InsetsState();
    private final InsetsState mTmpState = new InsetsState();

    private final Rect mFrame = new Rect();
    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    private final ViewRootImpl mViewRoot;

    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    private final ArrayList<InsetsAnimationControlImpl> mAnimationControls = new ArrayList<>();
    private WindowInsets mLastInsets;

    private boolean mAnimCallbackScheduled;

    private final Runnable mAnimCallback;

    public InsetsController(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mAnimationControls.isEmpty()) {
                return;
            }

            InsetsState state = new InsetsState(mState, true /* copySources */);
            for (int i = mAnimationControls.size() - 1; i >= 0; i--) {
                mAnimationControls.get(i).applyChangeInsets(state);
            }
            WindowInsets insets = state.calculateInsets(mFrame, mLastInsets.isRound(),
                    mLastInsets.shouldAlwaysConsumeNavBar(), mLastInsets.getDisplayCutout(),
                    null /* typeSideMap */);
            mViewRoot.mView.dispatchWindowInsetsAnimationProgress(insets);
        };
    }

    void onFrameChanged(Rect frame) {
        mFrame.set(frame);
    }

    public InsetsState getState() {
        return mState;
    }

    boolean onStateChanged(InsetsState state) {
        if (mState.equals(state)) {
            return false;
        }
        mState.set(state);
        mTmpState.set(state, true /* copySources */);
        applyLocalVisibilityOverride();
        mViewRoot.notifyInsetsChanged();
        if (!mState.equals(mTmpState)) {
            sendStateToWindowManager();
        }
        return true;
    }

    /**
     * @see InsetsState#calculateInsets
     */
    @VisibleForTesting
    public WindowInsets calculateInsets(boolean isScreenRound,
            boolean alwaysConsumeNavBar, DisplayCutout cutout) {
        mLastInsets = mState.calculateInsets(mFrame, isScreenRound, alwaysConsumeNavBar, cutout,
                null /* typeSideMap */);
        return mLastInsets;
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                mTmpControlArray.put(activeControl.getType(), activeControl);
            }
        }

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            final InsetsSourceControl control = mTmpControlArray.get(consumer.getType());

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control);
        }

        // Ensure to create source consumers if not available yet.
        for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = mTmpControlArray.valueAt(i);
            getSourceConsumer(control.getType()).setControl(control);
        }
        mTmpControlArray.clear();
    }

    @Override
    public void show(@InsetType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).show();
        }
    }

    @Override
    public void hide(@InsetType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).hide();
        }
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetType int types,
            WindowInsetsAnimationControlListener listener) {

        // TODO: Check whether we already have a controller.
        final ArraySet<Integer> internalTypes = mState.toInternalType(types);
        final SparseArray<InsetsSourceConsumer> consumers = new SparseArray<>();
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            if (consumer.getControl() != null) {
                consumers.put(consumer.getType(), consumer);
            } else {
                // TODO: Let calling app know it's not possible, or wait
                // TODO: Remove it from types
            }
        }
        final InsetsAnimationControlImpl controller = new InsetsAnimationControlImpl(consumers,
                mFrame, mState, listener, types,
                () -> new SyncRtSurfaceTransactionApplier(mViewRoot.mView), this);
        mAnimationControls.add(controller);
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer controller = mSourceConsumers.valueAt(i);
            controller.applyLocalVisibilityOverride();
        }
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getSourceConsumer(@InternalInsetType int type) {
        InsetsSourceConsumer controller = mSourceConsumers.get(type);
        if (controller != null) {
            return controller;
        }
        controller = new InsetsSourceConsumer(type, mState, Transaction::new, this);
        mSourceConsumers.put(type, controller);
        return controller;
    }

    @VisibleForTesting
    public void notifyVisibilityChanged() {
        mViewRoot.notifyInsetsChanged();
        sendStateToWindowManager();
    }

    /**
     * Sends the local visibility state back to window manager.
     */
    private void sendStateToWindowManager() {
        InsetsState tmpState = new InsetsState();
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getControl() != null) {
                tmpState.addSource(mState.getSource(consumer.getType()));
            }
        }

        // TODO: Put this on a dispatcher thread.
        try {
            mViewRoot.mWindowSession.insetsModified(mViewRoot.mWindow, tmpState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix); pw.println("InsetsController:");
        mState.dump(prefix + "  ", pw);
    }

    void dispatchAnimationStarted(WindowInsetsAnimationListener.InsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationStarted(animation);
    }

    void dispatchAnimationFinished(WindowInsetsAnimationListener.InsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationFinished(animation);
    }

    void scheduleApplyChangeInsets() {
        if (!mAnimCallbackScheduled) {
            mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION,
                    mAnimCallback, null /* token*/);
            mAnimCallbackScheduled = true;
        }
    }
}
