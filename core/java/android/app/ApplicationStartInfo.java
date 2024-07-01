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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.icu.text.SimpleDateFormat;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Xml;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.WireTypeMismatchException;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.Objects;

/**
 * Describes information related to an application process's startup.
 *
 * <p>
 * Many aspects concerning why and how an applications process was started are valuable for apps
 * both for logging and for potential behavior changes. Reason for process start, start type,
 * start times, throttling, and other useful diagnostic data can be obtained from
 * {@link ApplicationStartInfo} records.
 * </p>
 *
 * <p>
*  ApplicationStartInfo objects can be retrieved via:
*  - {@link ActivityManager#getHistoricalProcessStartReasons}, which can be called during or after
 *      a application's startup. Using this method, an app can retrieve information about an
 *      in-progress app start.
*  - {@link ActivityManager#addApplicationStartInfoCompletionListener}, which returns an
 *      ApplicationStartInfo object via a callback when the startup is complete, or immediately
 *      if requested after the startup is complete.
 * </p>
 */
@FlaggedApi(Flags.FLAG_APP_START_INFO)
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

    /** Process started from launcher recents. */
    public static final int START_REASON_LAUNCHER_RECENTS = 7;

    /** Process started not for any of the listed reasons. */
    public static final int START_REASON_OTHER = 8;

    /** Process started due to push message. */
    public static final int START_REASON_PUSH = 9;

    /** Process service started. */
    public static final int START_REASON_SERVICE = 10;

    /** Process started due to Activity started for any reason not explicitly listed. */
    public static final int START_REASON_START_ACTIVITY = 11;

    /** Start type not yet set. */
    public static final int START_TYPE_UNSET = 0;

    /** Process started from scratch. */
    public static final int START_TYPE_COLD = 1;

    /** Process retained minimally SavedInstanceState. */
    public static final int START_TYPE_WARM = 2;

    /** Process brought back to foreground. */
    public static final int START_TYPE_HOT = 3;

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

    /** The end of the range, beginning with 0, reserved for system timestamps.*/
    public static final int START_TIMESTAMP_RESERVED_RANGE_SYSTEM = 20;

    /** The beginning of the range reserved for developer supplied timestamps.*/
    public static final int START_TIMESTAMP_RESERVED_RANGE_DEVELOPER_START =
            START_TIMESTAMP_RESERVED_RANGE_SYSTEM + 1;

    /** The end of the range reserved for developer supplied timestamps.*/
    public static final int START_TIMESTAMP_RESERVED_RANGE_DEVELOPER = 30;

    /** Clock monotonic timestamp of launch started. */
    public static final int START_TIMESTAMP_LAUNCH = 0;

    /** Clock monotonic timestamp of process fork. */
    public static final int START_TIMESTAMP_FORK = 1;

    /** Clock monotonic timestamp of Application onCreate called. */
    public static final int START_TIMESTAMP_APPLICATION_ONCREATE = 2;

    /** Clock monotonic timestamp of bindApplication called. */
    public static final int START_TIMESTAMP_BIND_APPLICATION = 3;

    /** Clock monotonic timestamp of first frame drawn. */
    public static final int START_TIMESTAMP_FIRST_FRAME = 4;

    /** Clock monotonic timestamp of reportFullyDrawn called by application. */
    public static final int START_TIMESTAMP_FULLY_DRAWN = 5;

    /** Clock monotonic timestamp of initial renderthread frame. */
    public static final int START_TIMESTAMP_INITIAL_RENDERTHREAD_FRAME = 6;

    /** Clock monotonic timestamp of surfaceflinger composition complete. */
    public static final int START_TIMESTAMP_SURFACEFLINGER_COMPOSITION_COMPLETE = 7;

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
     * @see #getPackageName
     */
    private String mPackageName;

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
    private ArrayMap<Integer, Long> mStartupTimestampsNs;

    /**
     * @see #getStartType
     */
    private @StartType int mStartType;

    /**
     * @see #getIntent
     */
    private Intent mStartIntent;

    /**
     * @see #getLaunchMode
     */
    private @LaunchMode int mLaunchMode;

    /**
     * @see #wasForceStopped()
     */
    private boolean mWasForceStopped;

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
                START_REASON_LAUNCHER_RECENTS,
                START_REASON_OTHER,
                START_REASON_PUSH,
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
                START_TYPE_UNSET,
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
     * @see #getPackageName
     * @hide
     */
    public void setPackageName(final String packageName) {
        mPackageName = intern(packageName);
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
    public void addStartupTimestamp(int key, long timestampNs) {
        if (key < 0 || key > START_TIMESTAMP_RESERVED_RANGE_DEVELOPER) {
            return;
        }
        if (mStartupTimestampsNs == null) {
            mStartupTimestampsNs = new ArrayMap<Integer, Long>();
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
     *
     * <p class="note"> Note: This method will clone the provided intent and ensure that the cloned
     * intent doesn't contain any large objects like bitmaps in its extras by stripping it in the
     * least aggressive acceptable way for the individual intent.</p>
     *
     * @hide
     */
    public void setIntent(Intent startIntent) {
        if (startIntent != null) {
            if (startIntent.canStripForHistory()) {
                // If maybeStripForHistory will return a lightened version, do that.
                mStartIntent = startIntent.maybeStripForHistory();
            } else if (startIntent.getExtras() != null) {
                // If maybeStripForHistory would not return a lightened version and extras is
                // non-null then extras contains un-parcelled data. Use cloneFilter to strip data
                // more aggressively.
                mStartIntent = startIntent.cloneFilter();
            } else {
                // Finally, if maybeStripForHistory would not return a lightened version and extras
                // is null then do a regular clone so we don't leak the intent.
                mStartIntent = new Intent(startIntent);
            }

            // If the newly cloned intent has an original intent, clear that as we don't need it and
            // can't guarantee it doesn't need to be stripped as well.
            if (mStartIntent.getOriginalIntent() != null) {
                mStartIntent.setOriginalIntent(null);
            }
        }
    }

    /**
     * @see #getLaunchMode
     * @hide
     */
    public void setLaunchMode(@LaunchMode int launchMode) {
        mLaunchMode = launchMode;
    }

    /**
     * @see #wasForceStopped()
     * @param wasForceStopped whether the app had been force-stopped in the past
     * @hide
     */
    public void setForceStopped(boolean wasForceStopped) {
        mWasForceStopped = wasForceStopped;
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
     * Name of first package running in this process;
     *
     * @hide
     */
    public String getPackageName() {
        return mPackageName;
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
     * {@link #START_TIMESTAMP_APPLICATION_ONCREATE}, {@link #START_TIMESTAMP_BIND_APPLICATION},
     * and {@link #START_TIMESTAMP_FIRST_FRAME} will additionally be available.
     *
     * Timestamp {@link #START_TIMESTAMP_FULLY_DRAWN} is never guaranteed to be available as it is
     * dependant on devloper calling {@link Activity#reportFullyDrawn}.
     * </p>
     */
    public @NonNull Map<Integer, Long> getStartupTimestamps() {
        if (mStartupTimestampsNs == null) {
            mStartupTimestampsNs = new ArrayMap<Integer, Long>();
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
     * <p class="note"> Note: Intent is stripped and does not include extras.</p>
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

    /**
     * Informs whether this is the first process launch for an app since it was
     * {@link ApplicationInfo#FLAG_STOPPED force-stopped} for some reason.
     * This allows the app to know if it should re-register for any alarms, jobs and other callbacks
     * that were cleared when the app was force-stopped.
     *
     * @return {@code true} if this is the first process launch of the app after having been
     *      stopped, {@code false} otherwise.
     */
    @FlaggedApi(android.content.pm.Flags.FLAG_STAY_STOPPED)
    public boolean wasForceStopped() {
        return mWasForceStopped;
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
        dest.writeString(mPackageName);
        dest.writeString(mProcessName);
        dest.writeInt(mReason);
        dest.writeInt(mStartupTimestampsNs == null ? 0 : mStartupTimestampsNs.size());
        if (mStartupTimestampsNs != null) {
            for (int i = 0; i < mStartupTimestampsNs.size(); i++) {
                dest.writeInt(mStartupTimestampsNs.keyAt(i));
                dest.writeLong(mStartupTimestampsNs.valueAt(i));
            }
        }
        dest.writeInt(mStartType);
        dest.writeParcelable(mStartIntent, flags);
        dest.writeInt(mLaunchMode);
        dest.writeBoolean(mWasForceStopped);
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
        mPackageName = other.mPackageName;
        mProcessName = other.mProcessName;
        mReason = other.mReason;
        mStartupTimestampsNs = other.mStartupTimestampsNs;
        mStartType = other.mStartType;
        mStartIntent = other.mStartIntent;
        mLaunchMode = other.mLaunchMode;
        mWasForceStopped = other.mWasForceStopped;
    }

    private ApplicationStartInfo(@NonNull Parcel in) {
        mStartupState = in.readInt();
        mPid = in.readInt();
        mRealUid = in.readInt();
        mPackageUid = in.readInt();
        mDefiningUid = in.readInt();
        mPackageName = intern(in.readString());
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
        mWasForceStopped = in.readBoolean();
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

    private static final String PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMPS = "timestamps";
    private static final String PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMP = "timestamp";
    private static final String PROTO_SERIALIZER_ATTRIBUTE_KEY = "key";
    private static final String PROTO_SERIALIZER_ATTRIBUTE_TS = "ts";
    private static final String PROTO_SERIALIZER_ATTRIBUTE_INTENT = "intent";

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition at {@link
     * android.app.ApplicationStartInfoProto}
     *
     * @param proto Stream to write the ApplicationStartInfo object to.
     * @param fieldId Field Id of the ApplicationStartInfo as defined in the parent message
     * @hide
     */
    public void writeToProto(ProtoOutputStream proto, long fieldId) throws IOException {
        final long token = proto.start(fieldId);
        proto.write(ApplicationStartInfoProto.PID, mPid);
        proto.write(ApplicationStartInfoProto.REAL_UID, mRealUid);
        proto.write(ApplicationStartInfoProto.PACKAGE_UID, mPackageUid);
        proto.write(ApplicationStartInfoProto.DEFINING_UID, mDefiningUid);
        proto.write(ApplicationStartInfoProto.PROCESS_NAME, mProcessName);
        proto.write(ApplicationStartInfoProto.STARTUP_STATE, mStartupState);
        proto.write(ApplicationStartInfoProto.REASON, mReason);
        if (mStartupTimestampsNs != null && mStartupTimestampsNs.size() > 0) {
            ByteArrayOutputStream timestampsBytes = new ByteArrayOutputStream();
            ObjectOutputStream timestampsOut = new ObjectOutputStream(timestampsBytes);
            TypedXmlSerializer serializer = Xml.resolveSerializer(timestampsOut);
            serializer.startDocument(null, true);
            serializer.startTag(null, PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMPS);
            for (int i = 0; i < mStartupTimestampsNs.size(); i++) {
                serializer.startTag(null, PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMP);
                serializer.attributeInt(null, PROTO_SERIALIZER_ATTRIBUTE_KEY,
                        mStartupTimestampsNs.keyAt(i));
                serializer.attributeLong(null, PROTO_SERIALIZER_ATTRIBUTE_TS,
                        mStartupTimestampsNs.valueAt(i));
                serializer.endTag(null, PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMP);
            }
            serializer.endTag(null, PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMPS);
            serializer.endDocument();
            proto.write(ApplicationStartInfoProto.STARTUP_TIMESTAMPS,
                    timestampsBytes.toByteArray());
            timestampsOut.close();
        }
        proto.write(ApplicationStartInfoProto.START_TYPE, mStartType);
        if (mStartIntent != null) {
            ByteArrayOutputStream intentBytes = new ByteArrayOutputStream();
            ObjectOutputStream intentOut = new ObjectOutputStream(intentBytes);
            TypedXmlSerializer serializer = Xml.resolveSerializer(intentOut);
            serializer.startDocument(null, true);
            serializer.startTag(null, PROTO_SERIALIZER_ATTRIBUTE_INTENT);
            mStartIntent.saveToXml(serializer);
            serializer.endTag(null, PROTO_SERIALIZER_ATTRIBUTE_INTENT);
            serializer.endDocument();
            proto.write(ApplicationStartInfoProto.START_INTENT,
                    intentBytes.toByteArray());
            intentOut.close();
        }
        proto.write(ApplicationStartInfoProto.LAUNCH_MODE, mLaunchMode);
        proto.write(ApplicationStartInfoProto.WAS_FORCE_STOPPED, mWasForceStopped);
        proto.end(token);
    }

    /**
     * Read from a protocol buffer input stream. Protocol buffer message definition at {@link
     * android.app.ApplicationStartInfoProto}
     *
     * @param proto Stream to read the ApplicationStartInfo object from.
     * @param fieldId Field Id of the ApplicationStartInfo as defined in the parent message
     * @hide
     */
    public void readFromProto(ProtoInputStream proto, long fieldId)
            throws IOException, WireTypeMismatchException, ClassNotFoundException {
        final long token = proto.start(fieldId);
        while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (proto.getFieldNumber()) {
                case (int) ApplicationStartInfoProto.PID:
                    mPid = proto.readInt(ApplicationStartInfoProto.PID);
                    break;
                case (int) ApplicationStartInfoProto.REAL_UID:
                    mRealUid = proto.readInt(ApplicationStartInfoProto.REAL_UID);
                    break;
                case (int) ApplicationStartInfoProto.PACKAGE_UID:
                    mPackageUid = proto.readInt(ApplicationStartInfoProto.PACKAGE_UID);
                    break;
                case (int) ApplicationStartInfoProto.DEFINING_UID:
                    mDefiningUid = proto.readInt(ApplicationStartInfoProto.DEFINING_UID);
                    break;
                case (int) ApplicationStartInfoProto.PROCESS_NAME:
                    mProcessName = intern(proto.readString(ApplicationStartInfoProto.PROCESS_NAME));
                    break;
                case (int) ApplicationStartInfoProto.STARTUP_STATE:
                    mStartupState = proto.readInt(ApplicationStartInfoProto.STARTUP_STATE);
                    break;
                case (int) ApplicationStartInfoProto.REASON:
                    mReason = proto.readInt(ApplicationStartInfoProto.REASON);
                    break;
                case (int) ApplicationStartInfoProto.STARTUP_TIMESTAMPS:
                    ByteArrayInputStream timestampsBytes = new ByteArrayInputStream(proto.readBytes(
                            ApplicationStartInfoProto.STARTUP_TIMESTAMPS));
                    ObjectInputStream timestampsIn = new ObjectInputStream(timestampsBytes);
                    mStartupTimestampsNs = new ArrayMap<Integer, Long>();
                    try {
                        TypedXmlPullParser parser = Xml.resolvePullParser(timestampsIn);
                        XmlUtils.beginDocument(parser, PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMPS);
                        int depth = parser.getDepth();
                        while (XmlUtils.nextElementWithin(parser, depth)) {
                            if (PROTO_SERIALIZER_ATTRIBUTE_TIMESTAMP.equals(parser.getName())) {
                                int key = parser.getAttributeInt(null,
                                        PROTO_SERIALIZER_ATTRIBUTE_KEY);
                                long ts = parser.getAttributeLong(null,
                                        PROTO_SERIALIZER_ATTRIBUTE_TS);
                                mStartupTimestampsNs.put(key, ts);
                            }
                        }
                    } catch (XmlPullParserException e) {
                        // Timestamps lost
                    }
                    timestampsIn.close();
                    break;
                case (int) ApplicationStartInfoProto.START_TYPE:
                    mStartType = proto.readInt(ApplicationStartInfoProto.START_TYPE);
                    break;
                case (int) ApplicationStartInfoProto.START_INTENT:
                    ByteArrayInputStream intentBytes = new ByteArrayInputStream(proto.readBytes(
                            ApplicationStartInfoProto.START_INTENT));
                    ObjectInputStream intentIn = new ObjectInputStream(intentBytes);
                    try {
                        TypedXmlPullParser parser = Xml.resolvePullParser(intentIn);
                        XmlUtils.beginDocument(parser, PROTO_SERIALIZER_ATTRIBUTE_INTENT);
                        mStartIntent = Intent.restoreFromXml(parser);
                    } catch (XmlPullParserException e) {
                        // Intent lost
                    }
                    intentIn.close();
                    break;
                case (int) ApplicationStartInfoProto.LAUNCH_MODE:
                    mLaunchMode = proto.readInt(ApplicationStartInfoProto.LAUNCH_MODE);
                    break;
                case (int) ApplicationStartInfoProto.WAS_FORCE_STOPPED:
                    mWasForceStopped = proto.readBoolean(
                            ApplicationStartInfoProto.WAS_FORCE_STOPPED);
                    break;
            }
        }
        proto.end(token);
    }

    /** @hide */
    public void dump(@NonNull PrintWriter pw, @Nullable String prefix, @Nullable String seqSuffix,
            @NonNull SimpleDateFormat sdf) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix)
                .append("ApplicationStartInfo ").append(seqSuffix).append(':')
                .append('\n')
                .append(" pid=").append(mPid)
                .append(" realUid=").append(mRealUid)
                .append(" packageUid=").append(mPackageUid)
                .append(" definingUid=").append(mDefiningUid)
                .append(" user=").append(UserHandle.getUserId(mPackageUid))
                .append('\n')
                .append(" package=").append(mPackageName)
                .append(" process=").append(mProcessName)
                .append(" startupState=").append(mStartupState)
                .append(" reason=").append(reasonToString(mReason))
                .append(" startType=").append(startTypeToString(mStartType))
                .append(" launchMode=").append(mLaunchMode)
                .append(" wasForceStopped=").append(mWasForceStopped)
                .append('\n');
        if (mStartIntent != null) {
            sb.append(" intent=").append(mStartIntent.toString())
                .append('\n');
        }
        if (mStartupTimestampsNs != null && mStartupTimestampsNs.size() > 0) {
            sb.append(" timestamps: ");
            for (int i = 0; i < mStartupTimestampsNs.size(); i++) {
                sb.append(mStartupTimestampsNs.keyAt(i)).append("=").append(mStartupTimestampsNs
                        .valueAt(i)).append(" ");
            }
            sb.append('\n');
        }
        pw.print(sb.toString());
    }

    private static String reasonToString(@StartReason int reason) {
        return switch (reason) {
            case START_REASON_ALARM -> "ALARM";
            case START_REASON_BACKUP -> "BACKUP";
            case START_REASON_BOOT_COMPLETE -> "BOOT COMPLETE";
            case START_REASON_BROADCAST -> "BROADCAST";
            case START_REASON_CONTENT_PROVIDER -> "CONTENT PROVIDER";
            case START_REASON_JOB -> "JOB";
            case START_REASON_LAUNCHER -> "LAUNCHER";
            case START_REASON_LAUNCHER_RECENTS -> "LAUNCHER RECENTS";
            case START_REASON_OTHER -> "OTHER";
            case START_REASON_PUSH -> "PUSH";
            case START_REASON_SERVICE -> "SERVICE";
            case START_REASON_START_ACTIVITY -> "START ACTIVITY";
            default -> "";
        };
    }

    private static String startTypeToString(@StartType int startType) {
        return switch (startType) {
            case START_TYPE_UNSET -> "UNSET";
            case START_TYPE_COLD -> "COLD";
            case START_TYPE_WARM -> "WARM";
            case START_TYPE_HOT -> "HOT";
            default -> "";
        };
    }

    /** @hide */
    @Override
    public boolean equals(@Nullable Object other) {
        if (other == null || !(other instanceof ApplicationStartInfo)) {
            return false;
        }
        final ApplicationStartInfo o = (ApplicationStartInfo) other;
        return mPid == o.mPid && mRealUid == o.mRealUid && mPackageUid == o.mPackageUid
            && mDefiningUid == o.mDefiningUid && mReason == o.mReason
            && mStartupState == o.mStartupState && mStartType == o.mStartType
            && mLaunchMode == o.mLaunchMode && TextUtils.equals(mProcessName, o.mProcessName)
            && timestampsEquals(o) && mWasForceStopped == o.mWasForceStopped;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPid, mRealUid, mPackageUid, mDefiningUid, mReason, mStartupState,
                mStartType, mLaunchMode, mProcessName,
                mStartupTimestampsNs);
    }

    private boolean timestampsEquals(@NonNull ApplicationStartInfo other) {
        if (mStartupTimestampsNs == null && other.mStartupTimestampsNs == null) {
            return true;
        }
        if (mStartupTimestampsNs == null || other.mStartupTimestampsNs == null) {
            return false;
        }
        return mStartupTimestampsNs.equals(other.mStartupTimestampsNs);
    }
}
