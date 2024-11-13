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

package com.android.server.notification;

import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.net.Uri;
import android.os.IInterface;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.Condition;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ConditionProvidersTest extends UiServiceTestCase {

    private ConditionProviders mProviders;

    @Mock
    private IPackageManager mIpm;
    @Mock
    private ManagedServices.UserProfiles mUserProfiles;
    @Mock
    private ConditionProviders.Callback mCallback;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(
            SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.ensureTestableResources();

        mProviders = new ConditionProviders(mContext, mUserProfiles, mIpm);
        mProviders.setCallback(mCallback);
    }

    @Test
    public void notifyConditions_findCondition() {
        ComponentName cn = new ComponentName("package", "cls");
        ManagedServices.ManagedServiceInfo msi = mProviders.new ManagedServiceInfo(
                mock(IInterface.class), cn, 0, false, mock(ServiceConnection.class), 33, 100);
        Condition[] conditions = new Condition[] {
                new Condition(Uri.parse("a"), "summary", STATE_TRUE),
                new Condition(Uri.parse("b"), "summary2", STATE_TRUE)
        };

        mProviders.notifyConditions("package", msi, conditions);

        assertThat(mProviders.findCondition(cn, Uri.parse("a"))).isEqualTo(conditions[0]);
        assertThat(mProviders.findCondition(cn, Uri.parse("b"))).isEqualTo(conditions[1]);
        assertThat(mProviders.findCondition(null, Uri.parse("a"))).isNull();
        assertThat(mProviders.findCondition(cn, null)).isNull();
    }

    @Test
    public void notifyConditions_callbackOnConditionChanged() {
        ManagedServices.ManagedServiceInfo msi = mProviders.new ManagedServiceInfo(
                mock(IInterface.class), new ComponentName("package", "cls"), 0, false,
                mock(ServiceConnection.class), 33, 100);
        Condition[] conditionsToNotify = new Condition[] {
                new Condition(Uri.parse("a"), "summary", STATE_TRUE),
                new Condition(Uri.parse("b"), "summary2", STATE_TRUE),
                new Condition(Uri.parse("c"), "summary3", STATE_TRUE)
        };

        mProviders.notifyConditions("package", msi, conditionsToNotify);

        verify(mCallback).onConditionChanged(eq(Uri.parse("a")), eq(conditionsToNotify[0]));
        verify(mCallback).onConditionChanged(eq(Uri.parse("b")), eq(conditionsToNotify[1]));
        verify(mCallback).onConditionChanged(eq(Uri.parse("c")), eq(conditionsToNotify[2]));
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void notifyConditions_duplicateIds_ignored() {
        ManagedServices.ManagedServiceInfo msi = mProviders.new ManagedServiceInfo(
                mock(IInterface.class), new ComponentName("package", "cls"), 0, false,
                mock(ServiceConnection.class), 33, 100);
        Condition[] conditionsToNotify = new Condition[] {
                new Condition(Uri.parse("a"), "summary", STATE_TRUE),
                new Condition(Uri.parse("b"), "summary2", STATE_TRUE),
                new Condition(Uri.parse("a"), "summary3", STATE_FALSE),
                new Condition(Uri.parse("a"), "summary4", STATE_FALSE)
        };

        mProviders.notifyConditions("package", msi, conditionsToNotify);

        verify(mCallback).onConditionChanged(eq(Uri.parse("a")), eq(conditionsToNotify[0]));
        verify(mCallback).onConditionChanged(eq(Uri.parse("b")), eq(conditionsToNotify[1]));

        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void notifyConditions_nullItems_ignored() {
        ManagedServices.ManagedServiceInfo msi = mProviders.new ManagedServiceInfo(
                mock(IInterface.class), new ComponentName("package", "cls"), 0, false,
                mock(ServiceConnection.class), 33, 100);
        Condition[] conditionsToNotify = new Condition[] {
                new Condition(Uri.parse("a"), "summary", STATE_TRUE),
                null,
                null,
                new Condition(Uri.parse("b"), "summary", STATE_TRUE)
        };

        mProviders.notifyConditions("package", msi, conditionsToNotify);

        verify(mCallback).onConditionChanged(eq(Uri.parse("a")), eq(conditionsToNotify[0]));
        verify(mCallback).onConditionChanged(eq(Uri.parse("b")), eq(conditionsToNotify[3]));
        verifyNoMoreInteractions(mCallback);
    }

    @Test
    public void testRemoveDefaultFromConfig() {
        final int userId = 0;
        ComponentName oldDefaultComponent = ComponentName.unflattenFromString("package/Component1");

        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultDndDeniedPackages))
                .thenReturn("package");
        mProviders.setPackageOrComponentEnabled(oldDefaultComponent.flattenToString(),
                userId, true, true /*enabled*/, false /*userSet*/);
        assertEquals("package", mProviders.getApproved(userId, true));

        mProviders.removeDefaultFromConfig(userId);

        assertTrue(mProviders.getApproved(userId, true).isEmpty());
    }
}
