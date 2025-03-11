/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.WindowManager.transitTypeToString;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.Parcelable;
import android.view.WindowManager;

import com.android.internal.util.DataClass;

/**
 * Used to communicate information about what is changing during a transition to a TransitionPlayer.
 * @hide
 */
@DataClass(genToString = true, genSetters = true, genAidl = true)
public final class TransitionRequestInfo implements Parcelable {

    /** The type of the transition being requested. */
    private final @WindowManager.TransitionType int mType;

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    private @Nullable ActivityManager.RunningTaskInfo mTriggerTask;

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    private @Nullable TransitionRequestInfo.PipChange mPipChange;

    /** If non-null, a remote-transition associated with the source of this transition. */
    private @Nullable RemoteTransition mRemoteTransition;

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    private @Nullable TransitionRequestInfo.DisplayChange mDisplayChange;

    /** The transition flags known at the time of the request. These may not be complete. */
    private final int mFlags;

    /** This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work! */
    private final int mDebugId;

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransition remoteTransition) {
        this(type, triggerTask, null /* pipChange */,
                remoteTransition, null /* displayChange */, 0 /* flags */, -1 /* debugId */);
    }

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransition remoteTransition,
            int flags) {
        this(type, triggerTask, null /* pipChange */,
                remoteTransition, null /* displayChange */, flags, -1 /* debugId */);
    }

        /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            int flags) {
        this(type, triggerTask, null /* pipChange */, remoteTransition, displayChange, flags,
                -1 /* debugId */);
    }

    /** constructor override */
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable ActivityManager.RunningTaskInfo pipTask,
            @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            int flags) {
        this(type, triggerTask,
                pipTask != null ? new TransitionRequestInfo.PipChange(pipTask) : null,
                remoteTransition, displayChange, flags, -1 /* debugId */);
    }

    /** @hide */
    String typeToString() {
        return transitTypeToString(mType);
    }

    /** Requested change to a display. */
    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class DisplayChange implements Parcelable {
        private final int mDisplayId;

        // If non-null, these bounds changes should ignore any potential rotation changes.
        @Nullable private Rect mStartAbsBounds = null;
        @Nullable private Rect mEndAbsBounds = null;

        private int mStartRotation = WindowConfiguration.ROTATION_UNDEFINED;
        private int mEndRotation = WindowConfiguration.ROTATION_UNDEFINED;
        private boolean mPhysicalDisplayChanged = false;

        /** Create empty display-change. */
        public DisplayChange(int displayId) {
            mDisplayId = displayId;
        }

        /** Create a display-change representing a rotation. */
        public DisplayChange(int displayId, int startRotation, int endRotation) {
            mDisplayId = displayId;
            mStartRotation = startRotation;
            mEndRotation = endRotation;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public int getDisplayId() {
            return mDisplayId;
        }

        @DataClass.Generated.Member
        public @Nullable Rect getStartAbsBounds() {
            return mStartAbsBounds;
        }

        @DataClass.Generated.Member
        public @Nullable Rect getEndAbsBounds() {
            return mEndAbsBounds;
        }

        @DataClass.Generated.Member
        public int getStartRotation() {
            return mStartRotation;
        }

        @DataClass.Generated.Member
        public int getEndRotation() {
            return mEndRotation;
        }

        @DataClass.Generated.Member
        public boolean isPhysicalDisplayChanged() {
            return mPhysicalDisplayChanged;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setStartAbsBounds(@android.annotation.NonNull Rect value) {
            mStartAbsBounds = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setEndAbsBounds(@android.annotation.NonNull Rect value) {
            mEndAbsBounds = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setStartRotation( int value) {
            mStartRotation = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setEndRotation( int value) {
            mEndRotation = value;
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull DisplayChange setPhysicalDisplayChanged( boolean value) {
            mPhysicalDisplayChanged = value;
            return this;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "DisplayChange { " +
                    "displayId = " + mDisplayId + ", " +
                    "startAbsBounds = " + mStartAbsBounds + ", " +
                    "endAbsBounds = " + mEndAbsBounds + ", " +
                    "startRotation = " + mStartRotation + ", " +
                    "endRotation = " + mEndRotation + ", " +
                    "physicalDisplayChanged = " + mPhysicalDisplayChanged +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mPhysicalDisplayChanged) flg |= 0x20;
            if (mStartAbsBounds != null) flg |= 0x2;
            if (mEndAbsBounds != null) flg |= 0x4;
            dest.writeByte(flg);
            dest.writeInt(mDisplayId);
            if (mStartAbsBounds != null) dest.writeTypedObject(mStartAbsBounds, flags);
            if (mEndAbsBounds != null) dest.writeTypedObject(mEndAbsBounds, flags);
            dest.writeInt(mStartRotation);
            dest.writeInt(mEndRotation);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ DisplayChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            boolean physicalDisplayChanged = (flg & 0x20) != 0;
            int displayId = in.readInt();
            Rect startAbsBounds = (flg & 0x2) == 0 ? null : (Rect) in.readTypedObject(Rect.CREATOR);
            Rect endAbsBounds = (flg & 0x4) == 0 ? null : (Rect) in.readTypedObject(Rect.CREATOR);
            int startRotation = in.readInt();
            int endRotation = in.readInt();

            this.mDisplayId = displayId;
            this.mStartAbsBounds = startAbsBounds;
            this.mEndAbsBounds = endAbsBounds;
            this.mStartRotation = startRotation;
            this.mEndRotation = endRotation;
            this.mPhysicalDisplayChanged = physicalDisplayChanged;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<DisplayChange> CREATOR
                = new Parcelable.Creator<DisplayChange>() {
            @Override
            public DisplayChange[] newArray(int size) {
                return new DisplayChange[size];
            }

            @Override
            public DisplayChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new DisplayChange(in);
            }
        };

        @DataClass.Generated(
                time = 1733334462577L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private final  int mDisplayId\nprivate @android.annotation.Nullable android.graphics.Rect mStartAbsBounds\nprivate @android.annotation.Nullable android.graphics.Rect mEndAbsBounds\nprivate  int mStartRotation\nprivate  int mEndRotation\nprivate  boolean mPhysicalDisplayChanged\nclass DisplayChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    @DataClass(genToString = true, genSetters = true, genBuilder = false, genConstructor = false)
    public static final class PipChange implements Parcelable {
        // In AE case, we might care about the TF token instead of the task token.
        @android.annotation.NonNull
        private WindowContainerToken mTaskFragmentToken;

        @android.annotation.NonNull
        private ActivityManager.RunningTaskInfo mTaskInfo;

        /** Create empty display-change. */
        public PipChange(ActivityManager.RunningTaskInfo taskInfo) {
            mTaskFragmentToken = taskInfo.token;
            mTaskInfo = taskInfo;
        }

        /** Create a display-change representing a rotation. */
        public PipChange(WindowContainerToken taskFragmentToken,
                ActivityManager.RunningTaskInfo taskInfo) {
            mTaskFragmentToken = taskFragmentToken;
            mTaskInfo = taskInfo;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public @android.annotation.NonNull WindowContainerToken getTaskFragmentToken() {
            return mTaskFragmentToken;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull ActivityManager.RunningTaskInfo getTaskInfo() {
            return mTaskInfo;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull PipChange setTaskFragmentToken(@android.annotation.NonNull WindowContainerToken value) {
            mTaskFragmentToken = value;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskFragmentToken);
            return this;
        }

        @DataClass.Generated.Member
        public @android.annotation.NonNull PipChange setTaskInfo(@android.annotation.NonNull ActivityManager.RunningTaskInfo value) {
            mTaskInfo = value;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskInfo);
            return this;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "PipChange { " +
                    "taskFragmentToken = " + mTaskFragmentToken + ", " +
                    "taskInfo = " + mTaskInfo +
            " }";
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            dest.writeTypedObject(mTaskFragmentToken, flags);
            dest.writeTypedObject(mTaskInfo, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ PipChange(@android.annotation.NonNull android.os.Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            WindowContainerToken taskFragmentToken = (WindowContainerToken) in.readTypedObject(WindowContainerToken.CREATOR);
            ActivityManager.RunningTaskInfo taskInfo = (ActivityManager.RunningTaskInfo) in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);

            this.mTaskFragmentToken = taskFragmentToken;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskFragmentToken);
            this.mTaskInfo = taskInfo;
            com.android.internal.util.AnnotationValidations.validate(
                    android.annotation.NonNull.class, null, mTaskInfo);

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @android.annotation.NonNull Parcelable.Creator<PipChange> CREATOR
                = new Parcelable.Creator<PipChange>() {
            @Override
            public PipChange[] newArray(int size) {
                return new PipChange[size];
            }

            @Override
            public PipChange createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
                return new PipChange(in);
            }
        };

        @DataClass.Generated(
                time = 1733334462588L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
                inputSignatures = "private @android.annotation.NonNull android.window.WindowContainerToken mTaskFragmentToken\nprivate @android.annotation.NonNull android.app.ActivityManager.RunningTaskInfo mTaskInfo\nclass PipChange extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genBuilder=false, genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }




    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/TransitionRequestInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new TransitionRequestInfo.
     *
     * @param type
     *   The type of the transition being requested.
     * @param triggerTask
     *   If non-null, the task containing the activity whose lifecycle change (start or
     *   finish) has caused this transition to occur.
     * @param pipChange
     *   If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     *   {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     * @param remoteTransition
     *   If non-null, a remote-transition associated with the source of this transition.
     * @param displayChange
     *   If non-null, this request was triggered by this display change. This will not be complete:
     *   The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     *   (if size is changing).
     * @param flags
     *   The transition flags known at the time of the request. These may not be complete.
     * @param debugId
     *   This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work!
     */
    @DataClass.Generated.Member
    public TransitionRequestInfo(
            @WindowManager.TransitionType int type,
            @Nullable ActivityManager.RunningTaskInfo triggerTask,
            @Nullable TransitionRequestInfo.PipChange pipChange,
            @Nullable RemoteTransition remoteTransition,
            @Nullable TransitionRequestInfo.DisplayChange displayChange,
            int flags,
            int debugId) {
        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                WindowManager.TransitionType.class, null, mType);
        this.mTriggerTask = triggerTask;
        this.mPipChange = pipChange;
        this.mRemoteTransition = remoteTransition;
        this.mDisplayChange = displayChange;
        this.mFlags = flags;
        this.mDebugId = debugId;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The type of the transition being requested.
     */
    @DataClass.Generated.Member
    public @WindowManager.TransitionType int getType() {
        return mType;
    }

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    @DataClass.Generated.Member
    public @Nullable ActivityManager.RunningTaskInfo getTriggerTask() {
        return mTriggerTask;
    }

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.PipChange getPipChange() {
        return mPipChange;
    }

    /**
     * If non-null, a remote-transition associated with the source of this transition.
     */
    @DataClass.Generated.Member
    public @Nullable RemoteTransition getRemoteTransition() {
        return mRemoteTransition;
    }

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    @DataClass.Generated.Member
    public @Nullable TransitionRequestInfo.DisplayChange getDisplayChange() {
        return mDisplayChange;
    }

    /**
     * The transition flags known at the time of the request. These may not be complete.
     */
    @DataClass.Generated.Member
    public int getFlags() {
        return mFlags;
    }

    /**
     * This is only a BEST-EFFORT id used for log correlation. DO NOT USE for any real work!
     */
    @DataClass.Generated.Member
    public int getDebugId() {
        return mDebugId;
    }

    /**
     * If non-null, the task containing the activity whose lifecycle change (start or
     * finish) has caused this transition to occur.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setTriggerTask(@android.annotation.NonNull ActivityManager.RunningTaskInfo value) {
        mTriggerTask = value;
        return this;
    }

    /**
     * If non-null, this request might lead to a PiP transition; {@code PipChange} caches both
     * {@code TaskFragment} token and the {@code TaskInfo} of the task with PiP candidate activity.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setPipChange(@android.annotation.NonNull TransitionRequestInfo.PipChange value) {
        mPipChange = value;
        return this;
    }

    /**
     * If non-null, a remote-transition associated with the source of this transition.
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setRemoteTransition(@android.annotation.NonNull RemoteTransition value) {
        mRemoteTransition = value;
        return this;
    }

    /**
     * If non-null, this request was triggered by this display change. This will not be complete:
     * The reliable parts should be flags, rotation start/end (if rotating), and start/end bounds
     * (if size is changing).
     */
    @DataClass.Generated.Member
    public @android.annotation.NonNull TransitionRequestInfo setDisplayChange(@android.annotation.NonNull TransitionRequestInfo.DisplayChange value) {
        mDisplayChange = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "TransitionRequestInfo { " +
                "type = " + typeToString() + ", " +
                "triggerTask = " + mTriggerTask + ", " +
                "pipChange = " + mPipChange + ", " +
                "remoteTransition = " + mRemoteTransition + ", " +
                "displayChange = " + mDisplayChange + ", " +
                "flags = " + mFlags + ", " +
                "debugId = " + mDebugId +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@android.annotation.NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mTriggerTask != null) flg |= 0x2;
        if (mPipChange != null) flg |= 0x4;
        if (mRemoteTransition != null) flg |= 0x8;
        if (mDisplayChange != null) flg |= 0x10;
        dest.writeByte(flg);
        dest.writeInt(mType);
        if (mTriggerTask != null) dest.writeTypedObject(mTriggerTask, flags);
        if (mPipChange != null) dest.writeTypedObject(mPipChange, flags);
        if (mRemoteTransition != null) dest.writeTypedObject(mRemoteTransition, flags);
        if (mDisplayChange != null) dest.writeTypedObject(mDisplayChange, flags);
        dest.writeInt(mFlags);
        dest.writeInt(mDebugId);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ TransitionRequestInfo(@android.annotation.NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int type = in.readInt();
        ActivityManager.RunningTaskInfo triggerTask = (flg & 0x2) == 0 ? null : (ActivityManager.RunningTaskInfo) in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
        TransitionRequestInfo.PipChange pipChange = (flg & 0x4) == 0 ? null : (TransitionRequestInfo.PipChange) in.readTypedObject(TransitionRequestInfo.PipChange.CREATOR);
        RemoteTransition remoteTransition = (flg & 0x8) == 0 ? null : (RemoteTransition) in.readTypedObject(RemoteTransition.CREATOR);
        TransitionRequestInfo.DisplayChange displayChange = (flg & 0x10) == 0 ? null : (TransitionRequestInfo.DisplayChange) in.readTypedObject(TransitionRequestInfo.DisplayChange.CREATOR);
        int flags = in.readInt();
        int debugId = in.readInt();

        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                WindowManager.TransitionType.class, null, mType);
        this.mTriggerTask = triggerTask;
        this.mPipChange = pipChange;
        this.mRemoteTransition = remoteTransition;
        this.mDisplayChange = displayChange;
        this.mFlags = flags;
        this.mDebugId = debugId;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @android.annotation.NonNull Parcelable.Creator<TransitionRequestInfo> CREATOR
            = new Parcelable.Creator<TransitionRequestInfo>() {
        @Override
        public TransitionRequestInfo[] newArray(int size) {
            return new TransitionRequestInfo[size];
        }

        @Override
        public TransitionRequestInfo createFromParcel(@android.annotation.NonNull android.os.Parcel in) {
            return new TransitionRequestInfo(in);
        }
    };

    @DataClass.Generated(
            time = 1733334462604L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/window/TransitionRequestInfo.java",
            inputSignatures = "private final @android.view.WindowManager.TransitionType int mType\nprivate @android.annotation.Nullable android.app.ActivityManager.RunningTaskInfo mTriggerTask\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.PipChange mPipChange\nprivate @android.annotation.Nullable android.window.RemoteTransition mRemoteTransition\nprivate @android.annotation.Nullable android.window.TransitionRequestInfo.DisplayChange mDisplayChange\nprivate final  int mFlags\nprivate final  int mDebugId\n  java.lang.String typeToString()\nclass TransitionRequestInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genToString=true, genSetters=true, genAidl=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
