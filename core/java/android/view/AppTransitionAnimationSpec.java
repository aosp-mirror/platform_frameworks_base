package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Holds information about how the next app transition animation should be executed.
 *
 * This class is intended to be used with IWindowManager.overridePendingAppTransition* methods when
 * simple arguments are not enough to describe the animation.
 *
 * @hide
 */
public class AppTransitionAnimationSpec implements Parcelable {
    public final int taskId;
    public final HardwareBuffer buffer;
    public final Rect rect;

    @UnsupportedAppUsage
    public AppTransitionAnimationSpec(int taskId, HardwareBuffer buffer, Rect rect) {
        this.taskId = taskId;
        this.rect = rect;
        this.buffer = buffer;
    }

    public AppTransitionAnimationSpec(Parcel in) {
        taskId = in.readInt();
        rect = in.readParcelable(null);
        buffer = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeParcelable(rect, 0 /* flags */);
        dest.writeParcelable(buffer, 0);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AppTransitionAnimationSpec> CREATOR
            = new Parcelable.Creator<AppTransitionAnimationSpec>() {
        public AppTransitionAnimationSpec createFromParcel(Parcel in) {
            return new AppTransitionAnimationSpec(in);
        }

        public AppTransitionAnimationSpec[] newArray(int size) {
            return new AppTransitionAnimationSpec[size];
        }
    };

    @Override
    public String toString() {
        return "{taskId: " + taskId + ", buffer: " + buffer + ", rect: " + rect + "}";
    }
}
