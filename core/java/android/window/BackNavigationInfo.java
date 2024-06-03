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

import android.annotation.AnimRes;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information to be sent to SysUI about a back event.
 *
 * @hide
 */
@TestApi
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
     * @hide
     */
    public static final String KEY_NAVIGATION_FINISHED = "NavigationFinished";

    /**
     * Key to access the boolean value passed in {#mOnBackNavigationDone} result bundle
     * that represents if back gesture has been triggered.
     * @hide
     */
    public static final String KEY_GESTURE_FINISHED = "GestureFinished";


    /**
     * Defines the type of back destinations a back even can lead to. This is used to define the
     * type of animation that need to be run on SystemUI.
     * @hide
     */
    @IntDef(prefix = "TYPE_", value = {
            TYPE_UNDEFINED,
            TYPE_DIALOG_CLOSE,
            TYPE_RETURN_TO_HOME,
            TYPE_CROSS_ACTIVITY,
            TYPE_CROSS_TASK,
            TYPE_CALLBACK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackTargetType {
    }

    private final int mType;
    @Nullable
    private final RemoteCallback mOnBackNavigationDone;
    @Nullable
    private final IOnBackInvokedCallback mOnBackInvokedCallback;
    private final boolean mPrepareRemoteAnimation;
    private final boolean mAnimationCallback;
    @Nullable
    private final CustomAnimationInfo mCustomAnimationInfo;

    private final int mLetterboxColor;

    /**
     * Create a new {@link BackNavigationInfo} instance.
     *
     * @param type                  The {@link BackTargetType} of the destination (what will be
     * @param onBackNavigationDone  The callback to be called once the client is done with the
     *                              back preview.
     * @param onBackInvokedCallback The back callback registered by the current top level window.
     */
    private BackNavigationInfo(@BackTargetType int type,
            @Nullable RemoteCallback onBackNavigationDone,
            @Nullable IOnBackInvokedCallback onBackInvokedCallback,
            boolean isPrepareRemoteAnimation,
            boolean isAnimationCallback,
            @Nullable CustomAnimationInfo customAnimationInfo,
            int letterboxColor) {
        mType = type;
        mOnBackNavigationDone = onBackNavigationDone;
        mOnBackInvokedCallback = onBackInvokedCallback;
        mPrepareRemoteAnimation = isPrepareRemoteAnimation;
        mAnimationCallback = isAnimationCallback;
        mCustomAnimationInfo = customAnimationInfo;
        mLetterboxColor = letterboxColor;
    }

    private BackNavigationInfo(@NonNull Parcel in) {
        mType = in.readInt();
        mOnBackNavigationDone = in.readTypedObject(RemoteCallback.CREATOR);
        mOnBackInvokedCallback = IOnBackInvokedCallback.Stub.asInterface(in.readStrongBinder());
        mPrepareRemoteAnimation = in.readBoolean();
        mAnimationCallback = in.readBoolean();
        mCustomAnimationInfo = in.readTypedObject(CustomAnimationInfo.CREATOR);
        mLetterboxColor = in.readInt();
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeTypedObject(mOnBackNavigationDone, flags);
        dest.writeStrongInterface(mOnBackInvokedCallback);
        dest.writeBoolean(mPrepareRemoteAnimation);
        dest.writeBoolean(mAnimationCallback);
        dest.writeTypedObject(mCustomAnimationInfo, flags);
        dest.writeInt(mLetterboxColor);
    }

    /**
     * Returns the type of back navigation that is about to happen.
     * @hide
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
     * @hide
     * @see OnBackInvokedCallback
     * @see OnBackInvokedDispatcher
     */
    @Nullable
    public IOnBackInvokedCallback getOnBackInvokedCallback() {
        return mOnBackInvokedCallback;
    }

    /**
     * Return true if the core is preparing a back gesture animation.
     * @hide
     */
    public boolean isPrepareRemoteAnimation() {
        return mPrepareRemoteAnimation;
    }

    /**
     * Return true if the callback is {@link OnBackAnimationCallback}.
     * @hide
     */
    public boolean isAnimationCallback() {
        return mAnimationCallback;
    }

    /**
     * @return Letterbox color
     * @hide
     */
    public int getLetterboxColor() {
        return mLetterboxColor;
    }
    /**
     * Callback to be called when the back preview is finished in order to notify the server that
     * it can clean up the resources created for the animation.
     * @hide
     * @param triggerBack Boolean indicating if back navigation has been triggered.
     */
    public void onBackNavigationFinished(boolean triggerBack) {
        if (mOnBackNavigationDone != null) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_NAVIGATION_FINISHED, triggerBack);
            mOnBackNavigationDone.sendResult(result);
        }
    }

    /**
     * Callback to be called when the back gesture is finished in order to notify the server that
     * it can ask app to start rendering.
     * @hide
     * @param triggerBack Boolean indicating if back gesture has been triggered.
     */
    public void onBackGestureFinished(boolean triggerBack) {
        if (mOnBackNavigationDone != null) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_GESTURE_FINISHED, triggerBack);
            mOnBackNavigationDone.sendResult(result);
        }
    }

    /**
     * Get customize animation info.
     * @hide
     */
    @Nullable
    public CustomAnimationInfo getCustomAnimationInfo() {
        return mCustomAnimationInfo;
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
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
                + ", mPrepareRemoteAnimation=" + mPrepareRemoteAnimation
                + ", mAnimationCallback=" + mAnimationCallback
                + ", mCustomizeAnimationInfo=" + mCustomAnimationInfo
                + '}';
    }

    /**
     * Translates the {@link BackNavigationInfo} integer type to its String representation
     */
    @NonNull
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
     * Information for customize back animation.
     * @hide
     */
    public static final class CustomAnimationInfo implements Parcelable {
        private final String mPackageName;
        private int mWindowAnimations;
        @AnimRes private int mCustomExitAnim;
        @AnimRes private int mCustomEnterAnim;
        @ColorInt private int mCustomBackground;

        /**
         * The package name of the windowAnimations.
         */
        @NonNull
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * The resource Id of window animations.
         */
        public int getWindowAnimations() {
            return mWindowAnimations;
        }

        /**
         * The exit animation resource Id of customize activity transition.
         */
        public int getCustomExitAnim() {
            return mCustomExitAnim;
        }

        /**
         * The entering animation resource Id of customize activity transition.
         */
        public int getCustomEnterAnim() {
            return mCustomEnterAnim;
        }

        /**
         * The background color of customize activity transition.
         */
        public int getCustomBackground() {
            return mCustomBackground;
        }

        public CustomAnimationInfo(@NonNull String packageName) {
            this.mPackageName = packageName;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mPackageName);
            dest.writeInt(mWindowAnimations);
            dest.writeInt(mCustomEnterAnim);
            dest.writeInt(mCustomExitAnim);
            dest.writeInt(mCustomBackground);
        }

        private CustomAnimationInfo(@NonNull Parcel in) {
            mPackageName = in.readString8();
            mWindowAnimations = in.readInt();
            mCustomEnterAnim = in.readInt();
            mCustomExitAnim = in.readInt();
            mCustomBackground = in.readInt();
        }

        @Override
        public String toString() {
            return "CustomAnimationInfo, package name= " + mPackageName;
        }

        @NonNull
        public static final Creator<CustomAnimationInfo> CREATOR = new Creator<>() {
            @Override
            public CustomAnimationInfo createFromParcel(Parcel in) {
                return new CustomAnimationInfo(in);
            }

            @Override
            public CustomAnimationInfo[] newArray(int size) {
                return new CustomAnimationInfo[size];
            }
        };
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
        private CustomAnimationInfo mCustomAnimationInfo;
        private boolean mAnimationCallback = false;

        private int mLetterboxColor = Color.TRANSPARENT;

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
         * Set windowAnimations for customize animation.
         */
        public Builder setWindowAnimations(String packageName, int windowAnimations) {
            if (mCustomAnimationInfo == null) {
                mCustomAnimationInfo = new CustomAnimationInfo(packageName);
            }
            mCustomAnimationInfo.mWindowAnimations = windowAnimations;
            return this;
        }

        /**
         * Set resources ids for customize activity animation.
         */
        public Builder setCustomAnimation(String packageName, @AnimRes int enterResId,
                @AnimRes int exitResId, @ColorInt int backgroundColor) {
            if (mCustomAnimationInfo == null) {
                mCustomAnimationInfo = new CustomAnimationInfo(packageName);
            }
            mCustomAnimationInfo.mCustomExitAnim = exitResId;
            mCustomAnimationInfo.mCustomEnterAnim = enterResId;
            mCustomAnimationInfo.mCustomBackground = backgroundColor;
            return this;
        }

        /**
         * @param isAnimationCallback whether the callback is {@link OnBackAnimationCallback}
         */
        public Builder setAnimationCallback(boolean isAnimationCallback) {
            mAnimationCallback = isAnimationCallback;
            return this;
        }

        /**
         * @param color Non-transparent if there contain letterbox color.
         */
        public Builder setLetterboxColor(int color) {
            mLetterboxColor = color;
            return this;
        }

        /**
         * Builds and returns an instance of {@link BackNavigationInfo}
         */
        public BackNavigationInfo build() {
            return new BackNavigationInfo(mType, mOnBackNavigationDone,
                    mOnBackInvokedCallback,
                    mPrepareRemoteAnimation,
                    mAnimationCallback,
                    mCustomAnimationInfo,
                    mLetterboxColor);
        }
    }
}
