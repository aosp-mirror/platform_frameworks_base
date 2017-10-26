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

import android.app.ClientTransactionHandler;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Trace;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.List;

/**
 * Request to launch an activity.
 * @hide
 */
public class LaunchActivityItem extends ActivityLifecycleItem {

    private final Intent mIntent;
    private final int mIdent;
    private final ActivityInfo mInfo;
    private final Configuration mCurConfig;
    private final Configuration mOverrideConfig;
    private final CompatibilityInfo mCompatInfo;
    private final String mReferrer;
    private final IVoiceInteractor mVoiceInteractor;
    private final int mProcState;
    private final Bundle mState;
    private final PersistableBundle mPersistentState;
    private final List<ResultInfo> mPendingResults;
    private final List<ReferrerIntent> mPendingNewIntents;
    // TODO(lifecycler): use lifecycle request instead of this param.
    private final boolean mNotResumed;
    private final boolean mIsForward;
    private final ProfilerInfo mProfilerInfo;

    public LaunchActivityItem(Intent intent, int ident, ActivityInfo info,
            Configuration curConfig, Configuration overrideConfig, CompatibilityInfo compatInfo,
            String referrer, IVoiceInteractor voiceInteractor, int procState, Bundle state,
            PersistableBundle persistentState, List<ResultInfo> pendingResults,
            List<ReferrerIntent> pendingNewIntents, boolean notResumed, boolean isForward,
            ProfilerInfo profilerInfo) {
        mIntent = intent;
        mIdent = ident;
        mInfo = info;
        mCurConfig = curConfig;
        mOverrideConfig = overrideConfig;
        mCompatInfo = compatInfo;
        mReferrer = referrer;
        mVoiceInteractor = voiceInteractor;
        mProcState = procState;
        mState = state;
        mPersistentState = persistentState;
        mPendingResults = pendingResults;
        mPendingNewIntents = pendingNewIntents;
        mNotResumed = notResumed;
        mIsForward = isForward;
        mProfilerInfo = profilerInfo;
    }

    @Override
    public void prepare(ClientTransactionHandler client, IBinder token) {
        client.updateProcessState(mProcState, false);
        client.updatePendingConfiguration(mCurConfig);
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
        client.handleLaunchActivity(token, mIntent, mIdent, mInfo, mOverrideConfig, mCompatInfo,
                mReferrer, mVoiceInteractor, mState, mPersistentState, mPendingResults,
                mPendingNewIntents, mNotResumed, mIsForward, mProfilerInfo);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return mNotResumed ? PAUSED : RESUMED;
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
        dest.writeStrongBinder(mVoiceInteractor != null ? mVoiceInteractor.asBinder() : null);
        dest.writeInt(mProcState);
        dest.writeBundle(mState);
        dest.writePersistableBundle(mPersistentState);
        dest.writeTypedList(mPendingResults, flags);
        dest.writeTypedList(mPendingNewIntents, flags);
        dest.writeBoolean(mNotResumed);
        dest.writeBoolean(mIsForward);
        dest.writeTypedObject(mProfilerInfo, flags);
    }

    /** Read from Parcel. */
    private LaunchActivityItem(Parcel in) {
        mIntent = in.readTypedObject(Intent.CREATOR);
        mIdent = in.readInt();
        mInfo = in.readTypedObject(ActivityInfo.CREATOR);
        mCurConfig = in.readTypedObject(Configuration.CREATOR);
        mOverrideConfig = in.readTypedObject(Configuration.CREATOR);
        mCompatInfo = in.readTypedObject(CompatibilityInfo.CREATOR);
        mReferrer = in.readString();
        mVoiceInteractor = (IVoiceInteractor) in.readStrongBinder();
        mProcState = in.readInt();
        mState = in.readBundle(getClass().getClassLoader());
        mPersistentState = in.readPersistableBundle(getClass().getClassLoader());
        mPendingResults = in.createTypedArrayList(ResultInfo.CREATOR);
        mPendingNewIntents = in.createTypedArrayList(ReferrerIntent.CREATOR);
        mNotResumed = in.readBoolean();
        mIsForward = in.readBoolean();
        mProfilerInfo = in.readTypedObject(ProfilerInfo.CREATOR);
    }

    public static final Creator<LaunchActivityItem> CREATOR =
            new Creator<LaunchActivityItem>() {
        public LaunchActivityItem createFromParcel(Parcel in) {
            return new LaunchActivityItem(in);
        }

        public LaunchActivityItem[] newArray(int size) {
            return new LaunchActivityItem[size];
        }
    };
}
