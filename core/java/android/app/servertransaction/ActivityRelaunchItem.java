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

import static java.util.Objects.requireNonNull;

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
import android.window.ActivityWindowInfo;

import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Activity relaunch callback.
 *
 * @hide
 */
public class ActivityRelaunchItem extends ActivityTransactionItem {

    private static final String TAG = "ActivityRelaunchItem";

    @Nullable
    private final List<ResultInfo> mPendingResults;

    @Nullable
    private final List<ReferrerIntent> mPendingNewIntents;

    @NonNull
    private final MergedConfiguration mConfig;

    @NonNull
    private final ActivityWindowInfo mActivityWindowInfo;

    private final int mConfigChanges;
    private final boolean mPreserveWindow;

    /**
     * A record that was properly configured for relaunch. Execution will be cancelled if not
     * initialized after {@link #preExecute(ClientTransactionHandler)}.
     */
    @Nullable
    private ActivityClientRecord mActivityClientRecord;

    public ActivityRelaunchItem(@NonNull IBinder activityToken,
            @Nullable List<ResultInfo> pendingResults,
            @Nullable List<ReferrerIntent> pendingNewIntents, int configChanges,
            @NonNull MergedConfiguration config, boolean preserveWindow,
            @NonNull ActivityWindowInfo activityWindowInfo) {
        super(activityToken);
        mPendingResults = pendingResults != null ? new ArrayList<>(pendingResults) : null;
        mPendingNewIntents =
                pendingNewIntents != null ? new ArrayList<>(pendingNewIntents) : null;
        mConfig = new MergedConfiguration(config);
        mActivityWindowInfo = new ActivityWindowInfo(activityWindowInfo);
        mConfigChanges = configChanges;
        mPreserveWindow = preserveWindow;
    }

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        // The local config is already scaled so only apply if this item is from server side.
        if (!client.isExecutingLocalTransaction()) {
            CompatibilityInfo.applyOverrideScaleIfNeeded(mConfig);
        }
        mActivityClientRecord = client.prepareRelaunchActivity(getActivityToken(), mPendingResults,
                mPendingNewIntents, mConfigChanges, mConfig, mPreserveWindow, mActivityWindowInfo);
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

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedList(mPendingResults, flags);
        dest.writeTypedList(mPendingNewIntents, flags);
        dest.writeTypedObject(mConfig, flags);
        dest.writeTypedObject(mActivityWindowInfo, flags);
        dest.writeInt(mConfigChanges);
        dest.writeBoolean(mPreserveWindow);
    }

    /** Reads from Parcel. */
    private ActivityRelaunchItem(@NonNull Parcel in) {
        super(in);
        mPendingResults = in.createTypedArrayList(ResultInfo.CREATOR);
        mPendingNewIntents = in.createTypedArrayList(ReferrerIntent.CREATOR);
        mConfig = requireNonNull(in.readTypedObject(MergedConfiguration.CREATOR));
        mActivityWindowInfo = requireNonNull(in.readTypedObject(ActivityWindowInfo.CREATOR));
        mConfigChanges = in.readInt();
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
                && Objects.equals(mConfig, other.mConfig)
                && Objects.equals(mActivityWindowInfo, other.mActivityWindowInfo)
                && mConfigChanges == other.mConfigChanges
                && mPreserveWindow == other.mPreserveWindow;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mPendingResults);
        result = 31 * result + Objects.hashCode(mPendingNewIntents);
        result = 31 * result + Objects.hashCode(mConfig);
        result = 31 * result + Objects.hashCode(mActivityWindowInfo);
        result = 31 * result + mConfigChanges;
        result = 31 * result + (mPreserveWindow ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ActivityRelaunchItem{" + super.toString()
                + ",pendingResults=" + mPendingResults
                + ",pendingNewIntents=" + mPendingNewIntents
                + ",config=" + mConfig
                + ",activityWindowInfo=" + mActivityWindowInfo
                + ",configChanges=" + mConfigChanges
                + ",preserveWindow=" + mPreserveWindow + "}";
    }
}
