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
 * limitations under the License
 */

package android.view;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Object that describes how to run a remote animation.
 * <p>
 * A remote animation lets another app control the entire app transition. It does so by
 * <ul>
 *     <li>using {@link ActivityOptions#makeRemoteAnimation}</li>
 *     <li>using {@link IWindowManager#overridePendingAppTransitionRemote}</li>
 * </ul>
 * to register a {@link RemoteAnimationAdapter} that describes how the animation should be run:
 * Along some meta-data, this object contains a callback that gets invoked from window manager when
 * the transition is ready to be started.
 * <p>
 * Window manager supplies a list of {@link RemoteAnimationTarget}s into the callback. Each target
 * contains information about the activity that is animating as well as
 * {@link RemoteAnimationTarget#leash}. The controlling app can modify the leash like any other
 * {@link SurfaceControl}, including the possibility to synchronize updating the leash's surface
 * properties with a frame to be drawn using
 * {@link SurfaceControl.Transaction#deferTransactionUntil}.
 * <p>
 * When the animation is done, the controlling app can invoke
 * {@link IRemoteAnimationFinishedCallback} that gets supplied into
 * {@link IRemoteAnimationRunner#onStartAnimation}
 *
 * @hide
 */
public class RemoteAnimationAdapter implements Parcelable {

    private final IRemoteAnimationRunner mRunner;
    private final long mDuration;
    private final long mStatusBarTransitionDelay;
    private final boolean mChangeNeedsSnapshot;

    /** @see #getCallingPid */
    private int mCallingPid;
    private int mCallingUid;

    /** @see #getCallingApplication */
    private IApplicationThread mCallingApplication;

    /**
     * @param runner The interface that gets notified when we actually need to start the animation.
     * @param duration The duration of the animation.
     * @param changeNeedsSnapshot For change transitions, whether this should create a snapshot by
     *                            screenshotting the task.
     * @param statusBarTransitionDelay The desired delay for all visual animations in the
     *        status bar caused by this app animation in millis.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public RemoteAnimationAdapter(IRemoteAnimationRunner runner, long duration,
            long statusBarTransitionDelay, boolean changeNeedsSnapshot) {
        mRunner = runner;
        mDuration = duration;
        mChangeNeedsSnapshot = changeNeedsSnapshot;
        mStatusBarTransitionDelay = statusBarTransitionDelay;
    }

    @UnsupportedAppUsage
    public RemoteAnimationAdapter(IRemoteAnimationRunner runner, long duration,
            long statusBarTransitionDelay) {
        this(runner, duration, statusBarTransitionDelay, false /* changeNeedsSnapshot */);
    }

    @UnsupportedAppUsage
    public RemoteAnimationAdapter(IRemoteAnimationRunner runner, long duration,
            long statusBarTransitionDelay, IApplicationThread callingApplication) {
        this(runner, duration, statusBarTransitionDelay, false /* changeNeedsSnapshot */);
        mCallingApplication = callingApplication;
    }

    public RemoteAnimationAdapter(Parcel in) {
        mRunner = IRemoteAnimationRunner.Stub.asInterface(in.readStrongBinder());
        mDuration = in.readLong();
        mStatusBarTransitionDelay = in.readLong();
        mChangeNeedsSnapshot = in.readBoolean();
        mCallingApplication = IApplicationThread.Stub.asInterface(in.readStrongBinder());
    }

    public IRemoteAnimationRunner getRunner() {
        return mRunner;
    }

    public long getDuration() {
        return mDuration;
    }

    public long getStatusBarTransitionDelay() {
        return mStatusBarTransitionDelay;
    }

    public boolean getChangeNeedsSnapshot() {
        return mChangeNeedsSnapshot;
    }

    /**
     * To be called by system_server to keep track which pid and uid is running this animation.
     */
    public void setCallingPidUid(int pid, int uid) {
        mCallingPid = pid;
        mCallingUid = uid;
    }

    /**
     * @return The pid of the process running the animation.
     */
    public int getCallingPid() {
        return mCallingPid;
    }

    /**
     * @return The uid of the process running the animation.
     */
    public int getCallingUid() {
        return mCallingUid;
    }

    /**
     * Gets the ApplicationThread that will run the animation. Instead it is intended to pass the
     * calling information among client processes (eg. shell + launcher) through one-way binder
     * calls (where binder itself doesn't track calling information).
     */
    public IApplicationThread getCallingApplication() {
        return mCallingApplication;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongInterface(mRunner);
        dest.writeLong(mDuration);
        dest.writeLong(mStatusBarTransitionDelay);
        dest.writeBoolean(mChangeNeedsSnapshot);
        dest.writeStrongInterface(mCallingApplication);
    }

    public static final @android.annotation.NonNull Creator<RemoteAnimationAdapter> CREATOR
            = new Creator<RemoteAnimationAdapter>() {
        public RemoteAnimationAdapter createFromParcel(Parcel in) {
            return new RemoteAnimationAdapter(in);
        }

        public RemoteAnimationAdapter[] newArray(int size) {
            return new RemoteAnimationAdapter[size];
        }
    };
}
