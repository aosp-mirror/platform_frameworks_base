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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.util.MergedConfiguration;
import android.window.ActivityWindowInfo;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;

class TestUtils {

    static Configuration config() {
        Configuration config = new Configuration();
        config.densityDpi = 10;
        config.fontScale = 0.3f;
        config.screenHeightDp = 15;
        config.orientation = ORIENTATION_LANDSCAPE;
        return config;
    }

    static MergedConfiguration mergedConfig() {
        Configuration config = config();
        Configuration overrideConfig = new Configuration();
        overrideConfig.densityDpi = 30;
        overrideConfig.screenWidthDp = 40;
        overrideConfig.smallestScreenWidthDp = 15;
        return new MergedConfiguration(config, overrideConfig);
    }

    static List<ResultInfo> resultInfoList() {
        String resultWho1 = "resultWho1";
        int requestCode1 = 7;
        int resultCode1 = 4;
        Intent data1 = new Intent("action1");
        ResultInfo resultInfo1 = new ResultInfo(resultWho1, requestCode1, resultCode1, data1);

        String resultWho2 = "resultWho2";
        int requestCode2 = 8;
        int resultCode2 = 6;
        Intent data2 = new Intent("action2");
        ResultInfo resultInfo2 = new ResultInfo(resultWho2, requestCode2, resultCode2, data2);

        List<ResultInfo> resultInfoList = new ArrayList<>();
        resultInfoList.add(resultInfo1);
        resultInfoList.add(resultInfo2);

        return resultInfoList;
    }

    static List<ReferrerIntent> referrerIntentList() {
        Intent intent1 = new Intent("action1");
        ReferrerIntent referrerIntent1 = new ReferrerIntent(intent1, "referrer1");

        Intent intent2 = new Intent("action2");
        ReferrerIntent referrerIntent2 = new ReferrerIntent(intent2, "referrer2");

        List<ReferrerIntent> referrerIntents = new ArrayList<>();
        referrerIntents.add(referrerIntent1);
        referrerIntents.add(referrerIntent2);

        return referrerIntents;
    }

    static class LaunchActivityItemBuilder {
        @NonNull
        private final IBinder mActivityToken;
        @NonNull
        private final Intent mIntent;
        @NonNull
        private final ActivityInfo mInfo;
        @NonNull
        private final Configuration mCurConfig = new Configuration();
        @NonNull
        private final Configuration mOverrideConfig = new Configuration();

        private int mIdent;
        private int mDeviceId;
        @Nullable
        private String mReferrer;
        @Nullable
        private IVoiceInteractor mVoiceInteractor;
        private int mProcState;
        @Nullable
        private Bundle mState;
        @Nullable
        private PersistableBundle mPersistentState;
        @Nullable
        private List<ResultInfo> mPendingResults;
        @Nullable
        private List<ReferrerIntent> mPendingNewIntents;
        @Nullable
        private ActivityOptions mActivityOptions;
        private boolean mIsForward;
        @Nullable
        private ProfilerInfo mProfilerInfo;
        @Nullable
        private IBinder mAssistToken;
        @Nullable
        private IBinder mShareableActivityToken;
        private boolean mLaunchedFromBubble;
        @Nullable
        private IBinder mTaskFragmentToken;
        @Nullable
        private IBinder mInitialCallerInfoAccessToken;
        @NonNull
        private ActivityWindowInfo mActivityWindowInfo = new ActivityWindowInfo();

        LaunchActivityItemBuilder(@NonNull IBinder activityToken, @NonNull Intent intent,
                @NonNull ActivityInfo info) {
            mActivityToken = requireNonNull(activityToken);
            mIntent = requireNonNull(intent);
            mInfo = requireNonNull(info);
        }

        @NonNull
        LaunchActivityItemBuilder setIdent(int ident) {
            mIdent = ident;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setCurConfig(@NonNull Configuration curConfig) {
            mCurConfig.setTo(curConfig);
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setOverrideConfig(@NonNull Configuration overrideConfig) {
            mOverrideConfig.setTo(overrideConfig);
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setDeviceId(int deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setReferrer(@Nullable String referrer) {
            mReferrer = referrer;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setVoiceInteractor(@Nullable IVoiceInteractor voiceInteractor) {
            mVoiceInteractor = voiceInteractor;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setProcState(int procState) {
            mProcState = procState;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setState(@Nullable Bundle state) {
            mState = state;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setPersistentState(@Nullable PersistableBundle persistentState) {
            mPersistentState = persistentState;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setPendingResults(@Nullable List<ResultInfo> pendingResults) {
            mPendingResults = pendingResults;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setPendingNewIntents(
                @Nullable List<ReferrerIntent> pendingNewIntents) {
            mPendingNewIntents = pendingNewIntents;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setActivityOptions(@Nullable ActivityOptions activityOptions) {
            mActivityOptions = activityOptions;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setIsForward(boolean isForward) {
            mIsForward = isForward;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setProfilerInfo(@Nullable ProfilerInfo profilerInfo) {
            mProfilerInfo = profilerInfo;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setAssistToken(@Nullable IBinder assistToken) {
            mAssistToken = assistToken;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setShareableActivityToken(
                @Nullable IBinder shareableActivityToken) {
            mShareableActivityToken = shareableActivityToken;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setLaunchedFromBubble(boolean launchedFromBubble) {
            mLaunchedFromBubble = launchedFromBubble;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setTaskFragmentToken(@Nullable IBinder taskFragmentToken) {
            mTaskFragmentToken = taskFragmentToken;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setInitialCallerInfoAccessToken(
                @Nullable IBinder initialCallerInfoAccessToken) {
            mInitialCallerInfoAccessToken = initialCallerInfoAccessToken;
            return this;
        }

        @NonNull
        LaunchActivityItemBuilder setActivityWindowInfo(
                @NonNull ActivityWindowInfo activityWindowInfo) {
            mActivityWindowInfo.set(activityWindowInfo);
            return this;
        }

        @NonNull
        LaunchActivityItem build() {
            return new LaunchActivityItem(mActivityToken, mIntent, mIdent, mInfo,
                    mCurConfig, mOverrideConfig, mDeviceId, mReferrer, mVoiceInteractor,
                    mProcState, mState, mPersistentState, mPendingResults, mPendingNewIntents,
                    mActivityOptions != null ? mActivityOptions.getSceneTransitionInfo() : null,
                    mIsForward, mProfilerInfo, mAssistToken, null /* activityClientController */,
                    mShareableActivityToken, mLaunchedFromBubble, mTaskFragmentToken,
                    mInitialCallerInfoAccessToken, mActivityWindowInfo);
        }
    }
}
