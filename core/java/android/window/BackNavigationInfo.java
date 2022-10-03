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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;

/**
 * Information to be sent to SysUI about a back event.
 *
 * @hide
 */
public final class BackNavigationInfo implements Parcelable {

    /**
     * The target of the back navigation is undefined.
     */
    public static final int TYPE_UNDEFINED = -1;

    /**
     * Navigating back will close the currently visible dialog
     */
    public static final int TYPE_DIALOG_CLOSE = 0;

    /**
     * Navigating back will bring the user back to the home screen
     */
    public static final int TYPE_RETURN_TO_HOME = 1;

    /**
     * Navigating back will bring the user to the previous activity in the same Task
     */
    public static final int TYPE_CROSS_ACTIVITY = 2;

    /**
     * Navigating back will bring the user to the previous activity in the previous Task
     */
    public static final int TYPE_CROSS_TASK = 3;

    /**
     * A {@link OnBackInvokedCallback} is available and needs to be called.
     * <p>
     */
    public static final int TYPE_CALLBACK = 4;

    /**
     * Key to access the boolean value passed in {#mOnBackNavigationDone} result bundle
     * that represents if back navigation has been triggered.
     */
    public static final String KEY_TRIGGER_BACK = "TriggerBack";

    /**
     * Defines the type of back destinations a back even can lead to. This is used to define the
     * type of animation that need to be run on SystemUI.
     */
    @IntDef(prefix = "TYPE_", value = {
            TYPE_UNDEFINED,
            TYPE_DIALOG_CLOSE,
            TYPE_RETURN_TO_HOME,
            TYPE_CROSS_ACTIVITY,
            TYPE_CROSS_TASK,
            TYPE_CALLBACK
    })
    public @interface BackTargetType {
    }

    private final int mType;
    @Nullable
    private final RemoteCallback mOnBackNavigationDone;
    @Nullable
    private final IOnBackInvokedCallback mOnBackInvokedCallback;
    private final boolean mPrepareRemoteAnimation;
    @Nullable
    private WindowContainerToken mDepartingWindowContainerToken;

    /**
     * Create a new {@link BackNavigationInfo} instance.
     *
     * @param type                    The {@link BackTargetType} of the destination (what will be
     * @param onBackNavigationDone    The callback to be called once the client is done with the
     *                                back preview.
     * @param onBackInvokedCallback   The back callback registered by the current top level window.
     * @param departingWindowContainerToken The {@link WindowContainerToken} of departing window.
     * @param isPrepareRemoteAnimation  Return whether the core is preparing a back gesture
     *                                  animation, if true, the caller of startBackNavigation should
     *                                  be expected to receive an animation start callback.
     */
    private BackNavigationInfo(@BackTargetType int type,
            @Nullable RemoteCallback onBackNavigationDone,
            @Nullable IOnBackInvokedCallback onBackInvokedCallback,
            boolean isPrepareRemoteAnimation,
            @Nullable WindowContainerToken departingWindowContainerToken) {
        mType = type;
        mOnBackNavigationDone = onBackNavigationDone;
        mOnBackInvokedCallback = onBackInvokedCallback;
        mPrepareRemoteAnimation = isPrepareRemoteAnimation;
        mDepartingWindowContainerToken = departingWindowContainerToken;
    }

    private BackNavigationInfo(@NonNull Parcel in) {
        mType = in.readInt();
        mOnBackNavigationDone = in.readTypedObject(RemoteCallback.CREATOR);
        mOnBackInvokedCallback = IOnBackInvokedCallback.Stub.asInterface(in.readStrongBinder());
        mPrepareRemoteAnimation = in.readBoolean();
        mDepartingWindowContainerToken = in.readTypedObject(WindowContainerToken.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeTypedObject(mOnBackNavigationDone, flags);
        dest.writeStrongInterface(mOnBackInvokedCallback);
        dest.writeBoolean(mPrepareRemoteAnimation);
        dest.writeTypedObject(mDepartingWindowContainerToken, flags);
    }

    /**
     * Returns the type of back navigation that is about to happen.
     *
     * @see BackTargetType
     */
    public @BackTargetType int getType() {
        return mType;
    }

    /**
     * Returns the {@link OnBackInvokedCallback} of the top level window or null if
     * the client didn't register a callback.
     * <p>
     * This is never null when {@link #getType} returns {@link #TYPE_CALLBACK}.
     *
     * @see OnBackInvokedCallback
     * @see OnBackInvokedDispatcher
     */
    @Nullable
    public IOnBackInvokedCallback getOnBackInvokedCallback() {
        return mOnBackInvokedCallback;
    }

    /**
     * Return true if the core is preparing a back gesture nimation.
     */
    public boolean isPrepareRemoteAnimation() {
        return mPrepareRemoteAnimation;
    }

    /**
     * Returns the {@link WindowContainerToken} of the highest container in the hierarchy being
     * removed.
     * <p>
     * For example, if an Activity is the last one of its Task, the Task's token will be given.
     * Otherwise, it will be the Activity's token.
     */
    @Nullable
    public WindowContainerToken getDepartingWindowContainerToken() {
        return mDepartingWindowContainerToken;
    }

    /**
     * Callback to be called when the back preview is finished in order to notify the server that
     * it can clean up the resources created for the animation.
     *
     * @param triggerBack Boolean indicating if back navigation has been triggered.
     */
    public void onBackNavigationFinished(boolean triggerBack) {
        if (mOnBackNavigationDone != null) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_TRIGGER_BACK, triggerBack);
            mOnBackNavigationDone.sendResult(result);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BackNavigationInfo> CREATOR = new Creator<BackNavigationInfo>() {
        @Override
        public BackNavigationInfo createFromParcel(Parcel in) {
            return new BackNavigationInfo(in);
        }

        @Override
        public BackNavigationInfo[] newArray(int size) {
            return new BackNavigationInfo[size];
        }
    };

    @Override
    public String toString() {
        return "BackNavigationInfo{"
                + "mType=" + typeToString(mType) + " (" + mType + ")"
                + ", mOnBackNavigationDone=" + mOnBackNavigationDone
                + ", mOnBackInvokedCallback=" + mOnBackInvokedCallback
                + ", mWindowContainerToken=" + mDepartingWindowContainerToken
                + '}';
    }

    /**
     * Translates the {@link BackNavigationInfo} integer type to its String representation
     */
    public static String typeToString(@BackTargetType int type) {
        switch (type) {
            case TYPE_UNDEFINED:
                return "TYPE_UNDEFINED";
            case TYPE_DIALOG_CLOSE:
                return "TYPE_DIALOG_CLOSE";
            case TYPE_RETURN_TO_HOME:
                return "TYPE_RETURN_TO_HOME";
            case TYPE_CROSS_ACTIVITY:
                return "TYPE_CROSS_ACTIVITY";
            case TYPE_CROSS_TASK:
                return "TYPE_CROSS_TASK";
            case TYPE_CALLBACK:
                return "TYPE_CALLBACK";
        }
        return String.valueOf(type);
    }

    /**
     * @hide
     */
    @SuppressWarnings("UnusedReturnValue") // Builder pattern
    public static class Builder {
        private int mType = TYPE_UNDEFINED;
        @Nullable
        private RemoteCallback mOnBackNavigationDone = null;
        @Nullable
        private IOnBackInvokedCallback mOnBackInvokedCallback = null;
        private boolean mPrepareRemoteAnimation;
        @Nullable
        private WindowContainerToken mDepartingWindowContainerToken = null;

        /**
         * @see BackNavigationInfo#getType()
         */
        public Builder setType(@BackTargetType int type) {
            mType = type;
            return this;
        }

        /**
         * @see BackNavigationInfo#onBackNavigationFinished(boolean)
         */
        public Builder setOnBackNavigationDone(@Nullable RemoteCallback onBackNavigationDone) {
            mOnBackNavigationDone = onBackNavigationDone;
            return this;
        }

        /**
         * @see BackNavigationInfo#getOnBackInvokedCallback
         */
        public Builder setOnBackInvokedCallback(
                @Nullable IOnBackInvokedCallback onBackInvokedCallback) {
            mOnBackInvokedCallback = onBackInvokedCallback;
            return this;
        }

        /**
         * @param prepareRemoteAnimation Whether core prepare animation for shell.
         */
        public Builder setPrepareRemoteAnimation(boolean prepareRemoteAnimation) {
            mPrepareRemoteAnimation = prepareRemoteAnimation;
            return this;
        }

        /**
         * @see BackNavigationInfo#getDepartingWindowContainerToken()
         */
        public void setDepartingWCT(@NonNull WindowContainerToken windowContainerToken) {
            mDepartingWindowContainerToken = windowContainerToken;
        }

        /**
         * Builds and returns an instance of {@link BackNavigationInfo}
         */
        public BackNavigationInfo build() {
            return new BackNavigationInfo(mType, mOnBackNavigationDone,
                    mOnBackInvokedCallback,
                    mPrepareRemoteAnimation,
                    mDepartingWindowContainerToken);
        }
    }
}
