/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.ActivityThread.DEBUG_ORDER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.ResultInfo;
import android.content.res.CompatibilityInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;
import android.util.MergedConfiguration;
import android.util.Slog;

import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Activity relaunch callback.
 * @hide
 */
public class ActivityRelaunchItem extends ActivityTransactionItem {

    private static final String TAG = "ActivityRelaunchItem";

    private List<ResultInfo> mPendingResults;
    private List<ReferrerIntent> mPendingNewIntents;
    private int mConfigChanges;
    private MergedConfiguration mConfig;
    private boolean mPreserveWindow;

    /**
     * A record that was properly configured for relaunch. Execution will be cancelled if not
     * initialized after {@link #preExecute(ClientTransactionHandler)}.
     */
    private ActivityClientRecord mActivityClientRecord;

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        // The local config is already scaled so only apply if this item is from server side.
        if (!client.isExecutingLocalTransaction()) {
            CompatibilityInfo.applyOverrideScaleIfNeeded(mConfig);
        }
        mActivityClientRecord = client.prepareRelaunchActivity(getActivityToken(), mPendingResults,
                mPendingNewIntents, mConfigChanges, mConfig, mPreserveWindow);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        if (mActivityClientRecord == null) {
            if (DEBUG_ORDER) Slog.d(TAG, "Activity relaunch cancelled");
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityRestart");
        client.handleRelaunchActivity(mActivityClientRecord, pendingActions);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        final ActivityClientRecord r = getActivityClientRecord(client);
        client.reportRelaunch(r);
    }

    // ObjectPoolItem implementation

    private ActivityRelaunchItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static ActivityRelaunchItem obtain(@NonNull IBinder activityToken,
            @Nullable List<ResultInfo> pendingResults,
            @Nullable List<ReferrerIntent> pendingNewIntents, int configChanges,
            @NonNull MergedConfiguration config, boolean preserveWindow) {
        ActivityRelaunchItem instance = ObjectPool.obtain(ActivityRelaunchItem.class);
        if (instance == null) {
            instance = new ActivityRelaunchItem();
        }
        instance.setActivityToken(activityToken);
        instance.mPendingResults = pendingResults != null ? new ArrayList<>(pendingResults) : null;
        instance.mPendingNewIntents =
                pendingNewIntents != null ? new ArrayList<>(pendingNewIntents) : null;
        instance.mConfigChanges = configChanges;
        instance.mConfig = new MergedConfiguration(config);
        instance.mPreserveWindow = preserveWindow;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mPendingResults = null;
        mPendingNewIntents = null;
        mConfigChanges = 0;
        mConfig = null;
        mPreserveWindow = false;
        mActivityClientRecord = null;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedList(mPendingResults, flags);
        dest.writeTypedList(mPendingNewIntents, flags);
        dest.writeInt(mConfigChanges);
        dest.writeTypedObject(mConfig, flags);
        dest.writeBoolean(mPreserveWindow);
    }

    /** Read from Parcel. */
    private ActivityRelaunchItem(@NonNull Parcel in) {
        super(in);
        mPendingResults = in.createTypedArrayList(ResultInfo.CREATOR);
        mPendingNewIntents = in.createTypedArrayList(ReferrerIntent.CREATOR);
        mConfigChanges = in.readInt();
        mConfig = in.readTypedObject(MergedConfiguration.CREATOR);
        mPreserveWindow = in.readBoolean();
    }

    public static final @NonNull Creator<ActivityRelaunchItem> CREATOR =
            new Creator<>() {
                public ActivityRelaunchItem createFromParcel(@NonNull Parcel in) {
                    return new ActivityRelaunchItem(in);
                }

                public ActivityRelaunchItem[] newArray(int size) {
                    return new ActivityRelaunchItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final ActivityRelaunchItem other = (ActivityRelaunchItem) o;
        return Objects.equals(mPendingResults, other.mPendingResults)
                && Objects.equals(mPendingNewIntents, other.mPendingNewIntents)
                && mConfigChanges == other.mConfigChanges && Objects.equals(mConfig, other.mConfig)
                && mPreserveWindow == other.mPreserveWindow;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mPendingResults);
        result = 31 * result + Objects.hashCode(mPendingNewIntents);
        result = 31 * result + mConfigChanges;
        result = 31 * result + Objects.hashCode(mConfig);
        result = 31 * result + (mPreserveWindow ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ActivityRelaunchItem{" + super.toString()
                + ",pendingResults=" + mPendingResults
                + ",pendingNewIntents=" + mPendingNewIntents
                + ",configChanges="  + mConfigChanges
                + ",config=" + mConfig
                + ",preserveWindow" + mPreserveWindow + "}";
    }
}
