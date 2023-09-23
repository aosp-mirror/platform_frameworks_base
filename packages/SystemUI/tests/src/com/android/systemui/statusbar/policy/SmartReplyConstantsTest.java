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

package com.android.systemui.statusbar.policy;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.app.RemoteInput;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.res.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.DeviceConfigProxyFake;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SmartReplyConstantsTest extends SysuiTestCase {
    private SmartReplyConstants mConstants;
    private DeviceConfigProxyFake mDeviceConfig;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        mDeviceConfig = new DeviceConfigProxyFake();
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.bool.config_smart_replies_in_notifications_enabled, true);
        resources.addOverride(
                R.integer.config_smart_replies_in_notifications_max_squeeze_remeasure_attempts, 7);
        resources.addOverride(
                R.bool.config_smart_replies_in_notifications_edit_choices_before_sending, false);
        resources.addOverride(R.bool.config_smart_replies_in_notifications_show_in_heads_up, true);
        resources.addOverride(
                R.integer.config_smart_replies_in_notifications_min_num_system_generated_replies,
                2);
        resources.addOverride(
                R.integer.config_smart_replies_in_notifications_max_num_actions, -1);
        mTestableLooper = TestableLooper.get(this);
        mConstants = new SmartReplyConstants(
                new Handler(mTestableLooper.getLooper()),
                mContext,
                mDeviceConfig
        );
    }

    @Test
    public void testIsEnabledWithNoConfig() {
        assertTrue(mConstants.isEnabled());
    }

    @Test
    public void testIsEnabledWithInvalidConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_ENABLED, "invalid config");
        assertTrue(mConstants.isEnabled());
    }

    @Test
    public void testIsEnabledWithValidConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_ENABLED, "false");
        assertFalse(mConstants.isEnabled());
    }

    @Test
    public void testRequiresTargetingPConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_REQUIRES_TARGETING_P, "false");
        assertEquals(false, mConstants.requiresTargetingP());

        overrideSetting(SystemUiDeviceConfigFlags.SSIN_REQUIRES_TARGETING_P, null);
        assertEquals(true, mConstants.requiresTargetingP());
    }

    @Test
    public void testGetMaxSqueezeRemeasureAttemptsWithNoConfig() {
        assertTrue(mConstants.isEnabled());
        assertEquals(7, mConstants.getMaxSqueezeRemeasureAttempts());
    }

    @Test
    public void testGetMaxSqueezeRemeasureAttemptsWithInvalidConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_MAX_SQUEEZE_REMEASURE_ATTEMPTS,
                "invalid config");
        assertEquals(7, mConstants.getMaxSqueezeRemeasureAttempts());
    }

    @Test
    public void testGetMaxSqueezeRemeasureAttemptsWithValidConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_MAX_SQUEEZE_REMEASURE_ATTEMPTS, "5");
        assertEquals(5, mConstants.getMaxSqueezeRemeasureAttempts());
    }

    @Test
    public void testGetEffectiveEditChoicesBeforeSendingWithNoConfig() {
        assertFalse(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_AUTO));
        assertTrue(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED));
        assertFalse(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED));
    }

    @Test
    public void testGetEffectiveEditChoicesBeforeSendingWithEnabledConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_EDIT_CHOICES_BEFORE_SENDING, "true");
        assertTrue(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_AUTO));
        assertTrue(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED));
        assertFalse(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED));
    }

    @Test
    public void testGetEffectiveEditChoicesBeforeSendingWithDisabledConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_EDIT_CHOICES_BEFORE_SENDING, "false");
        assertFalse(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_AUTO));
        assertTrue(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_ENABLED));
        assertFalse(
                mConstants.getEffectiveEditChoicesBeforeSending(
                        RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED));
    }

    @Test
    public void testShowInHeadsUpWithNoConfig() {
        assertTrue(mConstants.isEnabled());
        assertTrue(mConstants.getShowInHeadsUp());
    }

    @Test
    public void testShowInHeadsUpEnabled() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_SHOW_IN_HEADS_UP, "true");
        assertTrue(mConstants.getShowInHeadsUp());
    }

    @Test
    public void testShowInHeadsUpDisabled() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_SHOW_IN_HEADS_UP, "false");
        assertFalse(mConstants.getShowInHeadsUp());
    }

    @Test
    public void testGetMinNumSystemGeneratedRepliesWithNoConfig() {
        assertTrue(mConstants.isEnabled());
        assertEquals(2, mConstants.getMinNumSystemGeneratedReplies());
    }

    @Test
    public void testGetMinNumSystemGeneratedRepliesWithValidConfig() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_MIN_NUM_SYSTEM_GENERATED_REPLIES, "5");
        assertEquals(5, mConstants.getMinNumSystemGeneratedReplies());
    }

    @Test
    public void testMaxNumActionsWithNoConfig() {
        assertTrue(mConstants.isEnabled());
        assertEquals(-1, mConstants.getMaxNumActions());
    }

    @Test
    public void testMaxNumActionsSet() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_MAX_NUM_ACTIONS, "10");
        assertEquals(10, mConstants.getMaxNumActions());
    }

    @Test
    public void testOnClickInitDelayWithNoConfig() {
        assertEquals(200, mConstants.getOnClickInitDelay());
    }

    @Test
    public void testOnClickInitDelaySet() {
        overrideSetting(SystemUiDeviceConfigFlags.SSIN_ONCLICK_INIT_DELAY, "50");
        assertEquals(50, mConstants.getOnClickInitDelay());
    }

    private void overrideSetting(String propertyName, String value) {
        mDeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                propertyName, value, false /* makeDefault */);
        mTestableLooper.processAllMessages();
    }
}
