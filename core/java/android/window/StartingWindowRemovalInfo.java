/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

/**
 * Information when removing a starting window of a particular task.
 * @hide
 */
public final class StartingWindowRemovalInfo implements Parcelable {

    /**
     * The identifier of a task.
     * @hide
     */
    public int taskId;

    /**
     * The animation container layer of the top activity.
     * @hide
     */
    @Nullable
    public SurfaceControl windowAnimationLeash;

    /**
     * The main window frame for the window of the top activity.
     * @hide
     */
    @Nullable
    public Rect mainFrame;

    /**
     * Whether need to play reveal animation.
     * @hide
     */
    public boolean playRevealAnimation;

    /**
     * Whether need to defer removing the starting window for IME.
     * @hide
     */
    public boolean deferRemoveForIme;

    public StartingWindowRemovalInfo() {

    }

    private StartingWindowRemovalInfo(@NonNull Parcel source) {
        readFromParcel(source);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    void readFromParcel(@NonNull Parcel source) {
        taskId = source.readInt();
        windowAnimationLeash = source.readTypedObject(SurfaceControl.CREATOR);
        mainFrame = source.readTypedObject(Rect.CREATOR);
        playRevealAnimation = source.readBoolean();
        deferRemoveForIme = source.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeTypedObject(windowAnimationLeash, flags);
        dest.writeTypedObject(mainFrame, flags);
        dest.writeBoolean(playRevealAnimation);
        dest.writeBoolean(deferRemoveForIme);
    }

    @Override
    public String toString() {
        return "StartingWindowRemovalInfo{taskId=" + taskId
                + " frame=" + mainFrame
                + " playRevealAnimation=" + playRevealAnimation
                + " deferRemoveForIme=" + deferRemoveForIme + "}";
    }

    public static final @android.annotation.NonNull Creator<StartingWindowRemovalInfo> CREATOR =
            new Creator<StartingWindowRemovalInfo>() {
                public StartingWindowRemovalInfo createFromParcel(@NonNull Parcel source) {
                    return new StartingWindowRemovalInfo(source);
                }
                public StartingWindowRemovalInfo[] newArray(int size) {
                    return new StartingWindowRemovalInfo[size];
                }
            };
}
