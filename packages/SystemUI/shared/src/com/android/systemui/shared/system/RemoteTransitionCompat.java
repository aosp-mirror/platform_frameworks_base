/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.shared.system;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.IRecentsAnimationController;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;
import com.android.systemui.shared.recents.model.ThumbnailData;

/**
 * Wrapper to expose RemoteTransition (shell transitions) to Launcher.
 *
 * @see IRemoteTransition
 * @see TransitionFilter
 */
@DataClass
public class RemoteTransitionCompat implements Parcelable {
    private static final String TAG = "RemoteTransitionCompat";

    @NonNull final IRemoteTransition mTransition;
    @Nullable TransitionFilter mFilter = null;

    RemoteTransitionCompat(IRemoteTransition transition) {
        mTransition = transition;
    }

    public RemoteTransitionCompat(RemoteTransitionRunner runner) {
        mTransition = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(TransitionInfo info, SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                final Runnable finishAdapter = () ->  {
                    try {
                        finishedCallback.onTransitionFinished(null /* wct */);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to call transition finished callback", e);
                    }
                };
                runner.startAnimation(info, t, finishAdapter);
            }
        };
    }

    /** Constructor specifically for recents animation */
    public RemoteTransitionCompat(RecentsAnimationListener recents,
            RecentsAnimationControllerCompat controller) {
        mTransition = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(TransitionInfo info, SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                final RemoteAnimationTargetCompat[] apps =
                        RemoteAnimationTargetCompat.wrap(info, false /* wallpapers */);
                final RemoteAnimationTargetCompat[] wallpapers =
                        RemoteAnimationTargetCompat.wrap(info, true /* wallpapers */);
                // TODO(b/177438007): Move this set-up logic into launcher's animation impl.
                // This transition is for opening recents, so recents is on-top. We want to draw
                // the current going-away task on top of recents, though, so move it to front
                WindowContainerToken pausingTask = null;
                for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                    final TransitionInfo.Change change = info.getChanges().get(i);
                    if (change.getMode() == TRANSIT_CLOSE || change.getMode() == TRANSIT_TO_BACK) {
                        t.setLayer(change.getLeash(), info.getChanges().size() * 3 - i);
                        if (change.getTaskInfo() != null) {
                            pausingTask = change.getTaskInfo().token;
                        }
                    }
                }
                // Also make all the wallpapers opaque since we want the visible from the start
                for (int i = wallpapers.length - 1; i >= 0; --i) {
                    t.setAlpha(wallpapers[i].leash.mSurfaceControl, 1);
                }
                t.apply();
                final RecentsAnimationControllerCompat wrapControl =
                        new RecentsControllerWrap(controller, finishedCallback, pausingTask);
                recents.onAnimationStart(wrapControl, apps, wallpapers, new Rect(0, 0, 0, 0),
                        new Rect());
            }
        };
    }

    /** Adds a filter check that restricts this remote transition to home open transitions. */
    public void addHomeOpenCheck() {
        if (mFilter == null) {
            mFilter = new TransitionFilter();
        }
        mFilter.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement()};
        mFilter.mRequirements[0].mActivityType = ACTIVITY_TYPE_HOME;
        mFilter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
    }

    /**
     * Wrapper to hook up parts of recents animation to shell transition.
     * TODO(b/177438007): Remove this once Launcher handles shell transitions directly.
     */
    @VisibleForTesting
    static class RecentsControllerWrap extends RecentsAnimationControllerCompat {
        private final RecentsAnimationControllerCompat mWrapped;
        private final IRemoteTransitionFinishedCallback mFinishCB;
        private final WindowContainerToken mPausingTask;

        RecentsControllerWrap(RecentsAnimationControllerCompat wrapped,
                IRemoteTransitionFinishedCallback finishCB, WindowContainerToken pausingTask) {
            mWrapped = wrapped;
            mFinishCB = finishCB;
            mPausingTask = pausingTask;
        }

        @Override public ThumbnailData screenshotTask(int taskId) {
            return mWrapped != null ? mWrapped.screenshotTask(taskId) : null;
        }

        @Override public void setInputConsumerEnabled(boolean enabled) {
            if (mWrapped != null) mWrapped.setInputConsumerEnabled(enabled);
        }

        @Override public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars) {
            if (mWrapped != null) mWrapped.setAnimationTargetsBehindSystemBars(behindSystemBars);
        }

        @Override public void hideCurrentInputMethod() {
            mWrapped.hideCurrentInputMethod();
        }

        @Override public void setFinishTaskBounds(int taskId, Rect destinationBounds,
                Rect windowCrop, float[] float9) {
            if (mWrapped != null) {
                mWrapped.setFinishTaskBounds(taskId, destinationBounds, windowCrop, float9);
            }
        }

        @Override public void finish(boolean toHome, boolean sendUserLeaveHint) {
            try {
                if (!toHome && mPausingTask != null) {
                    // The gesture went back to opening the app rather than continuing with
                    // recents, so end the transition by moving the app back to the top.
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    wct.reorder(mPausingTask, true /* onTop */);
                    mFinishCB.onTransitionFinished(wct);
                } else {
                    mFinishCB.onTransitionFinished(null /* wct */);
                }
            } catch (RemoteException e) {
                Log.e("RemoteTransitionCompat", "Failed to call animation finish callback", e);
            }
            if (mWrapped != null) mWrapped.finish(toHome, sendUserLeaveHint);
        }

        @Override public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
            if (mWrapped != null) mWrapped.setDeferCancelUntilNextTransition(defer, screenshot);
        }

        @Override public void cleanupScreenshot() {
            if (mWrapped != null) mWrapped.cleanupScreenshot();
        }

        @Override public void setWillFinishToHome(boolean willFinishToHome) {
            if (mWrapped != null) mWrapped.setWillFinishToHome(willFinishToHome);
        }

        /**
         * @see IRecentsAnimationController#removeTask
         */
        @Override public boolean removeTask(int taskId) {
            return mWrapped != null ? mWrapped.removeTask(taskId) : false;
        }
    }



    // Code below generated by codegen v1.0.21.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/packages/SystemUI/shared/src/com/android/systemui/shared/system/RemoteTransitionCompat.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off

    @DataClass.Generated.Member
    public @NonNull IRemoteTransition getTransition() {
        return mTransition;
    }

    @DataClass.Generated.Member
    public @Nullable TransitionFilter getFilter() {
        return mFilter;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mFilter != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeStrongInterface(mTransition);
        if (mFilter != null) dest.writeTypedObject(mFilter, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected RemoteTransitionCompat(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        IRemoteTransition transition = IRemoteTransition.Stub.asInterface(in.readStrongBinder());
        TransitionFilter filter = (flg & 0x2) == 0 ? null : (TransitionFilter) in.readTypedObject(TransitionFilter.CREATOR);

        this.mTransition = transition;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTransition);
        this.mFilter = filter;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<RemoteTransitionCompat> CREATOR
            = new Parcelable.Creator<RemoteTransitionCompat>() {
        @Override
        public RemoteTransitionCompat[] newArray(int size) {
            return new RemoteTransitionCompat[size];
        }

        @Override
        public RemoteTransitionCompat createFromParcel(@NonNull android.os.Parcel in) {
            return new RemoteTransitionCompat(in);
        }
    };

    /**
     * A builder for {@link RemoteTransitionCompat}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static class Builder {

        private @NonNull IRemoteTransition mTransition;
        private @Nullable TransitionFilter mFilter;

        private long mBuilderFieldsSet = 0L;

        public Builder(
                @NonNull IRemoteTransition transition) {
            mTransition = transition;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mTransition);
        }

        @DataClass.Generated.Member
        public @NonNull Builder setTransition(@NonNull IRemoteTransition value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mTransition = value;
            return this;
        }

        @DataClass.Generated.Member
        public @NonNull Builder setFilter(@NonNull TransitionFilter value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mFilter = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull RemoteTransitionCompat build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mFilter = null;
            }
            RemoteTransitionCompat o = new RemoteTransitionCompat(mTransition);
            o.mFilter = this.mFilter;
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1606862689344L,
            codegenVersion = "1.0.21",
            sourceFile = "frameworks/base/packages/SystemUI/shared/src/com/android/systemui/shared/system/RemoteTransitionCompat.java",
            inputSignatures = "final @android.annotation.NonNull com.android.systemui.shared.system.IRemoteTransition mTransition\n @android.annotation.Nullable android.window.TransitionFilter mFilter\npublic  void addHomeOpenCheck()\nclass RemoteTransitionCompat extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
