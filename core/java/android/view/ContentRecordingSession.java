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

package android.view;

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Description of a content recording session.
 *
 * @hide
 */
@DataClass(
        genConstructor = false,
        genToString = true,
        genSetters = true,
        genEqualsHashCode = true
)
public final class ContentRecordingSession implements Parcelable {

    /**
     * An entire DisplayContent is being recorded. Recording may also be paused.
     */
    public static final int RECORD_CONTENT_DISPLAY = 0;
    /**
     * A single Task is being recorded. Recording may also be paused.
     */
    public static final int RECORD_CONTENT_TASK = 1;

    /** Full screen sharing (app is not selected). */
    public static final int TARGET_UID_FULL_SCREEN = -1;

    /** Can't report (e.g. side loaded app). */
    public static final int TARGET_UID_UNKNOWN = -2;

    /**
     * Unique logical identifier of the {@link android.hardware.display.VirtualDisplay} that has
     * recorded content rendered to its surface.
     */
    private int mVirtualDisplayId = INVALID_DISPLAY;

    /**
     * The content to record.
     */
    @RecordContent
    private int mContentToRecord = RECORD_CONTENT_DISPLAY;

    /**
     * Unique logical identifier of the {@link android.view.Display} to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_DISPLAY}, then is
     * a valid display id.
     */
    private int mDisplayToRecord = INVALID_DISPLAY;

    /**
     * The token of the layer of the hierarchy to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_TASK}, then
     * represents the {@link android.window.WindowContainerToken} of the Task to record.
     */
    @Nullable
    private IBinder mTokenToRecord = null;

    /**
     * When {@code true}, no mirroring should take place until the user has re-granted access to
     * the consent token. When {@code false}, recording can begin immediately.
     *
     * <p>Only set on the server side to sanitize any input from the client process.
     */
    private boolean mWaitingForConsent = false;

    /** UID of the package that is captured if selected. */
    private int mTargetUid = TARGET_UID_UNKNOWN;

    /**
     * Default instance, with recording the display.
     */
    private ContentRecordingSession() {
    }

    /** Returns an instance initialized for recording the indicated display. */
    public static ContentRecordingSession createDisplaySession(int displayToMirror) {
        return new ContentRecordingSession()
                .setDisplayToRecord(displayToMirror)
                .setContentToRecord(RECORD_CONTENT_DISPLAY)
                .setTargetUid(TARGET_UID_FULL_SCREEN);
    }

    /** Returns an instance initialized for task recording. */
    public static ContentRecordingSession createTaskSession(
            @NonNull IBinder taskWindowContainerToken) {
        return createTaskSession(taskWindowContainerToken, TARGET_UID_UNKNOWN);
    }

    /** Returns an instance initialized for task recording. */
    public static ContentRecordingSession createTaskSession(
            @NonNull IBinder taskWindowContainerToken, int targetUid) {
        return new ContentRecordingSession()
                .setContentToRecord(RECORD_CONTENT_TASK)
                .setTokenToRecord(taskWindowContainerToken)
                .setTargetUid(targetUid);
    }

    /**
     * Returns {@code true} if this is a valid session.
     *
     * <p>A valid session has a non-null token for task recording, or a valid id for the display to
     * record.
     */
    public static boolean isValid(ContentRecordingSession session) {
        if (session == null) {
            return false;
        }
        final boolean isValidTaskSession = session.getContentToRecord() == RECORD_CONTENT_TASK
                && session.getTokenToRecord() != null;
        final boolean isValidDisplaySession = session.getContentToRecord() == RECORD_CONTENT_DISPLAY
                && session.getDisplayToRecord() > INVALID_DISPLAY;
        return session.getVirtualDisplayId() > INVALID_DISPLAY
                && (isValidTaskSession || isValidDisplaySession);
    }

    /**
     * Returns {@code true} when both sessions are on the same
     * {@link android.hardware.display.VirtualDisplay}.
     */
    public static boolean isProjectionOnSameDisplay(ContentRecordingSession session,
            ContentRecordingSession incomingSession) {
        return session != null && incomingSession != null
                && session.getVirtualDisplayId() == incomingSession.getVirtualDisplayId();
    }




    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/view/ContentRecordingSession.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @IntDef(prefix = "RECORD_CONTENT_", value = {
        RECORD_CONTENT_DISPLAY,
        RECORD_CONTENT_TASK
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface RecordContent {}

    @DataClass.Generated.Member
    public static String recordContentToString(@RecordContent int value) {
        switch (value) {
            case RECORD_CONTENT_DISPLAY:
                    return "RECORD_CONTENT_DISPLAY";
            case RECORD_CONTENT_TASK:
                    return "RECORD_CONTENT_TASK";
            default: return Integer.toHexString(value);
        }
    }

    @IntDef(prefix = "TARGET_UID_", value = {
        TARGET_UID_FULL_SCREEN,
        TARGET_UID_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface TargetUid {}

    @DataClass.Generated.Member
    public static String targetUidToString(@TargetUid int value) {
        switch (value) {
            case TARGET_UID_FULL_SCREEN:
                    return "TARGET_UID_FULL_SCREEN";
            case TARGET_UID_UNKNOWN:
                    return "TARGET_UID_UNKNOWN";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ ContentRecordingSession(
            int virtualDisplayId,
            @RecordContent int contentToRecord,
            int displayToRecord,
            @Nullable IBinder tokenToRecord,
            boolean waitingForConsent,
            int targetUid) {
        this.mVirtualDisplayId = virtualDisplayId;
        this.mContentToRecord = contentToRecord;

        if (!(mContentToRecord == RECORD_CONTENT_DISPLAY)
                && !(mContentToRecord == RECORD_CONTENT_TASK)) {
            throw new java.lang.IllegalArgumentException(
                    "contentToRecord was " + mContentToRecord + " but must be one of: "
                            + "RECORD_CONTENT_DISPLAY(" + RECORD_CONTENT_DISPLAY + "), "
                            + "RECORD_CONTENT_TASK(" + RECORD_CONTENT_TASK + ")");
        }

        this.mDisplayToRecord = displayToRecord;
        this.mTokenToRecord = tokenToRecord;
        this.mWaitingForConsent = waitingForConsent;
        this.mTargetUid = targetUid;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Unique logical identifier of the {@link android.hardware.display.VirtualDisplay} that has
     * recorded content rendered to its surface.
     */
    @DataClass.Generated.Member
    public int getVirtualDisplayId() {
        return mVirtualDisplayId;
    }

    /**
     * The content to record.
     */
    @DataClass.Generated.Member
    public @RecordContent int getContentToRecord() {
        return mContentToRecord;
    }

    /**
     * Unique logical identifier of the {@link android.view.Display} to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_DISPLAY}, then is
     * a valid display id.
     */
    @DataClass.Generated.Member
    public int getDisplayToRecord() {
        return mDisplayToRecord;
    }

    /**
     * The token of the layer of the hierarchy to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_TASK}, then
     * represents the {@link android.window.WindowContainerToken} of the Task to record.
     */
    @DataClass.Generated.Member
    public @Nullable IBinder getTokenToRecord() {
        return mTokenToRecord;
    }

    /**
     * When {@code true}, no mirroring should take place until the user has re-granted access to
     * the consent token. When {@code false}, recording can begin immediately.
     *
     * <p>Only set on the server side to sanitize any input from the client process.
     */
    @DataClass.Generated.Member
    public boolean isWaitingForConsent() {
        return mWaitingForConsent;
    }

    /**
     * UID of the package that is captured if selected.
     */
    @DataClass.Generated.Member
    public int getTargetUid() {
        return mTargetUid;
    }

    /**
     * Unique logical identifier of the {@link android.hardware.display.VirtualDisplay} that has
     * recorded content rendered to its surface.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setVirtualDisplayId( int value) {
        mVirtualDisplayId = value;
        return this;
    }

    /**
     * The content to record.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setContentToRecord(@RecordContent int value) {
        mContentToRecord = value;

        if (!(mContentToRecord == RECORD_CONTENT_DISPLAY)
                && !(mContentToRecord == RECORD_CONTENT_TASK)) {
            throw new java.lang.IllegalArgumentException(
                    "contentToRecord was " + mContentToRecord + " but must be one of: "
                            + "RECORD_CONTENT_DISPLAY(" + RECORD_CONTENT_DISPLAY + "), "
                            + "RECORD_CONTENT_TASK(" + RECORD_CONTENT_TASK + ")");
        }

        return this;
    }

    /**
     * Unique logical identifier of the {@link android.view.Display} to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_DISPLAY}, then is
     * a valid display id.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setDisplayToRecord( int value) {
        mDisplayToRecord = value;
        return this;
    }

    /**
     * The token of the layer of the hierarchy to record.
     *
     * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_TASK}, then
     * represents the {@link android.window.WindowContainerToken} of the Task to record.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setTokenToRecord(@NonNull IBinder value) {
        mTokenToRecord = value;
        return this;
    }

    /**
     * When {@code true}, no mirroring should take place until the user has re-granted access to
     * the consent token. When {@code false}, recording can begin immediately.
     *
     * <p>Only set on the server side to sanitize any input from the client process.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setWaitingForConsent( boolean value) {
        mWaitingForConsent = value;
        return this;
    }

    /**
     * UID of the package that is captured if selected.
     */
    @DataClass.Generated.Member
    public @NonNull ContentRecordingSession setTargetUid( int value) {
        mTargetUid = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "ContentRecordingSession { " +
                "virtualDisplayId = " + mVirtualDisplayId + ", " +
                "contentToRecord = " + recordContentToString(mContentToRecord) + ", " +
                "displayToRecord = " + mDisplayToRecord + ", " +
                "tokenToRecord = " + mTokenToRecord + ", " +
                "waitingForConsent = " + mWaitingForConsent + ", " +
                "targetUid = " + mTargetUid +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(ContentRecordingSession other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        ContentRecordingSession that = (ContentRecordingSession) o;
        //noinspection PointlessBooleanExpression
        return true
                && mVirtualDisplayId == that.mVirtualDisplayId
                && mContentToRecord == that.mContentToRecord
                && mDisplayToRecord == that.mDisplayToRecord
                && java.util.Objects.equals(mTokenToRecord, that.mTokenToRecord)
                && mWaitingForConsent == that.mWaitingForConsent
                && mTargetUid == that.mTargetUid;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mVirtualDisplayId;
        _hash = 31 * _hash + mContentToRecord;
        _hash = 31 * _hash + mDisplayToRecord;
        _hash = 31 * _hash + java.util.Objects.hashCode(mTokenToRecord);
        _hash = 31 * _hash + Boolean.hashCode(mWaitingForConsent);
        _hash = 31 * _hash + mTargetUid;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mWaitingForConsent) flg |= 0x10;
        if (mTokenToRecord != null) flg |= 0x8;
        dest.writeByte(flg);
        dest.writeInt(mVirtualDisplayId);
        dest.writeInt(mContentToRecord);
        dest.writeInt(mDisplayToRecord);
        if (mTokenToRecord != null) dest.writeStrongBinder(mTokenToRecord);
        dest.writeInt(mTargetUid);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ ContentRecordingSession(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean waitingForConsent = (flg & 0x10) != 0;
        int virtualDisplayId = in.readInt();
        int contentToRecord = in.readInt();
        int displayToRecord = in.readInt();
        IBinder tokenToRecord = (flg & 0x8) == 0 ? null : (IBinder) in.readStrongBinder();
        int targetUid = in.readInt();

        this.mVirtualDisplayId = virtualDisplayId;
        this.mContentToRecord = contentToRecord;

        if (!(mContentToRecord == RECORD_CONTENT_DISPLAY)
                && !(mContentToRecord == RECORD_CONTENT_TASK)) {
            throw new java.lang.IllegalArgumentException(
                    "contentToRecord was " + mContentToRecord + " but must be one of: "
                            + "RECORD_CONTENT_DISPLAY(" + RECORD_CONTENT_DISPLAY + "), "
                            + "RECORD_CONTENT_TASK(" + RECORD_CONTENT_TASK + ")");
        }

        this.mDisplayToRecord = displayToRecord;
        this.mTokenToRecord = tokenToRecord;
        this.mWaitingForConsent = waitingForConsent;
        this.mTargetUid = targetUid;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<ContentRecordingSession> CREATOR
            = new Parcelable.Creator<ContentRecordingSession>() {
        @Override
        public ContentRecordingSession[] newArray(int size) {
            return new ContentRecordingSession[size];
        }

        @Override
        public ContentRecordingSession createFromParcel(@NonNull Parcel in) {
            return new ContentRecordingSession(in);
        }
    };

    /**
     * A builder for {@link ContentRecordingSession}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private int mVirtualDisplayId;
        private @RecordContent int mContentToRecord;
        private int mDisplayToRecord;
        private @Nullable IBinder mTokenToRecord;
        private boolean mWaitingForConsent;
        private int mTargetUid;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Unique logical identifier of the {@link android.hardware.display.VirtualDisplay} that has
         * recorded content rendered to its surface.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setVirtualDisplayId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mVirtualDisplayId = value;
            return this;
        }

        /**
         * The content to record.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setContentToRecord(@RecordContent int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mContentToRecord = value;
            return this;
        }

        /**
         * Unique logical identifier of the {@link android.view.Display} to record.
         *
         * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_DISPLAY}, then is
         * a valid display id.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setDisplayToRecord(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mDisplayToRecord = value;
            return this;
        }

        /**
         * The token of the layer of the hierarchy to record.
         *
         * <p>If {@link #getContentToRecord()} is {@link RecordContent#RECORD_CONTENT_TASK}, then
         * represents the {@link android.window.WindowContainerToken} of the Task to record.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTokenToRecord(@NonNull IBinder value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mTokenToRecord = value;
            return this;
        }

        /**
         * When {@code true}, no mirroring should take place until the user has re-granted access to
         * the consent token. When {@code false}, recording can begin immediately.
         *
         * <p>Only set on the server side to sanitize any input from the client process.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setWaitingForConsent(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mWaitingForConsent = value;
            return this;
        }

        /**
         * UID of the package that is captured if selected.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setTargetUid(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mTargetUid = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull ContentRecordingSession build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mVirtualDisplayId = INVALID_DISPLAY;
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mContentToRecord = RECORD_CONTENT_DISPLAY;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mDisplayToRecord = INVALID_DISPLAY;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mTokenToRecord = null;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mWaitingForConsent = false;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mTargetUid = TARGET_UID_UNKNOWN;
            }
            ContentRecordingSession o = new ContentRecordingSession(
                    mVirtualDisplayId,
                    mContentToRecord,
                    mDisplayToRecord,
                    mTokenToRecord,
                    mWaitingForConsent,
                    mTargetUid);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x40) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1697456140720L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/view/ContentRecordingSession.java",
            inputSignatures = "public static final  int RECORD_CONTENT_DISPLAY\npublic static final  int RECORD_CONTENT_TASK\npublic static final  int TARGET_UID_FULL_SCREEN\npublic static final  int TARGET_UID_UNKNOWN\nprivate  int mVirtualDisplayId\nprivate @android.view.ContentRecordingSession.RecordContent int mContentToRecord\nprivate  int mDisplayToRecord\nprivate @android.annotation.Nullable android.os.IBinder mTokenToRecord\nprivate  boolean mWaitingForConsent\nprivate  int mTargetUid\npublic static  android.view.ContentRecordingSession createDisplaySession(int)\npublic static  android.view.ContentRecordingSession createTaskSession(android.os.IBinder)\npublic static  android.view.ContentRecordingSession createTaskSession(android.os.IBinder,int)\npublic static  boolean isValid(android.view.ContentRecordingSession)\npublic static  boolean isProjectionOnSameDisplay(android.view.ContentRecordingSession,android.view.ContentRecordingSession)\nclass ContentRecordingSession extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genToString=true, genSetters=true, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
