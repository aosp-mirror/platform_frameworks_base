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
import android.app.WindowConfiguration;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

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
    @interface BackTargetType {
    }

    private final int mType;
    @Nullable
    private final RemoteAnimationTarget mDepartingAnimationTarget;
    @Nullable
    private final SurfaceControl mScreenshotSurface;
    @Nullable
    private final HardwareBuffer mScreenshotBuffer;
    @Nullable
    private final RemoteCallback mOnBackNavigationDone;
    @Nullable
    private final WindowConfiguration mTaskWindowConfiguration;
    @Nullable
    private final IOnBackInvokedCallback mOnBackInvokedCallback;

    private final boolean mIsPrepareRemoteAnimation;

    /**
     * Create a new {@link BackNavigationInfo} instance.
     *
     * @param type                    The {@link BackTargetType} of the destination (what will be
     *                                displayed after the back action).
     * @param departingAnimationTarget  The remote animation target, containing a leash to animate
     *                                  away the departing window. The consumer of the leash is
     *                                  responsible for removing it.
     * @param screenshotSurface       The screenshot of the previous activity to be displayed.
     * @param screenshotBuffer        A buffer containing a screenshot used to display the activity.
     *                                See {@link  #getScreenshotHardwareBuffer()} for information
     *                                about nullity.
     * @param taskWindowConfiguration The window configuration of the Task being animated beneath.
     * @param onBackNavigationDone    The callback to be called once the client is done with the
     *                                back preview.
     * @param onBackInvokedCallback   The back callback registered by the current top level window.
     * @param isPrepareRemoteAnimation  Return whether the core is preparing a back gesture
     *                                  animation, if true, the caller of startBackNavigation should
     *                                  be expected to receive an animation start callback.
     */
    private BackNavigationInfo(@BackTargetType int type,
            @Nullable RemoteAnimationTarget departingAnimationTarget,
            @Nullable SurfaceControl screenshotSurface,
            @Nullable HardwareBuffer screenshotBuffer,
            @Nullable WindowConfiguration taskWindowConfiguration,
            @Nullable RemoteCallback onBackNavigationDone,
            @Nullable IOnBackInvokedCallback onBackInvokedCallback,
            boolean isPrepareRemoteAnimation) {
        mType = type;
        mDepartingAnimationTarget = departingAnimationTarget;
        mScreenshotSurface = screenshotSurface;
        mScreenshotBuffer = screenshotBuffer;
        mTaskWindowConfiguration = taskWindowConfiguration;
        mOnBackNavigationDone = onBackNavigationDone;
        mOnBackInvokedCallback = onBackInvokedCallback;
        mIsPrepareRemoteAnimation = isPrepareRemoteAnimation;
    }

    private BackNavigationInfo(@NonNull Parcel in) {
        mType = in.readInt();
        mDepartingAnimationTarget = in.readTypedObject(RemoteAnimationTarget.CREATOR);
        mScreenshotSurface = in.readTypedObject(SurfaceControl.CREATOR);
        mScreenshotBuffer = in.readTypedObject(HardwareBuffer.CREATOR);
        mTaskWindowConfiguration = in.readTypedObject(WindowConfiguration.CREATOR);
        mOnBackNavigationDone = in.readTypedObject(RemoteCallback.CREATOR);
        mOnBackInvokedCallback = IOnBackInvokedCallback.Stub.asInterface(in.readStrongBinder());
        mIsPrepareRemoteAnimation = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeTypedObject(mDepartingAnimationTarget, flags);
        dest.writeTypedObject(mScreenshotSurface, flags);
        dest.writeTypedObject(mScreenshotBuffer, flags);
        dest.writeTypedObject(mTaskWindowConfiguration, flags);
        dest.writeTypedObject(mOnBackNavigationDone, flags);
        dest.writeStrongInterface(mOnBackInvokedCallback);
        dest.writeBoolean(mIsPrepareRemoteAnimation);
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
     * Returns a {@link RemoteAnimationTarget}, containing a leash to the top window container
     * that needs to be animated. This can be null if the back animation is controlled by
     * the application.
     */
    @Nullable
    public RemoteAnimationTarget getDepartingAnimationTarget() {
        return mDepartingAnimationTarget;
    }

    /**
     * Returns the {@link SurfaceControl} that should be used to display a screenshot of the
     * previous activity.
     */
    @Nullable
    public SurfaceControl getScreenshotSurface() {
        return mScreenshotSurface;
    }

    /**
     * Returns the {@link HardwareBuffer} containing the screenshot the activity about to be
     * shown. This can be null if one of the following conditions is met:
     * <ul>
     *     <li>The screenshot is not available
     *     <li> The previous activity is the home screen ( {@link  #TYPE_RETURN_TO_HOME}
     *     <li> The current window is a dialog ({@link  #TYPE_DIALOG_CLOSE}
     *     <li> The back animation is controlled by the application
     * </ul>
     */
    @Nullable
    public HardwareBuffer getScreenshotHardwareBuffer() {
        return mScreenshotBuffer;
    }

    /**
     * Returns the {@link WindowConfiguration} of the current task. This is null when the top
     * application is controlling the back animation.
     */
    @Nullable
    public WindowConfiguration getTaskWindowConfiguration() {
        return mTaskWindowConfiguration;
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

    public boolean isPrepareRemoteAnimation() {
        return mIsPrepareRemoteAnimation;
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
                + ", mDepartingAnimationTarget=" + mDepartingAnimationTarget
                + ", mScreenshotSurface=" + mScreenshotSurface
                + ", mTaskWindowConfiguration= " + mTaskWindowConfiguration
                + ", mScreenshotBuffer=" + mScreenshotBuffer
                + ", mOnBackNavigationDone=" + mOnBackNavigationDone
                + ", mOnBackInvokedCallback=" + mOnBackInvokedCallback
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
        private RemoteAnimationTarget mDepartingAnimationTarget = null;
        @Nullable
        private SurfaceControl mScreenshotSurface = null;
        @Nullable
        private HardwareBuffer mScreenshotBuffer = null;
        @Nullable
        private WindowConfiguration mTaskWindowConfiguration = null;
        @Nullable
        private RemoteCallback mOnBackNavigationDone = null;
        @Nullable
        private IOnBackInvokedCallback mOnBackInvokedCallback = null;

        private boolean mPrepareAnimation;

        /**
         * @see BackNavigationInfo#getType()
         */
        public Builder setType(@BackTargetType int type) {
            mType = type;
            return this;
        }

        /**
         * @see BackNavigationInfo#getDepartingAnimationTarget
         */
        public Builder setDepartingAnimationTarget(
                @Nullable RemoteAnimationTarget departingAnimationTarget) {
            mDepartingAnimationTarget = departingAnimationTarget;
            return this;
        }

        /**
         * @see BackNavigationInfo#getScreenshotSurface
         */
        public Builder setScreenshotSurface(@Nullable SurfaceControl screenshotSurface) {
            mScreenshotSurface = screenshotSurface;
            return this;
        }

        /**
         * @see BackNavigationInfo#getScreenshotHardwareBuffer()
         */
        public Builder setScreenshotBuffer(@Nullable HardwareBuffer screenshotBuffer) {
            mScreenshotBuffer = screenshotBuffer;
            return this;
        }

        /**
         * @see BackNavigationInfo#getTaskWindowConfiguration
         */
        public Builder setTaskWindowConfiguration(
                @Nullable WindowConfiguration taskWindowConfiguration) {
            mTaskWindowConfiguration = taskWindowConfiguration;
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
         * @param prepareAnimation Whether core prepare animation for shell.
         */
        public Builder setPrepareAnimation(boolean prepareAnimation) {
            mPrepareAnimation = prepareAnimation;
            return this;
        }

        /**
         * Builds and returns an instance of {@link BackNavigationInfo}
         */
        public BackNavigationInfo build() {
            return new BackNavigationInfo(mType, mDepartingAnimationTarget, mScreenshotSurface,
                    mScreenshotBuffer, mTaskWindowConfiguration, mOnBackNavigationDone,
                    mOnBackInvokedCallback, mPrepareAnimation);
        }
    }
}
