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

import android.app.ActivityOptions;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.util.MergedConfiguration;
import android.view.DisplayAdjustments.FixedRotationAdjustments;

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
        private Intent mIntent;
        private int mIdent;
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
        private ActivityOptions mActivityOptions;
        private boolean mIsForward;
        private ProfilerInfo mProfilerInfo;
        private IBinder mAssistToken;
        private IBinder mShareableActivityToken;
        private FixedRotationAdjustments mFixedRotationAdjustments;
        private boolean mLaunchedFromBubble;

        LaunchActivityItemBuilder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        LaunchActivityItemBuilder setIdent(int ident) {
            mIdent = ident;
            return this;
        }

        LaunchActivityItemBuilder setInfo(ActivityInfo info) {
            mInfo = info;
            return this;
        }

        LaunchActivityItemBuilder setCurConfig(Configuration curConfig) {
            mCurConfig = curConfig;
            return this;
        }

        LaunchActivityItemBuilder setOverrideConfig(Configuration overrideConfig) {
            mOverrideConfig = overrideConfig;
            return this;
        }

        LaunchActivityItemBuilder setCompatInfo(CompatibilityInfo compatInfo) {
            mCompatInfo = compatInfo;
            return this;
        }

        LaunchActivityItemBuilder setReferrer(String referrer) {
            mReferrer = referrer;
            return this;
        }

        LaunchActivityItemBuilder setVoiceInteractor(IVoiceInteractor voiceInteractor) {
            mVoiceInteractor = voiceInteractor;
            return this;
        }

        LaunchActivityItemBuilder setProcState(int procState) {
            mProcState = procState;
            return this;
        }

        LaunchActivityItemBuilder setState(Bundle state) {
            mState = state;
            return this;
        }

        LaunchActivityItemBuilder setPersistentState(PersistableBundle persistentState) {
            mPersistentState = persistentState;
            return this;
        }

        LaunchActivityItemBuilder setPendingResults(List<ResultInfo> pendingResults) {
            mPendingResults = pendingResults;
            return this;
        }

        LaunchActivityItemBuilder setPendingNewIntents(List<ReferrerIntent> pendingNewIntents) {
            mPendingNewIntents = pendingNewIntents;
            return this;
        }

        LaunchActivityItemBuilder setActivityOptions(ActivityOptions activityOptions) {
            mActivityOptions = activityOptions;
            return this;
        }

        LaunchActivityItemBuilder setIsForward(boolean isForward) {
            mIsForward = isForward;
            return this;
        }

        LaunchActivityItemBuilder setProfilerInfo(ProfilerInfo profilerInfo) {
            mProfilerInfo = profilerInfo;
            return this;
        }

        LaunchActivityItemBuilder setAssistToken(IBinder assistToken) {
            mAssistToken = assistToken;
            return this;
        }

        LaunchActivityItemBuilder setShareableActivityToken(IBinder shareableActivityToken) {
            mShareableActivityToken = shareableActivityToken;
            return this;
        }

        LaunchActivityItemBuilder setFixedRotationAdjustments(FixedRotationAdjustments fra) {
            mFixedRotationAdjustments = fra;
            return this;
        }

        LaunchActivityItemBuilder setLaunchedFromBubble(boolean launchedFromBubble) {
            mLaunchedFromBubble = launchedFromBubble;
            return this;
        }

        LaunchActivityItem build() {
            return LaunchActivityItem.obtain(mIntent, mIdent, mInfo,
                    mCurConfig, mOverrideConfig, mCompatInfo, mReferrer, mVoiceInteractor,
                    mProcState, mState, mPersistentState, mPendingResults, mPendingNewIntents,
                    mActivityOptions, mIsForward, mProfilerInfo, mAssistToken,
                    null /* activityClientController */, mFixedRotationAdjustments,
                    mShareableActivityToken, mLaunchedFromBubble);
        }
    }
}
