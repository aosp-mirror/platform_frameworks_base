/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.MergedConfiguration;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

/**
 * Stores information to pass to {@link IWindowSession#relayout} as AIDL out type.
 * @hide
 */
public final class WindowRelayoutResult implements Parcelable {

    /** The window frames used by the client side for layout. */
    @NonNull
    public final ClientWindowFrames frames;

    /**
     * New config container that holds global, override and merged config for window, if it is now
     * becoming visible and the merged config has changed since it was last displayed.
     */
    @NonNull
    public final MergedConfiguration mergedConfiguration;

    /** Object in which is placed the new display surface. */
    @NonNull
    public final SurfaceControl surfaceControl;

    /** The current insets state in the system. */
    @NonNull
    public final InsetsState insetsState;

    /** Objects which allow controlling {@link InsetsSource}s. */
    @NonNull
    public final InsetsSourceControl.Array activeControls;

    /** The latest sync seq id for the relayout configuration. */
    public int syncSeqId;

    /**
     * The latest {@link ActivityWindowInfo} if this relayout window is an Activity window.
     * {@code null} if this is not an Activity window.
     */
    @Nullable
    public ActivityWindowInfo activityWindowInfo;

    public WindowRelayoutResult() {
        this(new ClientWindowFrames(), new MergedConfiguration(), new SurfaceControl(),
                new InsetsState(), new InsetsSourceControl.Array());
    }

    /**
     * Stores information to pass for {@link IWindowSession#relayout}.
     *
     * @param frames              The window frames used by the client side for layout.
     * @param mergedConfiguration New config container that holds global, override and merged
     *                            config for window, if it is now becoming visible and the merged
     *                            config has changed since it was last displayed.
     * @param surfaceControl      Object in which is placed the new display surface.
     * @param insetsState         The current insets state in the system.
     * @param activeControls      Objects which allow controlling {@link InsetsSource}s.
     */
    public WindowRelayoutResult(@NonNull ClientWindowFrames frames,
            @NonNull MergedConfiguration mergedConfiguration,
            @NonNull SurfaceControl surfaceControl, @NonNull InsetsState insetsState,
            @NonNull InsetsSourceControl.Array activeControls) {
        this.frames = requireNonNull(frames);
        this.mergedConfiguration = requireNonNull(mergedConfiguration);
        this.surfaceControl = requireNonNull(surfaceControl);
        this.insetsState = requireNonNull(insetsState);
        this.activeControls = requireNonNull(activeControls);
    }

    private WindowRelayoutResult(@NonNull Parcel in) {
        this();
        readFromParcel(in);
    }

    /** Needed for IBinder out parameter. */
    public void readFromParcel(@NonNull Parcel in) {
        frames.readFromParcel(in);
        mergedConfiguration.readFromParcel(in);
        surfaceControl.readFromParcel(in);
        insetsState.readFromParcel(in);
        activeControls.readFromParcel(in);
        syncSeqId = in.readInt();
        activityWindowInfo = in.readTypedObject(ActivityWindowInfo.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        frames.writeToParcel(dest, flags);
        mergedConfiguration.writeToParcel(dest, flags);
        surfaceControl.writeToParcel(dest, flags);
        insetsState.writeToParcel(dest, flags);
        activeControls.writeToParcel(dest, flags);
        dest.writeInt(syncSeqId);
        dest.writeTypedObject(activityWindowInfo, flags);
    }

    @NonNull
    public static final Creator<WindowRelayoutResult> CREATOR =
            new Creator<>() {
                @Override
                public WindowRelayoutResult createFromParcel(@NonNull Parcel in) {
                    return new WindowRelayoutResult(in);
                }

                @Override
                public WindowRelayoutResult[] newArray(int size) {
                    return new WindowRelayoutResult[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }
}
