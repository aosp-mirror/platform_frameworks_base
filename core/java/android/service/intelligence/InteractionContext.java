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
package android.service.intelligence;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// TODO(b/111276913): add javadocs / implement Parcelable / implement equals/hashcode/toString
/** @hide */
@SystemApi
public final class InteractionContext implements Parcelable {

    /**
     * Flag used to indicate that the app explicitly disabled content capture for the activity
     * (using
     * {@link android.view.intelligence.IntelligenceManager#disableContentCapture()}),
     * in which case the service will just receive activity-level events.
     */
    public static final int FLAG_DISABLED_BY_APP = 0x1;

    /**
     * Flag used to indicate that the activity's window is tagged with
     * {@link android.view.Display#FLAG_SECURE}, in which case the service will just receive
     * activity-level events.
     */
    public static final int FLAG_DISABLED_BY_FLAG_SECURE = 0x2;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_DISABLED_BY_APP,
            FLAG_DISABLED_BY_FLAG_SECURE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContextCreationFlags{}

    // TODO(b/111276913): create new object for taskId + componentName / reuse on other places
    private final @NonNull ComponentName mComponentName;
    private final int mTaskId;
    private final int mDisplayId;
    private final int mFlags;


    /** @hide */
    public InteractionContext(@NonNull ComponentName componentName, int taskId, int displayId,
            int flags) {
        mComponentName = Preconditions.checkNotNull(componentName);
        mTaskId = taskId;
        mDisplayId = displayId;
        mFlags = flags;
    }

    /**
     * Gets the id of the {@link TaskInfo task} associated with this context.
     */
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * Gets the activity associated with this context.
     */
    public @NonNull ComponentName getActivityComponent() {
        return mComponentName;
    }

    /**
     * Gets the ID of the display associated with this context, as defined by
     * {G android.hardware.display.DisplayManager#getDisplay(int) DisplayManager.getDisplay()}.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Gets the flags associated with this context.
     *
     * @return any combination of {@link #FLAG_DISABLED_BY_FLAG_SECURE} and
     * {@link #FLAG_DISABLED_BY_APP}.
     */
    public @ContextCreationFlags int getFlags() {
        return mFlags;
    }

    /**
     * @hide
     */
    // TODO(b/111276913): dump to proto as well
    public void dump(PrintWriter pw) {
        pw.print("comp="); pw.print(mComponentName.flattenToShortString());
        pw.print(", taskId="); pw.print(mTaskId);
        pw.print(", displayId="); pw.print(mDisplayId);
        if (mFlags > 0) {
            pw.print(", flags="); pw.print(mFlags);
        }
    }

    @Override
    public String toString() {
        return "Context[act=" + mComponentName.flattenToShortString() + ", taskId=" + mTaskId
                + ", displayId=" + mDisplayId + ", flags=" + mFlags + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mComponentName, flags);
        parcel.writeInt(mTaskId);
        parcel.writeInt(mDisplayId);
        parcel.writeInt(mFlags);
    }

    public static final Parcelable.Creator<InteractionContext> CREATOR =
            new Parcelable.Creator<InteractionContext>() {

        @Override
        public InteractionContext createFromParcel(Parcel parcel) {
            final ComponentName componentName = parcel.readParcelable(null);
            final int taskId = parcel.readInt();
            final int displayId = parcel.readInt();
            final int flags = parcel.readInt();
            return new InteractionContext(componentName, taskId, displayId, flags);
        }

        @Override
        public InteractionContext[] newArray(int size) {
            return new InteractionContext[size];
        }
    };
}
