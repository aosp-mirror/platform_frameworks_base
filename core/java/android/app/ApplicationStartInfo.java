/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;

/**
 * Provide information related to a processes startup.
 *
 * @hide
 */
public final class ApplicationStartInfo implements Parcelable {

    /**
     * State indicating process startup has started. Some information is available in
     * {@link ApplicationStartInfo} and more will be added.
     */
    public static final int STARTUP_STATE_STARTED = 0;

    /**
     * State indicating process startup has failed. Startup information in
     * {@link ApplicationStartInfo} is incomplete, but no more will be added.
     */
    public static final int STARTUP_STATE_ERROR = 1;

    /**
     * State indicating process startup has made it to first frame draw. Startup
     * information in {@link ApplicationStartInfo} is complete with potential exception
     * of fully drawn timestamp which is not guaranteed to be set.
     */
    public static final int STARTUP_STATE_FIRST_FRAME_DRAWN = 2;

    /** Process started due to alarm. */
    public static final int START_REASON_ALARM = 0;

    /** Process started to run backup. */
    public static final int START_REASON_BACKUP = 1;

    /** Process started due to boot complete. */
    public static final int START_REASON_BOOT_COMPLETE = 2;

    /**  Process started due to broadcast received. */
    public static final int START_REASON_BROADCAST = 3;

    /** Process started due to access of ContentProvider */
    public static final int START_REASON_CONTENT_PROVIDER = 4;

    /** * Process started to run scheduled job. */
    public static final int START_REASON_JOB = 5;

    /** Process started due to click app icon or widget from launcher. */
    public static final int START_REASON_LAUNCHER = 6;

    /** Process started not for any of the listed reasons. */
    public static final int START_REASON_OTHER = 7;

    /** Process started due to push message. */
    public static final int START_REASON_PUSH = 8;

    /** Process started to resume activity. */
    public static final int START_REASON_RESUMED_ACTIVITY = 9;

    /** Process service started. */
    public static final int START_REASON_SERVICE = 10;

    /** Process started due to Activity started for any reason not explicitly listed. */
    public static final int START_REASON_START_ACTIVITY = 11;

    /** Process started from scratch. */
    public static final int START_TYPE_COLD = 0;

    /** Process retained minimally SavedInstanceState. */
    public static final int START_TYPE_WARM = 1;

    /** Process brought back to foreground. */
    public static final int START_TYPE_HOT = 2;

    /**
     * Default. The system always creates a new instance of the activity in the target task and
     * routes the intent to it.
     */
    public static final int LAUNCH_MODE_STANDARD = 0;

    /**
     * If an instance of the activity already exists at the top of the target task, the system
     * routes the intent to that instance through a call to its onNewIntent() method, rather than
     * creating a new instance of the activity.
     */
    public static final int LAUNCH_MODE_SINGLE_TOP = 1;

    /**
     * The system creates the activity at the root of a new task or locates the activity on an
     * existing task with the same affinity. If an instance of the activity already exists and is at
     * the root of the task, the system routes the intent to existing instance through a call to its
     * onNewIntent() method, rather than creating a new one.
     */
    public static final int LAUNCH_MODE_SINGLE_INSTANCE = 2;

    /**
     * Same as "singleTask", except that the system doesn't launch any other activities into the
     * task holding the instance. The activity is always the single and only member of its task.
     */
    public static final int LAUNCH_MODE_SINGLE_TASK = 3;

    /**
     * The activity can only be running as the root activity of the task, the first activity that
     * created the task, and therefore there will only be one instance of this activity in a task;
     * but activity can be instantiated multiple times in different tasks.
     */
    public static final int LAUNCH_MODE_SINGLE_INSTANCE_PER_TASK = 4;

    /** Clock monotonic timestamp of launch started. */
    public static final int START_TIMESTAMP_LAUNCH = 0;

    /** Clock monotonic timestamp of finish java classloading. */
    public static final int START_TIMESTAMP_JAVA_CLASSLOADING_COMPLETE = 1;

    /** Clock monotonic timestamp of Application onCreate called. */
    public static final int START_TIMESTAMP_APPLICATION_ONCREATE = 2;

    /** Clock monotonic timestamp of bindApplication called. */
    public static final int START_TIMESTAMP_BIND_APPLICATION = 3;

    /** Clock monotonic timestamp of first frame drawn. */
    public static final int START_TIMESTAMP_FIRST_FRAME = 4;

    /** Clock monotonic timestamp of reportFullyDrawn called by application. */
    public static final int START_TIMESTAMP_FULLY_DRAWN = 5;

    /**
     * @see #getStartupState
     */
    private @StartupState int mStartupState;

    /**
     * @see #getPid
     */
    private int mPid;

    /**
     * @see #getRealUid
     */
    private int mRealUid;

    /**
     * @see #getPackageUid
     */
    private int mPackageUid;

    /**
     * @see #getDefiningUid
     */
    private int mDefiningUid;

    /**
     * @see #getProcessName
     */
    private String mProcessName;

    /**
     * @see #getReason
     */
    private @StartReason int mReason;

    /**
     * @see #getStartupTimestamps
     */
    private Map<@StartupTimestamp Integer, Long> mStartupTimestampsNs;

    /**
     * @see #getStartType
     */
    private @StartType int mStartType;

    /**
     * @see #getStartIntent
     */
    private Intent mStartIntent;

    /**
     * @see #getLaunchMode
     */
    private @LaunchMode int mLaunchMode;

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"STARTUP_STATE_"},
            value = {
                STARTUP_STATE_STARTED,
                STARTUP_STATE_ERROR,
                STARTUP_STATE_FIRST_FRAME_DRAWN,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartupState {}

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"START_REASON_"},
            value = {
                START_REASON_ALARM,
                START_REASON_BACKUP,
                START_REASON_BOOT_COMPLETE,
                START_REASON_BROADCAST,
                START_REASON_CONTENT_PROVIDER,
                START_REASON_JOB,
                START_REASON_LAUNCHER,
                START_REASON_OTHER,
                START_REASON_PUSH,
                START_REASON_RESUMED_ACTIVITY,
                START_REASON_SERVICE,
                START_REASON_START_ACTIVITY,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartReason {}

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"START_TYPE_"},
            value = {
                START_TYPE_COLD,
                START_TYPE_WARM,
                START_TYPE_HOT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartType {}

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"LAUNCH_MODE_"},
            value = {
                LAUNCH_MODE_STANDARD,
                LAUNCH_MODE_SINGLE_TOP,
                LAUNCH_MODE_SINGLE_INSTANCE,
                LAUNCH_MODE_SINGLE_TASK,
                LAUNCH_MODE_SINGLE_INSTANCE_PER_TASK,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LaunchMode {}

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"START_TIMESTAMP_"},
            value = {
                START_TIMESTAMP_LAUNCH,
                START_TIMESTAMP_JAVA_CLASSLOADING_COMPLETE,
                START_TIMESTAMP_APPLICATION_ONCREATE,
                START_TIMESTAMP_BIND_APPLICATION,
                START_TIMESTAMP_FULLY_DRAWN,
            })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface StartupTimestamp {}

    /**
     * @see #getStartupState
     * @hide
     */
    public void setStartupState(final @StartupState int startupState) {
        mStartupState = startupState;
    }

    /**
     * @see #getPid
     * @hide
     */
    public void setPid(final int pid) {
        mPid = pid;
    }

    /**
     * @see #getRealUid
     * @hide
     */
    public void setRealUid(final int uid) {
        mRealUid = uid;
    }

    /**
     * @see #getPackageUid
     * @hide
     */
    public void setPackageUid(final int uid) {
        mPackageUid = uid;
    }

    /**
     * @see #getDefiningUid
     * @hide
     */
    public void setDefiningUid(final int uid) {
        mDefiningUid = uid;
    }

    /**
     * @see #getProcessName
     * @hide
     */
    public void setProcessName(final String processName) {
        mProcessName = intern(processName);
    }

    /**
     * @see #getReason
     * @hide
     */
    public void setReason(@StartReason int reason) {
        mReason = reason;
    }

    /**
     * @see #getStartupTimestamps
     * @hide
     */
    public void addStartupTimestamp(@StartupTimestamp int key, long timestampNs) {
        if (mStartupTimestampsNs == null) {
            mStartupTimestampsNs = new HashMap<@StartupTimestamp Integer, Long>();
        }
        mStartupTimestampsNs.put(key, timestampNs);
    }

    /**
     * @see #getStartType
     * @hide
     */
    public void setStartType(@StartType int startType) {
        mStartType = startType;
    }

    /**
     * @see #getStartIntent
     * @hide
     */
    public void setIntent(Intent startIntent) {
        mStartIntent = startIntent;
    }

    /**
     * @see #getLaunchMode
     * @hide
     */
    public void setLaunchMode(@LaunchMode int launchMode) {
        mLaunchMode = launchMode;
    }

    /**
     * Current state of startup.
     *
     * Can be used to determine whether the object will have additional fields added as it may be
     * queried before all data is collected.
     *
     * <p class="note"> Note: field will always be set and available.</p>
     */
    public @StartupState int getStartupState() {
        return mStartupState;
    }

    /**
     * The process id.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public int getPid() {
        return mPid;
    }

    /**
     * The kernel user identifier of the process, most of the time the system uses this to do access
     * control checks. It's typically the uid of the package where the component is running from,
     * except the case of isolated process, where this field identifies the kernel user identifier
     * that this process is actually running with, while the {@link #getPackageUid} identifies the
     * kernel user identifier that is assigned at the package installation time.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public int getRealUid() {
        return mRealUid;
    }

    /**
     * Similar to {@link #getRealUid}, it's the kernel user identifier that is assigned at the
     * package installation time.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public int getPackageUid() {
        return mPackageUid;
    }

    /**
     * Return the defining kernel user identifier, maybe different from {@link #getRealUid} and
     * {@link #getPackageUid}, if an external service has the {@link
     * android.R.styleable#AndroidManifestService_useAppZygote android:useAppZygote} set to <code>
     * true</code> and was bound with the flag {@link android.content.Context#BIND_EXTERNAL_SERVICE}
     * - in this case, this field here will be the kernel user identifier of the external service
     * provider.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public int getDefiningUid() {
        return mDefiningUid;
    }

    /**
     * The actual process name it was running with.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public @NonNull String getProcessName() {
        return mProcessName;
    }

    /**
     * The reason code of what triggered the process's start.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public @StartReason int getReason() {
        return mReason;
    }

    /**
     * Various clock monotonic timestamps in nanoseconds throughout the startup process.
     *
     * <p class="note"> Note: different timestamps will be available for different values of
     * {@link #getStartupState}:
     *
     * (Subsequent rows contain all timestamps of proceding states.)
     *
     * For {@link #STARTUP_STATE_STARTED}, timestamp {@link #START_TIMESTAMP_LAUNCH} will be
     * available.
     * For {@link #STARTUP_STATE_ERROR}, no additional timestamps are guaranteed available.
     * For {@link #STARTUP_STATE_FIRST_FRAME_DRAWN}, timestamps
     * {@link #START_TIMESTAMP_JAVA_CLASSLOADING_COMPLETE}, {@link #START_TIMESTAMP_APPLICATION_ONCREATE},
     * {@link #START_TIMESTAMP_BIND_APPLICATION}, and {@link #START_TIMESTAMP_FIRST_FRAME} will
     * additionally be available.
     *
     * Timestamp {@link #START_TIMESTAMP_FULLY_DRAWN} is never guaranteed to be available as it is
     * dependant on devloper calling {@link Activity#reportFullyDrawn}.
     * </p>
     */
    public @NonNull Map<@StartupTimestamp Integer, Long> getStartupTimestamps() {
        if (mStartupTimestampsNs == null) {
            mStartupTimestampsNs = new HashMap<@StartupTimestamp Integer, Long>();
        }
        return mStartupTimestampsNs;
    }

    /**
     * The state of the app at startup.
     *
     * <p class="note"> Note: field will be set for {@link #getStartupState} value
     * {@link #STARTUP_STATE_FIRST_FRAME_DRAWN}. Not guaranteed for other states.</p>
     */
    public @StartType int getStartType() {
        return mStartType;
    }

    /**
     * The intent used to launch the application.
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    @SuppressLint("IntentBuilderName")
    @Nullable
    public Intent getIntent() {
        return mStartIntent;
    }

    /**
     * An instruction on how the activity should be launched. There are five modes that work in
     * conjunction with activity flags in Intent objects to determine what should happen when the
     * activity is called upon to handle an intent.
     *
     * Modes:
     * {@link #LAUNCH_MODE_STANDARD}
     * {@link #LAUNCH_MODE_SINGLE_TOP}
     * {@link #LAUNCH_MODE_SINGLE_INSTANCE}
     * {@link #LAUNCH_MODE_SINGLE_TASK}
     * {@link #LAUNCH_MODE_SINGLE_INSTANCE_PER_TASK}
     *
     * <p class="note"> Note: field will be set for any {@link #getStartupState} value.</p>
     */
    public @LaunchMode int getLaunchMode() {
        return mLaunchMode;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStartupState);
        dest.writeInt(mPid);
        dest.writeInt(mRealUid);
        dest.writeInt(mPackageUid);
        dest.writeInt(mDefiningUid);
        dest.writeString(mProcessName);
        dest.writeInt(mReason);
        dest.writeInt(mStartupTimestampsNs.size());
        for (@StartupTimestamp int key : mStartupTimestampsNs.keySet()) {
            dest.writeInt(key);
            dest.writeLong(mStartupTimestampsNs.get(key));
        }
        dest.writeInt(mStartType);
        dest.writeParcelable(mStartIntent, flags);
        dest.writeInt(mLaunchMode);
    }

    /** @hide */
    public ApplicationStartInfo() {}

    /** @hide */
    public ApplicationStartInfo(ApplicationStartInfo other) {
        mStartupState = other.mStartupState;
        mPid = other.mPid;
        mRealUid = other.mRealUid;
        mPackageUid = other.mPackageUid;
        mDefiningUid = other.mDefiningUid;
        mProcessName = other.mProcessName;
        mReason = other.mReason;
        mStartupTimestampsNs = other.mStartupTimestampsNs;
        mStartType = other.mStartType;
        mStartIntent = other.mStartIntent;
        mLaunchMode = other.mLaunchMode;
    }

    private ApplicationStartInfo(@NonNull Parcel in) {
        mStartupState = in.readInt();
        mPid = in.readInt();
        mRealUid = in.readInt();
        mPackageUid = in.readInt();
        mDefiningUid = in.readInt();
        mProcessName = intern(in.readString());
        mReason = in.readInt();
        int starupTimestampCount = in.readInt();
        for (int i = 0; i < starupTimestampCount; i++) {
            int key = in.readInt();
            long val = in.readLong();
            addStartupTimestamp(key, val);
        }
        mStartType = in.readInt();
        mStartIntent =
                in.readParcelable(Intent.class.getClassLoader(), android.content.Intent.class);
        mLaunchMode = in.readInt();
    }

    private static String intern(@Nullable String source) {
        return source != null ? source.intern() : null;
    }

    public @NonNull static final Creator<ApplicationStartInfo> CREATOR =
            new Creator<ApplicationStartInfo>() {
                @Override
                public ApplicationStartInfo createFromParcel(Parcel in) {
                    return new ApplicationStartInfo(in);
                }

                @Override
                public ApplicationStartInfo[] newArray(int size) {
                    return new ApplicationStartInfo[size];
                }
            };
}
