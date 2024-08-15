/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.spy;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ZenDurationDialogTest {
    private ZenDurationDialog mController;

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private ContentResolver mContentResolver;
    private AlertDialog.Builder mBuilder;

    @Before
    public void setup() {
        mContext = RuntimeEnvironment.application;
        mContentResolver = RuntimeEnvironment.application.getContentResolver();
        mLayoutInflater = LayoutInflater.from(mContext);

        mController = spy(new ZenDurationDialog(mContext));
        mController.mLayoutInflater = mLayoutInflater;
        mController.getContentView();
        mBuilder = new AlertDialog.Builder(mContext);
    }

    @Test
    public void testAlwaysPrompt() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Global.ZEN_DURATION_PROMPT);
        mController.setupDialog(mBuilder);

        assertFalse(mController.getConditionTagAt(ZenDurationDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(ZenDurationDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertTrue(mController.getConditionTagAt(
                ZenDurationDialog.ALWAYS_ASK_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testForever() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_FOREVER);
        mController.setupDialog(mBuilder);

        assertTrue(mController.getConditionTagAt(ZenDurationDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(ZenDurationDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(
                ZenDurationDialog.ALWAYS_ASK_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testSpecificDuration() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION, 45);
        mController.setupDialog(mBuilder);

        assertFalse(mController.getConditionTagAt(ZenDurationDialog.FOREVER_CONDITION_INDEX).rb
                .isChecked());
        assertTrue(mController.getConditionTagAt(ZenDurationDialog.COUNTDOWN_CONDITION_INDEX).rb
                .isChecked());
        assertFalse(mController.getConditionTagAt(
                ZenDurationDialog.ALWAYS_ASK_CONDITION_INDEX).rb.isChecked());
    }

    @Test
    public void testChooseAlwaysPromptSetting() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_FOREVER);

        mController.setupDialog(mBuilder);
        mController.getConditionTagAt(ZenDurationDialog.ALWAYS_ASK_CONDITION_INDEX).rb.setChecked(
                true);
        mController.updateZenDuration(Settings.Secure.ZEN_DURATION_FOREVER);

        assertEquals(Settings.Secure.ZEN_DURATION_PROMPT, Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ZEN_DURATION, Settings.Secure.ZEN_DURATION_FOREVER));
    }

    @Test
    public void testChooseForeverSetting() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_PROMPT);

        mController.setupDialog(mBuilder);
        mController.getConditionTagAt(ZenDurationDialog.FOREVER_CONDITION_INDEX).rb.setChecked(
                true);
        mController.updateZenDuration(Settings.Secure.ZEN_DURATION_PROMPT);

        assertEquals(Settings.Secure.ZEN_DURATION_FOREVER, Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ZEN_DURATION, Settings.Secure.ZEN_DURATION_PROMPT));
    }

    @Test
    public void testChooseTimeSetting() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_PROMPT);

        mController.setupDialog(mBuilder);
        mController.getConditionTagAt(ZenDurationDialog.COUNTDOWN_CONDITION_INDEX).rb.setChecked(
                true);
        mController.updateZenDuration(Settings.Secure.ZEN_DURATION_PROMPT);

        // countdown defaults to 60 minutes:
        assertEquals(60, Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ZEN_DURATION, Settings.Secure.ZEN_DURATION_PROMPT));
    }

    @Test
    public void testGetTimeFromBucket() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_PROMPT);

        mController.setupDialog(mBuilder);
        // click time button starts at 60 minutes
        // - 1 hour to MAX_BUCKET_MINUTES (12 hours), increments by 1 hour
        // - 0-60 minutes increments by 15 minutes
        View view = mController.mZenRadioGroupContent.getChildAt(
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        ZenDurationDialog.ConditionTag tag = mController.getConditionTagAt(
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);

        // test incrementing up:
        mController.onClickTimeButton(view, tag, true, ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(120, tag.countdownZenDuration); // goes from 1 hour to 2 hours

        // try clicking up 50 times - should max out at ZenDurationDialog.MAX_BUCKET_MINUTES
        for (int i = 0; i < 50; i++) {
            mController.onClickTimeButton(view, tag, true,
                    ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        }
        assertEquals(ZenDurationDialog.MAX_BUCKET_MINUTES, tag.countdownZenDuration);

        // reset, test incrementing down:
        mController.mBucketIndex = -1; // reset current bucket index to reset countdownZenDuration
        tag.countdownZenDuration = 60; // back to default
        mController.onClickTimeButton(view, tag, false,
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(45, tag.countdownZenDuration); // goes from 60 minutes to 45 minutes

        // try clicking down 50 times - should stop at MIN_BUCKET_MINUTES
        for (int i = 0; i < 50; i++) {
            mController.onClickTimeButton(view, tag, false,
                    ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        }
        assertEquals(ZenDurationDialog.MIN_BUCKET_MINUTES, tag.countdownZenDuration);

        // reset countdownZenDuration to unbucketed number, should round change to nearest bucket
        mController.mBucketIndex = -1;
        tag.countdownZenDuration = 50;
        mController.onClickTimeButton(view, tag, false,
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(45, tag.countdownZenDuration);

        mController.mBucketIndex = -1;
        tag.countdownZenDuration = 50;
        mController.onClickTimeButton(view, tag, true,
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(60, tag.countdownZenDuration);

        mController.mBucketIndex = -1;
        tag.countdownZenDuration = 75;
        mController.onClickTimeButton(view, tag, false,
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(60, tag.countdownZenDuration);

        mController.mBucketIndex = -1;
        tag.countdownZenDuration = 75;
        mController.onClickTimeButton(view, tag, true,
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        assertEquals(120, tag.countdownZenDuration);
    }

    @Test
    public void testAccessibility() {
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_DURATION,
                Settings.Secure.ZEN_DURATION_FOREVER);
        mController.setupDialog(mBuilder);
        ZenDurationDialog.ConditionTag forever = mController.getConditionTagAt(
                ZenDurationDialog.FOREVER_CONDITION_INDEX);
        ZenDurationDialog.ConditionTag countdown = mController.getConditionTagAt(
                ZenDurationDialog.COUNTDOWN_CONDITION_INDEX);
        ZenDurationDialog.ConditionTag alwaysAsk = mController.getConditionTagAt(
                ZenDurationDialog.ALWAYS_ASK_CONDITION_INDEX);

        forever.rb.setChecked(true);
        assertThat(forever.line1.getStateDescription().toString()).isEqualTo("selected");
        assertThat(countdown.line1.getStateDescription()).isNull();
        assertThat(alwaysAsk.line1.getStateDescription()).isNull();

        alwaysAsk.rb.setChecked(true);
        assertThat(forever.line1.getStateDescription()).isNull();
        assertThat(countdown.line1.getStateDescription()).isNull();
        assertThat(alwaysAsk.line1.getStateDescription().toString()).isEqualTo("selected");
    }
}
