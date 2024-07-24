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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityClient;
import android.app.ActivityOptions.SceneTransitionInfo;
import android.app.ActivityThread;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.IActivityClientController;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
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
import android.window.ActivityWindowInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request to launch an activity.
 * @hide
 */
public class LaunchActivityItem extends ClientTransactionItem {

    private IBinder mActivityToken;
    @UnsupportedAppUsage
    private Intent mIntent;
    private int mIdent;
    @UnsupportedAppUsage
    private ActivityInfo mInfo;
    private Configuration mCurConfig;
    private Configuration mOverrideConfig;
    private int mDeviceId;
    private String mReferrer;
    private IVoiceInteractor mVoiceInteractor;
    private int mProcState;
    private Bundle mState;
    private PersistableBundle mPersistentState;
    private List<ResultInfo> mPendingResults;
    private List<ReferrerIntent> mPendingNewIntents;
    private SceneTransitionInfo mSceneTransitionInfo;
    private boolean mIsForward;
    private ProfilerInfo mProfilerInfo;
    private IBinder mAssistToken;
    private IBinder mShareableActivityToken;
    private boolean mLaunchedFromBubble;
    private IBinder mTaskFragmentToken;
    private IBinder mInitialCallerInfoAccessToken;
    private ActivityWindowInfo mActivityWindowInfo;

    /**
     * It is only non-null if the process is the first time to launch activity. It is only an
     * optimization for quick look up of the interface so the field is ignored for comparison.
     */
    private IActivityClientController mActivityClientController;

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        client.countLaunchingActivities(1);
        client.updateProcessState(mProcState, false);
        CompatibilityInfo.applyOverrideScaleIfNeeded(mCurConfig);
        CompatibilityInfo.applyOverrideScaleIfNeeded(mOverrideConfig);
        client.updatePendingConfiguration(mCurConfig);
        if (mActivityClientController != null) {
            ActivityClient.setActivityClientController(mActivityClientController);
        }
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStart");
        ActivityClientRecord r = new ActivityClientRecord(mActivityToken, mIntent, mIdent, mInfo,
                mOverrideConfig, mReferrer, mVoiceInteractor, mState, mPersistentState,
                mPendingResults, mPendingNewIntents, mSceneTransitionInfo, mIsForward,
                mProfilerInfo, client, mAssistToken, mShareableActivityToken, mLaunchedFromBubble,
                mTaskFragmentToken, mInitialCallerInfoAccessToken, mActivityWindowInfo);
        client.handleLaunchActivity(r, pendingActions, mDeviceId, null /* customIntent */);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        client.countLaunchingActivities(-1);
    }

    @Nullable
    @Override
    public Context getContextToUpdate(@NonNull ClientTransactionHandler client) {
        // LaunchActivityItem may update the global config with #mCurConfig.
        return ActivityThread.currentApplication();
    }

    // ObjectPoolItem implementation

    private LaunchActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static LaunchActivityItem obtain(@NonNull IBinder activityToken, @NonNull Intent intent,
            int ident, @NonNull ActivityInfo info, @NonNull Configuration curConfig,
            @NonNull Configuration overrideConfig, int deviceId, @Nullable String referrer,
            @Nullable IVoiceInteractor voiceInteractor, int procState, @Nullable Bundle state,
            @Nullable PersistableBundle persistentState, @Nullable List<ResultInfo> pendingResults,
            @Nullable List<ReferrerIntent> pendingNewIntents,
            @Nullable SceneTransitionInfo sceneTransitionInfo,
            boolean isForward, @Nullable ProfilerInfo profilerInfo, @NonNull IBinder assistToken,
            @Nullable IActivityClientController activityClientController,
            @NonNull IBinder shareableActivityToken, boolean launchedFromBubble,
            @Nullable IBinder taskFragmentToken, @NonNull IBinder initialCallerInfoAccessToken,
            @NonNull ActivityWindowInfo activityWindowInfo) {
        LaunchActivityItem instance = ObjectPool.obtain(LaunchActivityItem.class);
        if (instance == null) {
            instance = new LaunchActivityItem();
        }
        setValues(instance, activityToken, new Intent(intent), ident,  new ActivityInfo(info),
                new Configuration(curConfig), new Configuration(overrideConfig), deviceId,
                referrer, voiceInteractor, procState,
                state != null ? new Bundle(state) : null,
                persistentState != null ? new PersistableBundle(persistentState) : null,
                pendingResults != null ? new ArrayList<>(pendingResults) : null,
                pendingNewIntents != null ? new ArrayList<>(pendingNewIntents) : null,
                sceneTransitionInfo, isForward,
                profilerInfo != null ? new ProfilerInfo(profilerInfo) : null,
                assistToken, activityClientController, shareableActivityToken,
                launchedFromBubble, taskFragmentToken, initialCallerInfoAccessToken,
                new ActivityWindowInfo(activityWindowInfo));

        return instance;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    @Override
    public IBinder getActivityToken() {
        return mActivityToken;
    }

    @Override
    public void recycle() {
        setValues(this, null, null, 0, null, null, null, 0, null, null, 0, null, null, null, null,
                null, false, null, null, null, null, false, null, null, null);
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write from Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mActivityToken);
        dest.writeTypedObject(mIntent, flags);
        dest.writeInt(mIdent);
        dest.writeTypedObject(mInfo, flags);
        dest.writeTypedObject(mCurConfig, flags);
        dest.writeTypedObject(mOverrideConfig, flags);
        dest.writeInt(mDeviceId);
        dest.writeString(mReferrer);
        dest.writeStrongInterface(mVoiceInteractor);
        dest.writeInt(mProcState);
        dest.writeBundle(mState);
        dest.writePersistableBundle(mPersistentState);
        dest.writeTypedList(mPendingResults, flags);
        dest.writeTypedList(mPendingNewIntents, flags);
        dest.writeTypedObject(mSceneTransitionInfo, flags);
        dest.writeBoolean(mIsForward);
        dest.writeTypedObject(mProfilerInfo, flags);
        dest.writeStrongBinder(mAssistToken);
        dest.writeStrongInterface(mActivityClientController);
        dest.writeStrongBinder(mShareableActivityToken);
        dest.writeBoolean(mLaunchedFromBubble);
        dest.writeStrongBinder(mTaskFragmentToken);
        dest.writeStrongBinder(mInitialCallerInfoAccessToken);
        dest.writeTypedObject(mActivityWindowInfo, flags);
    }

    /** Read from Parcel. */
    private LaunchActivityItem(@NonNull Parcel in) {
        setValues(this, in.readStrongBinder(), in.readTypedObject(Intent.CREATOR), in.readInt(),
                in.readTypedObject(ActivityInfo.CREATOR), in.readTypedObject(Configuration.CREATOR),
                in.readTypedObject(Configuration.CREATOR), in.readInt(), in.readString(),
                IVoiceInteractor.Stub.asInterface(in.readStrongBinder()), in.readInt(),
                in.readBundle(getClass().getClassLoader()),
                in.readPersistableBundle(getClass().getClassLoader()),
                in.createTypedArrayList(ResultInfo.CREATOR),
                in.createTypedArrayList(ReferrerIntent.CREATOR),
                in.readTypedObject(SceneTransitionInfo.CREATOR),
                in.readBoolean(),
                in.readTypedObject(ProfilerInfo.CREATOR),
                in.readStrongBinder(),
                IActivityClientController.Stub.asInterface(in.readStrongBinder()),
                in.readStrongBinder(),
                in.readBoolean(),
                in.readStrongBinder(),
                in.readStrongBinder(),
                in.readTypedObject(ActivityWindowInfo.CREATOR));
    }

    public static final @NonNull Creator<LaunchActivityItem> CREATOR = new Creator<>() {
        public LaunchActivityItem createFromParcel(@NonNull Parcel in) {
            return new LaunchActivityItem(in);
        }

        public LaunchActivityItem[] newArray(int size) {
            return new LaunchActivityItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LaunchActivityItem other = (LaunchActivityItem) o;
        final boolean intentsEqual = (mIntent == null && other.mIntent == null)
                || (mIntent != null && mIntent.filterEquals(other.mIntent));
        return intentsEqual
                && Objects.equals(mActivityToken, other.mActivityToken) && mIdent == other.mIdent
                && activityInfoEqual(other.mInfo) && Objects.equals(mCurConfig, other.mCurConfig)
                && Objects.equals(mOverrideConfig, other.mOverrideConfig)
                && mDeviceId == other.mDeviceId
                && Objects.equals(mReferrer, other.mReferrer)
                && mProcState == other.mProcState && areBundlesEqualRoughly(mState, other.mState)
                && areBundlesEqualRoughly(mPersistentState, other.mPersistentState)
                && Objects.equals(mPendingResults, other.mPendingResults)
                && Objects.equals(mPendingNewIntents, other.mPendingNewIntents)
                && (mSceneTransitionInfo == null) == (other.mSceneTransitionInfo == null)
                && mIsForward == other.mIsForward
                && Objects.equals(mProfilerInfo, other.mProfilerInfo)
                && Objects.equals(mAssistToken, other.mAssistToken)
                && Objects.equals(mShareableActivityToken, other.mShareableActivityToken)
                && Objects.equals(mTaskFragmentToken, other.mTaskFragmentToken)
                && Objects.equals(mInitialCallerInfoAccessToken,
                        other.mInitialCallerInfoAccessToken)
                && Objects.equals(mActivityWindowInfo, other.mActivityWindowInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mActivityToken);
        result = 31 * result + mIntent.filterHashCode();
        result = 31 * result + mIdent;
        result = 31 * result + Objects.hashCode(mCurConfig);
        result = 31 * result + Objects.hashCode(mOverrideConfig);
        result = 31 * result + mDeviceId;
        result = 31 * result + Objects.hashCode(mReferrer);
        result = 31 * result + Objects.hashCode(mProcState);
        result = 31 * result + getRoughBundleHashCode(mState);
        result = 31 * result + getRoughBundleHashCode(mPersistentState);
        result = 31 * result + Objects.hashCode(mPendingResults);
        result = 31 * result + Objects.hashCode(mPendingNewIntents);
        result = 31 * result + (mSceneTransitionInfo != null ? 1 : 0);
        result = 31 * result + (mIsForward ? 1 : 0);
        result = 31 * result + Objects.hashCode(mProfilerInfo);
        result = 31 * result + Objects.hashCode(mAssistToken);
        result = 31 * result + Objects.hashCode(mShareableActivityToken);
        result = 31 * result + Objects.hashCode(mTaskFragmentToken);
        result = 31 * result + Objects.hashCode(mInitialCallerInfoAccessToken);
        result = 31 * result + Objects.hashCode(mActivityWindowInfo);
        return result;
    }

    private boolean activityInfoEqual(@Nullable ActivityInfo other) {
        if (mInfo == null) {
            return other == null;
        }
        return other != null && mInfo.flags == other.flags
                && mInfo.getMaxAspectRatio() == other.getMaxAspectRatio()
                && Objects.equals(mInfo.launchToken, other.launchToken)
                && Objects.equals(mInfo.getComponentName(), other.getComponentName());
    }

    /**
     * This method may be used to compare a parceled item with another unparceled item, and the
     * parceled bundle may contain customized class that will raise BadParcelableException when
     * unparceling if a customized class loader is not set to the bundle. So the hash code is
     * simply determined by the bundle is empty or not.
     */
    private static int getRoughBundleHashCode(@Nullable BaseBundle bundle) {
        return (bundle == null || bundle.isDefinitelyEmpty()) ? 0 : 1;
    }

    /** Compares the bundles without unparceling them (avoid BadParcelableException). */
    private static boolean areBundlesEqualRoughly(@Nullable BaseBundle a, @Nullable BaseBundle b) {
        return getRoughBundleHashCode(a) == getRoughBundleHashCode(b);
    }

    @Override
    public String toString() {
        return "LaunchActivityItem{activityToken=" + mActivityToken
                + ",intent=" + mIntent
                + ",ident=" + mIdent
                + ",info=" + mInfo
                + ",curConfig=" + mCurConfig
                + ",overrideConfig=" + mOverrideConfig
                + ",deviceId=" + mDeviceId
                + ",referrer=" + mReferrer
                + ",procState=" + mProcState
                + ",state=" + mState
                + ",persistentState=" + mPersistentState
                + ",pendingResults=" + mPendingResults
                + ",pendingNewIntents=" + mPendingNewIntents
                + ",sceneTransitionInfo=" + mSceneTransitionInfo
                + ",profilerInfo=" + mProfilerInfo
                + ",assistToken=" + mAssistToken
                + ",shareableActivityToken=" + mShareableActivityToken
                + ",activityWindowInfo=" + mActivityWindowInfo
                + "}";
    }

    // Using the same method to set and clear values to make sure we don't forget anything
    private static void setValues(@Nullable LaunchActivityItem instance,
            @Nullable IBinder activityToken, @Nullable Intent intent, int ident,
            @Nullable ActivityInfo info, @Nullable Configuration curConfig,
            @Nullable Configuration overrideConfig, int deviceId,
            @Nullable String referrer, @Nullable IVoiceInteractor voiceInteractor,
            int procState, @Nullable Bundle state, @Nullable PersistableBundle persistentState,
            @Nullable List<ResultInfo> pendingResults,
            @Nullable List<ReferrerIntent> pendingNewIntents,
            @Nullable SceneTransitionInfo sceneTransitionInfo, boolean isForward,
            @Nullable ProfilerInfo profilerInfo, @Nullable IBinder assistToken,
            @Nullable IActivityClientController activityClientController,
            @Nullable IBinder shareableActivityToken, boolean launchedFromBubble,
            @Nullable IBinder taskFragmentToken, @Nullable IBinder initialCallerInfoAccessToken,
            @Nullable ActivityWindowInfo activityWindowInfo) {
        instance.mActivityToken = activityToken;
        instance.mIntent = intent;
        instance.mIdent = ident;
        instance.mInfo = info;
        instance.mCurConfig = curConfig;
        instance.mOverrideConfig = overrideConfig;
        instance.mDeviceId = deviceId;
        instance.mReferrer = referrer;
        instance.mVoiceInteractor = voiceInteractor;
        instance.mProcState = procState;
        instance.mState = state;
        instance.mPersistentState = persistentState;
        instance.mPendingResults = pendingResults;
        instance.mPendingNewIntents = pendingNewIntents;
        instance.mSceneTransitionInfo = sceneTransitionInfo;
        instance.mIsForward = isForward;
        instance.mProfilerInfo = profilerInfo;
        instance.mAssistToken = assistToken;
        instance.mActivityClientController = activityClientController;
        instance.mShareableActivityToken = shareableActivityToken;
        instance.mLaunchedFromBubble = launchedFromBubble;
        instance.mTaskFragmentToken = taskFragmentToken;
        instance.mInitialCallerInfoAccessToken = initialCallerInfoAccessToken;
        instance.mActivityWindowInfo = activityWindowInfo;
    }
}
