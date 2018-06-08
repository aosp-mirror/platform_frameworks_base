/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.view.MenuItem;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Tests for {@link HelpUtils}.
 */
@RunWith(RobolectricTestRunner.class)
public class HelpUtilsTest {
    private static final String TEST_HELP_URL = "intent:#Intent;action=com.android.test;end";
    private static final String PACKAGE_NAME_KEY = "package-name-key";
    private static final String PACKAGE_NAME_VALUE = "package-name-value";
    private static final String HELP_INTENT_EXTRA_KEY = "help-intent-extra";
    private static final String HELP_INTENT_NAME_KEY = "help-intent-name";
    private static final String FEEDBACK_INTENT_EXTRA_KEY = "feedback-intent-extra";
    private static final String FEEDBACK_INTENT_NAME_KEY = "feedback-intent-name";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;
    @Mock
    private Activity mActivity;
    @Mock
    private PackageManager mPackageManager;


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources().getString(R.string.config_helpPackageNameKey))
                .thenReturn(PACKAGE_NAME_KEY);
        when(mContext.getResources().getString(R.string.config_helpPackageNameValue))
                .thenReturn(PACKAGE_NAME_VALUE);
        when(mContext.getResources().getString(R.string.config_helpIntentExtraKey))
                .thenReturn(HELP_INTENT_EXTRA_KEY);
        when(mContext.getResources().getString(R.string.config_helpIntentNameKey))
                .thenReturn(HELP_INTENT_NAME_KEY);
        when(mContext.getResources().getString(R.string.config_feedbackIntentExtraKey))
                .thenReturn(FEEDBACK_INTENT_EXTRA_KEY);
        when(mContext.getResources().getString(R.string.config_feedbackIntentNameKey))
                .thenReturn(FEEDBACK_INTENT_NAME_KEY);
        when(mActivity.getPackageManager()).thenReturn(mPackageManager);


    }

    @Test
    public void addIntentParameters_configTrue_argumentTrue() {
        when(mContext.getResources().getBoolean(R.bool.config_sendPackageName)).thenReturn(true);
        Intent intent = new Intent();

        HelpUtils.addIntentParameters(
                mContext, intent, null /* backupContext */, true /* sendPackageName */);

        assertThat(intent.getStringArrayExtra(HELP_INTENT_EXTRA_KEY)).asList()
                .containsExactly(PACKAGE_NAME_KEY);
        assertThat(intent.getStringArrayExtra(HELP_INTENT_NAME_KEY)).asList()
                .containsExactly(PACKAGE_NAME_VALUE);
        assertThat(intent.getStringArrayExtra(FEEDBACK_INTENT_EXTRA_KEY)).asList()
                .containsExactly(PACKAGE_NAME_KEY);
        assertThat(intent.getStringArrayExtra(FEEDBACK_INTENT_NAME_KEY)).asList()
                .containsExactly(PACKAGE_NAME_VALUE);
    }

    @Test
    public void addIntentParameters_configTrue_argumentFalse() {
        when(mContext.getResources().getBoolean(R.bool.config_sendPackageName)).thenReturn(true);
        Intent intent = new Intent();

        HelpUtils.addIntentParameters(
                mContext, intent, null /* backupContext */, false /* sendPackageName */);

        assertThat(intent.hasExtra(HELP_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(HELP_INTENT_NAME_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_NAME_KEY)).isFalse();
    }

    @Test
    public void addIntentParameters_configFalse_argumentTrue() {
        when(mContext.getResources().getBoolean(R.bool.config_sendPackageName)).thenReturn(false);
        Intent intent = new Intent();

        HelpUtils.addIntentParameters(
                mContext, intent, null /* backupContext */, true /* sendPackageName */);

        assertThat(intent.hasExtra(HELP_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(HELP_INTENT_NAME_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_NAME_KEY)).isFalse();
    }

    @Test
    public void addIntentParameters_configFalse_argumentFalse() {
        when(mContext.getResources().getBoolean(R.bool.config_sendPackageName)).thenReturn(false);
        Intent intent = new Intent();

        HelpUtils.addIntentParameters(
                mContext, intent, null /* backupContext */, false /* sendPackageName */);

        assertThat(intent.hasExtra(HELP_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(HELP_INTENT_NAME_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_EXTRA_KEY)).isFalse();
        assertThat(intent.hasExtra(FEEDBACK_INTENT_NAME_KEY)).isFalse();
    }

    @Test
    public void prepareHelpMenuItem_shouldShowIcon() {
        Settings.Global.putInt(RuntimeEnvironment.application.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 1);
        final Resources res = mock(Resources.class);
        final ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.activityInfo.applicationInfo.packageName = "pkg";
        resolveInfo.activityInfo.name = "name";
        final MenuItem item = mock(MenuItem.class);


        when(mActivity.getContentResolver())
                .thenReturn(RuntimeEnvironment.application.getContentResolver());
        when(mActivity.getResources()).thenReturn(res);
        when(mActivity.obtainStyledAttributes(any(int[].class)))
                .thenReturn(mock(TypedArray.class));
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(resolveInfo);

        HelpUtils.prepareHelpMenuItem(mActivity, item, TEST_HELP_URL, "backup_url");

        verify(item).setVisible(true);
        verify(item).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }
}