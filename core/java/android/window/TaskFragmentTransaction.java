/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Used to communicate information about what are changing on embedded TaskFragments belonging to
 * the same TaskFragmentOrganizer. A transaction can contain multiple changes.
 * @see TaskFragmentTransaction.Change
 * @hide
 */
@TestApi
public final class TaskFragmentTransaction implements Parcelable {

    /** Unique token to represent this transaction. */
    private final IBinder mTransactionToken;

    /** Changes in this transaction. */
    private final ArrayList<Change> mChanges = new ArrayList<>();

    public TaskFragmentTransaction() {
        mTransactionToken = new Binder();
    }

    private TaskFragmentTransaction(Parcel in) {
        mTransactionToken = in.readStrongBinder();
        in.readTypedList(mChanges, Change.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mTransactionToken);
        dest.writeTypedList(mChanges);
    }

    @NonNull
    public IBinder getTransactionToken() {
        return mTransactionToken;
    }

    /** Adds a {@link Change} to this transaction. */
    public void addChange(@Nullable Change change) {
        if (change != null) {
            mChanges.add(change);
        }
    }

    /** Whether this transaction contains any {@link Change}. */
    public boolean isEmpty() {
        return mChanges.isEmpty();
    }

    @NonNull
    public List<Change> getChanges() {
        return mChanges;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TaskFragmentTransaction{token=");
        sb.append(mTransactionToken);
        sb.append(" changes=[");
        for (int i = 0; i < mChanges.size(); ++i) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mChanges.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<TaskFragmentTransaction> CREATOR = new Creator<>() {
        @Override
        public TaskFragmentTransaction createFromParcel(Parcel in) {
            return new TaskFragmentTransaction(in);
        }

        @Override
        public TaskFragmentTransaction[] newArray(int size) {
            return new TaskFragmentTransaction[size];
        }
    };

    /** Change type: the TaskFragment is attached to the hierarchy. */
    public static final int TYPE_TASK_FRAGMENT_APPEARED = 1;

    /** Change type: the status of the TaskFragment is changed. */
    public static final int TYPE_TASK_FRAGMENT_INFO_CHANGED = 2;

    /** Change type: the TaskFragment is removed from the hierarchy. */
    public static final int TYPE_TASK_FRAGMENT_VANISHED = 3;

    /** Change type: the status of the parent leaf Task is changed. */
    public static final int TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED = 4;

    /** Change type: the TaskFragment related operation failed on the server side. */
    public static final int TYPE_TASK_FRAGMENT_ERROR = 5;

    /**
     * Change type: an Activity is reparented to the Task. For example, when an Activity enters and
     * then exits Picture-in-picture, it will be reparented back to its original Task. In this case,
     * we need to notify the organizer so that it can check if the Activity matches any split rule.
     */
    public static final int TYPE_ACTIVITY_REPARENTED_TO_TASK = 6;

    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_TASK_FRAGMENT_APPEARED,
            TYPE_TASK_FRAGMENT_INFO_CHANGED,
            TYPE_TASK_FRAGMENT_VANISHED,
            TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED,
            TYPE_TASK_FRAGMENT_ERROR,
            TYPE_ACTIVITY_REPARENTED_TO_TASK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ChangeType {}

    /** Represents the change an embedded TaskFragment undergoes. */
    public static final class Change implements Parcelable {

        /** @see ChangeType */
        @ChangeType
        private final int mType;

        /** @see #setTaskFragmentToken(IBinder) */
        @Nullable
        private IBinder mTaskFragmentToken;

        /** @see #setTaskFragmentInfo(TaskFragmentInfo) */
        @Nullable
        private TaskFragmentInfo mTaskFragmentInfo;

        /** @see #setTaskId(int) */
        private int mTaskId;

        /** @see #setErrorCallbackToken(IBinder) */
        @Nullable
        private IBinder mErrorCallbackToken;

        /** @see #setErrorBundle(Bundle) */
        @Nullable
        private Bundle mErrorBundle;

        /** @see #setActivityIntent(Intent) */
        @Nullable
        private Intent mActivityIntent;

        /** @see #setActivityToken(IBinder) */
        @Nullable
        private IBinder mActivityToken;

        @Nullable
        private TaskFragmentParentInfo mTaskFragmentParentInfo;

        @Nullable
        private SurfaceControl mSurfaceControl;

        public Change(@ChangeType int type) {
            mType = type;
        }

        private Change(Parcel in) {
            mType = in.readInt();
            mTaskFragmentToken = in.readStrongBinder();
            mTaskFragmentInfo = in.readTypedObject(TaskFragmentInfo.CREATOR);
            mTaskId = in.readInt();
            mErrorCallbackToken = in.readStrongBinder();
            mErrorBundle = in.readBundle(TaskFragmentTransaction.class.getClassLoader());
            mActivityIntent = in.readTypedObject(Intent.CREATOR);
            mActivityToken = in.readStrongBinder();
            mTaskFragmentParentInfo = in.readTypedObject(TaskFragmentParentInfo.CREATOR);
            mSurfaceControl = in.readTypedObject(SurfaceControl.CREATOR);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeStrongBinder(mTaskFragmentToken);
            dest.writeTypedObject(mTaskFragmentInfo, flags);
            dest.writeInt(mTaskId);
            dest.writeStrongBinder(mErrorCallbackToken);
            dest.writeBundle(mErrorBundle);
            dest.writeTypedObject(mActivityIntent, flags);
            dest.writeStrongBinder(mActivityToken);
            dest.writeTypedObject(mTaskFragmentParentInfo, flags);
            dest.writeTypedObject(mSurfaceControl, flags);
        }

        /** The change is related to the TaskFragment created with this unique token. */
        @NonNull
        public Change setTaskFragmentToken(@NonNull IBinder taskFragmentToken) {
            mTaskFragmentToken = requireNonNull(taskFragmentToken);
            return this;
        }

        /** Info of the embedded TaskFragment. */
        @NonNull
        public Change setTaskFragmentInfo(@NonNull TaskFragmentInfo info) {
            mTaskFragmentInfo = requireNonNull(info);
            return this;
        }

        /** Task id the parent Task. */
        @NonNull
        public Change setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        // TODO(b/241043377): Keep this API to prevent @TestApi changes. Remove in the next release.
        /** Configuration of the parent Task. */
        @NonNull
        public Change setTaskConfiguration(@NonNull Configuration configuration) {
            return this;
        }

        /**
         * If the {@link #TYPE_TASK_FRAGMENT_ERROR} is from a {@link WindowContainerTransaction}
         * from the {@link TaskFragmentOrganizer}, it may come with an error callback token to
         * report back.
         */
        @NonNull
        public Change setErrorCallbackToken(@Nullable IBinder errorCallbackToken) {
            mErrorCallbackToken = errorCallbackToken;
            return this;
        }

        /**
         * Bundle with necessary info about the failure operation of
         * {@link #TYPE_TASK_FRAGMENT_ERROR}.
         */
        @NonNull
        public Change setErrorBundle(@NonNull Bundle errorBundle) {
            mErrorBundle = requireNonNull(errorBundle);
            return this;
        }

        /**
         * Intent of the activity that is reparented to the Task for
         * {@link #TYPE_ACTIVITY_REPARENTED_TO_TASK}.
         */
        @NonNull
        public Change setActivityIntent(@NonNull Intent intent) {
            mActivityIntent = requireNonNull(intent);
            return this;
        }

        /**
         * Token of the reparent activity for {@link #TYPE_ACTIVITY_REPARENTED_TO_TASK}.
         * If the activity belongs to the same process as the organizer, this will be the actual
         * activity token; if the activity belongs to a different process, the server will generate
         * a temporary token that the organizer can use to reparent the activity through
         * {@link WindowContainerTransaction} if needed.
         */
        @NonNull
        public Change setActivityToken(@NonNull IBinder activityToken) {
            mActivityToken = requireNonNull(activityToken);
            return this;
        }

        // TODO(b/241043377): Hide this API to prevent @TestApi changes. Remove in the next release.
        /**
         * Sets info of the parent Task of the embedded TaskFragment.
         * @see TaskFragmentParentInfo
         *
         * @hide pending unhide
         */
        @NonNull
        public Change setTaskFragmentParentInfo(@NonNull TaskFragmentParentInfo info) {
            mTaskFragmentParentInfo = requireNonNull(info);
            return this;
        }

        /** @hide */
        @NonNull
        public Change setTaskFragmentSurfaceControl(@Nullable SurfaceControl sc) {
            mSurfaceControl = sc;
            return this;
        }

        @ChangeType
        public int getType() {
            return mType;
        }

        @Nullable
        public IBinder getTaskFragmentToken() {
            return mTaskFragmentToken;
        }

        @Nullable
        public TaskFragmentInfo getTaskFragmentInfo() {
            return mTaskFragmentInfo;
        }

        public int getTaskId() {
            return mTaskId;
        }

        // TODO(b/241043377): Keep this API to prevent @TestApi changes. Remove in the next release.
        @Nullable
        public Configuration getTaskConfiguration() {
            return mTaskFragmentParentInfo.getConfiguration();
        }

        @Nullable
        public IBinder getErrorCallbackToken() {
            return mErrorCallbackToken;
        }

        @NonNull
        public Bundle getErrorBundle() {
            return mErrorBundle != null ? mErrorBundle : Bundle.EMPTY;
        }

        @SuppressLint("IntentBuilderName") // This is not creating new Intent.
        @Nullable
        public Intent getActivityIntent() {
            return mActivityIntent;
        }

        @Nullable
        public IBinder getActivityToken() {
            return mActivityToken;
        }

        // TODO(b/241043377): Hide this API to prevent @TestApi changes. Remove in the next release.
        /** @hide pending unhide */
        @Nullable
        public TaskFragmentParentInfo getTaskFragmentParentInfo() {
            return mTaskFragmentParentInfo;
        }

        /**
         * Gets the {@link SurfaceControl} of the TaskFragment. This field is {@code null} for
         * a regular {@link TaskFragmentOrganizer} and is only available for a system
         * {@link TaskFragmentOrganizer} in the
         * {@link TaskFragmentTransaction#TYPE_TASK_FRAGMENT_APPEARED} event. See
         * {@link ITaskFragmentOrganizerController#registerOrganizer(ITaskFragmentOrganizer,
         * boolean)}
         *
         * @hide
         */
        @Nullable
        public SurfaceControl getTaskFragmentSurfaceControl() {
            return mSurfaceControl;
        }

        @Override
        public String toString() {
            return "Change{ type=" + mType + " }";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Creator<Change> CREATOR = new Creator<>() {
            @Override
            public Change createFromParcel(Parcel in) {
                return new Change(in);
            }

            @Override
            public Change[] newArray(int size) {
                return new Change[size];
            }
        };
    }
}
