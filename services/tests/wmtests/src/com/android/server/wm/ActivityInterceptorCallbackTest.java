/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Test;

@Presubmit
@MediumTest
public class ActivityInterceptorCallbackTest {

    @Test
    public void testBuildActivityInterceptorCallback() {
        int callingUid = 10;
        int callingPid = 100;
        int realCallingUid = 20;
        int realCallingPid = 200;
        int userId = 1;
        Intent intent = new Intent();
        ResolveInfo resolveInfo = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();
        String resolveType = "resolveType";
        String callingPackage = "callingPackage";
        String callingFeatureId = "callingFeatureId";
        ActivityOptions activityOptions = ActivityOptions.makeBasic();
        Runnable clearOptionsAnimation = () -> {};

        ActivityInterceptorCallback.ActivityInterceptorInfo activityInterceptorInfo =
                new ActivityInterceptorCallback.ActivityInterceptorInfo.Builder(callingUid,
                        callingPid, realCallingUid, realCallingPid, userId, intent, resolveInfo,
                        activityInfo)
                .setResolvedType(resolveType)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setCheckedOptions(activityOptions)
                .setClearOptionsAnimationRunnable(clearOptionsAnimation)
                .build();

        assertThat(activityInterceptorInfo.getCallingUid()).isEqualTo(callingUid);
        assertThat(activityInterceptorInfo.getCallingPid()).isEqualTo(callingPid);
        assertThat(activityInterceptorInfo.getRealCallingUid()).isEqualTo(realCallingUid);
        assertThat(activityInterceptorInfo.getRealCallingPid()).isEqualTo(realCallingPid);
        assertThat(activityInterceptorInfo.getUserId()).isEqualTo(userId);
        assertThat(activityInterceptorInfo.getIntent()).isEqualTo(intent);
        assertThat(activityInterceptorInfo.getResolveInfo()).isEqualTo(resolveInfo);
        assertThat(activityInterceptorInfo.getActivityInfo()).isEqualTo(activityInfo);
        assertThat(activityInterceptorInfo.getResolvedType()).isEqualTo(resolveType);
        assertThat(activityInterceptorInfo.getCallingPackage()).isEqualTo(callingPackage);
        assertThat(activityInterceptorInfo.getCallingFeatureId()).isEqualTo(callingFeatureId);
        assertThat(activityInterceptorInfo.getCheckedOptions()).isEqualTo(activityOptions);
        assertThat(activityInterceptorInfo.getClearOptionsAnimationRunnable())
                .isEqualTo(clearOptionsAnimation);
    }

    @Test
    public void testActivityInterceptResult() {
        Intent intent = new Intent();
        ActivityOptions activityOptions = ActivityOptions.makeBasic();
        boolean isActivityResolved = true;

        ActivityInterceptorCallback.ActivityInterceptResult result =
                new ActivityInterceptorCallback.ActivityInterceptResult(intent, activityOptions);
        assertThat(result.getIntent()).isEqualTo(intent);
        assertThat(result.getActivityOptions()).isEqualTo(activityOptions);
        assertThat(result.isActivityResolved()).isFalse();

        result = new ActivityInterceptorCallback.ActivityInterceptResult(
                intent, activityOptions, isActivityResolved);
        assertThat(result.getIntent()).isEqualTo(intent);
        assertThat(result.getActivityOptions()).isEqualTo(activityOptions);
        assertThat(result.isActivityResolved()).isTrue();
    }
}
