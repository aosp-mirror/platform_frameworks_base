/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Data object of params for TaskFragment related {@link WindowContainerTransaction} operation.
 *
 * @see WindowContainerTransaction#setTaskFragmentOperation(IBinder, TaskFragmentOperation).
 * @hide
 */
// TODO(b/263436063): move other TaskFragment related operation here.
public final class TaskFragmentOperation implements Parcelable {

    /** Sets the {@link TaskFragmentAnimationParams} for the given TaskFragment. */
    public static final int OP_TYPE_SET_ANIMATION_PARAMS = 0;

    @IntDef(prefix = { "OP_TYPE_" }, value = {
            OP_TYPE_SET_ANIMATION_PARAMS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface OperationType {}

    @OperationType
    private final int mOpType;

    @Nullable
    private final TaskFragmentAnimationParams mAnimationParams;

    private TaskFragmentOperation(@OperationType int opType,
            @Nullable TaskFragmentAnimationParams animationParams) {
        mOpType = opType;
        mAnimationParams = animationParams;
    }

    private TaskFragmentOperation(Parcel in) {
        mOpType = in.readInt();
        mAnimationParams = in.readTypedObject(TaskFragmentAnimationParams.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mOpType);
        dest.writeTypedObject(mAnimationParams, flags);
    }

    @NonNull
    public static final Creator<TaskFragmentOperation> CREATOR =
            new Creator<TaskFragmentOperation>() {
                @Override
                public TaskFragmentOperation createFromParcel(Parcel in) {
                    return new TaskFragmentOperation(in);
                }

                @Override
                public TaskFragmentOperation[] newArray(int size) {
                    return new TaskFragmentOperation[size];
                }
            };

    /**
     * Gets the {@link OperationType} of this {@link TaskFragmentOperation}.
     */
    @OperationType
    public int getOpType() {
        return mOpType;
    }

    /**
     * Gets the animation related override of TaskFragment.
     */
    @Nullable
    public TaskFragmentAnimationParams getAnimationParams() {
        return mAnimationParams;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("TaskFragmentOperation{ opType=").append(mOpType);
        if (mAnimationParams != null) {
            sb.append(", animationParams=").append(mAnimationParams);
        }

        sb.append('}');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = mOpType;
        result = result * 31 + mAnimationParams.hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof TaskFragmentOperation)) {
            return false;
        }
        final TaskFragmentOperation other = (TaskFragmentOperation) obj;
        return mOpType == other.mOpType
                && Objects.equals(mAnimationParams, other.mAnimationParams);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder to construct the {@link TaskFragmentOperation}. */
    public static final class Builder {

        @OperationType
        private final int mOpType;

        @Nullable
        private TaskFragmentAnimationParams mAnimationParams;

        /**
         * @param opType the {@link OperationType} of this {@link TaskFragmentOperation}.
         */
        public Builder(@OperationType int opType) {
            mOpType = opType;
        }

        /**
         * Sets the {@link TaskFragmentAnimationParams} for the given TaskFragment.
         */
        @NonNull
        public Builder setAnimationParams(@Nullable TaskFragmentAnimationParams animationParams) {
            mAnimationParams = animationParams;
            return this;
        }

        /**
         * Constructs the {@link TaskFragmentOperation}.
         */
        @NonNull
        public TaskFragmentOperation build() {
            return new TaskFragmentOperation(mOpType, mAnimationParams);
        }
    }
}
