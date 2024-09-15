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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    /** The mode is default defer removing the snapshot starting window. */
    public static final int DEFER_MODE_DEFAULT = 0;

    /** The mode to defer removing the snapshot starting window until IME has drawn. */
    public static final int DEFER_MODE_NORMAL = 1;

    /**
     * The mode to defer the snapshot starting window removal until IME drawn and finished the
     * rotation.
     */
    public static final int DEFER_MODE_ROTATION = 2;

    /** The mode is no need to defer removing the snapshot starting window. */
    public static final int DEFER_MODE_NONE = 3;

    @IntDef(prefix = { "DEFER_MODE_" }, value = {
            DEFER_MODE_DEFAULT,
            DEFER_MODE_NORMAL,
            DEFER_MODE_ROTATION,
            DEFER_MODE_NONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeferMode {}

    /**
     * Whether need to defer removing the snapshot starting window.
     * @hide
     */
    public @DeferMode int deferRemoveMode;

    /**
     * The rounded corner radius
     * @hide
     */
    public float roundedCornerRadius;

    /**
     * Remove windowless surface.
     */
    public boolean windowlessSurface;

    /**
     * Remove immediately.
     */
    public boolean removeImmediately;

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
        deferRemoveMode = source.readInt();
        roundedCornerRadius = source.readFloat();
        windowlessSurface = source.readBoolean();
        removeImmediately = source.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeTypedObject(windowAnimationLeash, flags);
        dest.writeTypedObject(mainFrame, flags);
        dest.writeBoolean(playRevealAnimation);
        dest.writeInt(deferRemoveMode);
        dest.writeFloat(roundedCornerRadius);
        dest.writeBoolean(windowlessSurface);
        dest.writeBoolean(removeImmediately);
    }

    @Override
    public String toString() {
        return "StartingWindowRemovalInfo{taskId=" + taskId
                + " frame=" + mainFrame
                + " playRevealAnimation=" + playRevealAnimation
                + " roundedCornerRadius=" + roundedCornerRadius
                + " deferRemoveMode=" + deferRemoveMode
                + " windowlessSurface=" + windowlessSurface
                + " removeImmediately=" + removeImmediately + "}";
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
