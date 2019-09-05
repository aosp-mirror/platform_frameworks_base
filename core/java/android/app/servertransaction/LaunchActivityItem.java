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

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Trace;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.List;
import java.util.Objects;

/**
 * Request to launch an activity.
 * @hide
 */
public class LaunchActivityItem extends ClientTransactionItem {

    @UnsupportedAppUsage
    private Intent mIntent;
    private int mIdent;
    @UnsupportedAppUsage
    private ActivityInfo mInfo;
    private Configuration mCurConfig;
    private Configuration mOverrideConfig;
    private CompatibilityInfo mCompatInfo;
    private String mReferrer;
    private IVoiceInteractor mVoiceInteractor;
    private int mProcState;
    private Bundle mState;
    private PersistableBundle mPersistentState;
    private List<ResultInfo> mPendingResults;
    private List<ReferrerIntent> mPendingNewIntents;
    private boolean mIsForward;
    private ProfilerInfo mProfilerInfo;
    private IBinder mAssistToken;

    @Override
    public void preExecute(ClientTransactionHandler client, IBinder token) {
        client.countLaunchingActivities(1);
        client.updateProcessState(mProcState, false);
        client.updatePendingConfiguration(mCurConfig);
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
        ActivityClientRecord r = new ActivityClientRecord(token, mIntent, mIdent, mInfo,
                mOverrideConfig, mCompatInfo, mReferrer, mVoiceInteractor, mState, mPersistentState,
                mPendingResults, mPendingNewIntents, mIsForward,
                mProfilerInfo, client, mAssistToken);
        client.handleLaunchActivity(r, pendingActions, null /* customIntent */);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        client.countLaunchingActivities(-1);
    }


    // ObjectPoolItem implementation

    private LaunchActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    public static LaunchActivityItem obtain(Intent intent, int ident, ActivityInfo info,
            Configuration curConfig, Configuration overrideConfig, CompatibilityInfo compatInfo,
            String referrer, IVoiceInteractor voiceInteractor, int procState, Bundle state,
            PersistableBundle persistentState, List<ResultInfo> pendingResults,
            List<ReferrerIntent> pendingNewIntents, boolean isForward, ProfilerInfo profilerInfo,
            IBinder assistToken) {
        LaunchActivityItem instance = ObjectPool.obtain(LaunchActivityItem.class);
        if (instance == null) {
            instance = new LaunchActivityItem();
        }
        setValues(instance, intent, ident, info, curConfig, overrideConfig, compatInfo, referrer,
                voiceInteractor, procState, state, persistentState, pendingResults,
                pendingNewIntents, isForward, profilerInfo, assistToken);

        return instance;
    }

    @Override
    public void recycle() {
        setValues(this, null, 0, null, null, null, null, null, null, 0, null, null, null, null,
                false, null, null);
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write from Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mIntent, flags);
        dest.writeInt(mIdent);
        dest.writeTypedObject(mInfo, flags);
        dest.writeTypedObject(mCurConfig, flags);
        dest.writeTypedObject(mOverrideConfig, flags);
        dest.writeTypedObject(mCompatInfo, flags);
        dest.writeString(mReferrer);
        dest.writeStrongInterface(mVoiceInteractor);
        dest.writeInt(mProcState);
        dest.writeBundle(mState);
        dest.writePersistableBundle(mPersistentState);
        dest.writeTypedList(mPendingResults, flags);
        dest.writeTypedList(mPendingNewIntents, flags);
        dest.writeBoolean(mIsForward);
        dest.writeTypedObject(mProfilerInfo, flags);
        dest.writeStrongBinder(mAssistToken);
    }

    /** Read from Parcel. */
    private LaunchActivityItem(Parcel in) {
        setValues(this, in.readTypedObject(Intent.CREATOR), in.readInt(),
                in.readTypedObject(ActivityInfo.CREATOR), in.readTypedObject(Configuration.CREATOR),
                in.readTypedObject(Configuration.CREATOR),
                in.readTypedObject(CompatibilityInfo.CREATOR), in.readString(),
                IVoiceInteractor.Stub.asInterface(in.readStrongBinder()), in.readInt(),
                in.readBundle(getClass().getClassLoader()),
                in.readPersistableBundle(getClass().getClassLoader()),
                in.createTypedArrayList(ResultInfo.CREATOR),
                in.createTypedArrayList(ReferrerIntent.CREATOR), in.readBoolean(),
                in.readTypedObject(ProfilerInfo.CREATOR),
                in.readStrongBinder());
    }

    public static final @android.annotation.NonNull Creator<LaunchActivityItem> CREATOR =
            new Creator<LaunchActivityItem>() {
        public LaunchActivityItem createFromParcel(Parcel in) {
            return new LaunchActivityItem(in);
        }

        public LaunchActivityItem[] newArray(int size) {
            return new LaunchActivityItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LaunchActivityItem other = (LaunchActivityItem) o;
        final boolean intentsEqual = (mIntent == null && other.mIntent == null)
                || (mIntent != null && mIntent.filterEquals(other.mIntent));
        return intentsEqual && mIdent == other.mIdent
                && activityInfoEqual(other.mInfo) && Objects.equals(mCurConfig, other.mCurConfig)
                && Objects.equals(mOverrideConfig, other.mOverrideConfig)
                && Objects.equals(mCompatInfo, other.mCompatInfo)
                && Objects.equals(mReferrer, other.mReferrer)
                && mProcState == other.mProcState && areBundlesEqual(mState, other.mState)
                && areBundlesEqual(mPersistentState, other.mPersistentState)
                && Objects.equals(mPendingResults, other.mPendingResults)
                && Objects.equals(mPendingNewIntents, other.mPendingNewIntents)
                && mIsForward == other.mIsForward
                && Objects.equals(mProfilerInfo, other.mProfilerInfo)
                && Objects.equals(mAssistToken, other.mAssistToken);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mIntent.filterHashCode();
        result = 31 * result + mIdent;
        result = 31 * result + Objects.hashCode(mCurConfig);
        result = 31 * result + Objects.hashCode(mOverrideConfig);
        result = 31 * result + Objects.hashCode(mCompatInfo);
        result = 31 * result + Objects.hashCode(mReferrer);
        result = 31 * result + Objects.hashCode(mProcState);
        result = 31 * result + (mState != null ? mState.size() : 0);
        result = 31 * result + (mPersistentState != null ? mPersistentState.size() : 0);
        result = 31 * result + Objects.hashCode(mPendingResults);
        result = 31 * result + Objects.hashCode(mPendingNewIntents);
        result = 31 * result + (mIsForward ? 1 : 0);
        result = 31 * result + Objects.hashCode(mProfilerInfo);
        result = 31 * result + Objects.hashCode(mAssistToken);
        return result;
    }

    private boolean activityInfoEqual(ActivityInfo other) {
        if (mInfo == null) {
            return other == null;
        }
        return other != null && mInfo.flags == other.flags
                && mInfo.maxAspectRatio == other.maxAspectRatio
                && Objects.equals(mInfo.launchToken, other.launchToken)
                && Objects.equals(mInfo.getComponentName(), other.getComponentName());
    }

    private static boolean areBundlesEqual(BaseBundle extras, BaseBundle newExtras) {
        if (extras == null || newExtras == null) {
            return extras == newExtras;
        }

        if (extras.size() != newExtras.size()) {
            return false;
        }

        for (String key : extras.keySet()) {
            if (key != null) {
                final Object value = extras.get(key);
                final Object newValue = newExtras.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "LaunchActivityItem{intent=" + mIntent + ",ident=" + mIdent + ",info=" + mInfo
                + ",curConfig=" + mCurConfig + ",overrideConfig=" + mOverrideConfig
                + ",referrer=" + mReferrer + ",procState=" + mProcState + ",state=" + mState
                + ",persistentState=" + mPersistentState + ",pendingResults=" + mPendingResults
                + ",pendingNewIntents=" + mPendingNewIntents + ",profilerInfo=" + mProfilerInfo
                + " assistToken=" + mAssistToken
                + "}";
    }

    // Using the same method to set and clear values to make sure we don't forget anything
    private static void setValues(LaunchActivityItem instance, Intent intent, int ident,
            ActivityInfo info, Configuration curConfig, Configuration overrideConfig,
            CompatibilityInfo compatInfo, String referrer, IVoiceInteractor voiceInteractor,
            int procState, Bundle state, PersistableBundle persistentState,
            List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
            boolean isForward, ProfilerInfo profilerInfo, IBinder assistToken) {
        instance.mIntent = intent;
        instance.mIdent = ident;
        instance.mInfo = info;
        instance.mCurConfig = curConfig;
        instance.mOverrideConfig = overrideConfig;
        instance.mCompatInfo = compatInfo;
        instance.mReferrer = referrer;
        instance.mVoiceInteractor = voiceInteractor;
        instance.mProcState = procState;
        instance.mState = state;
        instance.mPersistentState = persistentState;
        instance.mPendingResults = pendingResults;
        instance.mPendingNewIntents = pendingNewIntents;
        instance.mIsForward = isForward;
        instance.mProfilerInfo = profilerInfo;
        instance.mAssistToken = assistToken;
    }
}
