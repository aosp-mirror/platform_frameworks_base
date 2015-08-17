package android.view;

import android.graphics.Bitmap;
import android.graphics.Rect;
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
    public final Bitmap bitmap;
    public final Rect rect;

    public AppTransitionAnimationSpec(int taskId, Bitmap bitmap, Rect rect) {
        this.taskId = taskId;
        this.bitmap = bitmap;
        this.rect = rect;
    }

    public AppTransitionAnimationSpec(Parcel in) {
        taskId = in.readInt();
        bitmap = in.readParcelable(null);
        rect = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeParcelable(bitmap, 0 /* flags */);
        dest.writeParcelable(rect, 0 /* flags */);

    }

    public static final Parcelable.Creator<AppTransitionAnimationSpec> CREATOR
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
        return "{taskId: " + taskId + ", bitmap: " + bitmap + ", rect: " + rect + "}";
    }
}
