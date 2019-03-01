/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.telephony.SubscriptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextController.CarrierTextCallbackInfo;
import com.android.systemui.R;
import com.android.systemui.R.id;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSFooterImplTest extends LeakCheckedTest {

    private QSFooterImpl mFooter;
    private ActivityStarter mActivityStarter;
    private DeviceProvisionedController mDeviceProvisionedController;

    @Before
    public void setup() throws Exception {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mActivityStarter = mDependency.injectMockDependency(ActivityStarter.class);
        mDeviceProvisionedController = mDependency.injectMockDependency(
                DeviceProvisionedController.class);
        TestableLooper.get(this).runWithLooper(
                () -> mFooter = (QSFooterImpl) LayoutInflater.from(mContext).inflate(
                        R.layout.qs_footer_impl, null));
    }

    @Test
    @Ignore("failing")
    public void testSettings_UserNotSetup() {
        View settingsButton = mFooter.findViewById(id.settings_button);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);

        mFooter.onClick(settingsButton);
        // Verify Settings wasn't launched.
        verify(mActivityStarter, never()).startActivity(any(), anyBoolean());
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_sameLengts() {
        QSFooterImpl spiedFooter = Mockito.spy(mFooter);
        when(spiedFooter.getSlotIndex(anyInt())).thenAnswer(
                new Answer<Integer>() {
                    @Override
                    public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
                        return invocationOnMock.getArgument(0);
                    }
                });

        // listOfCarriers length 1, subscriptionIds length 1, anySims false
        CarrierTextCallbackInfo c1 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0});
        spiedFooter.updateCarrierInfo(c1);

        // listOfCarriers length 1, subscriptionIds length 1, anySims true
        CarrierTextCallbackInfo c2 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0});
        spiedFooter.updateCarrierInfo(c2);

        // listOfCarriers length 2, subscriptionIds length 2, anySims false
        CarrierTextCallbackInfo c3 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0, 1});
        spiedFooter.updateCarrierInfo(c3);

        // listOfCarriers length 2, subscriptionIds length 2, anySims true
        CarrierTextCallbackInfo c4 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        spiedFooter.updateCarrierInfo(c4);
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_differentLength() {
        QSFooterImpl spiedFooter = Mockito.spy(mFooter);
        when(spiedFooter.getSlotIndex(anyInt())).thenAnswer(
                new Answer<Integer>() {
                    @Override
                    public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
                        return invocationOnMock.getArgument(0);
                    }
                });

        // listOfCarriers length 2, subscriptionIds length 1, anySims false
        CarrierTextCallbackInfo c1 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0});
        spiedFooter.updateCarrierInfo(c1);

        // listOfCarriers length 2, subscriptionIds length 1, anySims true
        CarrierTextCallbackInfo c2 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0});
        spiedFooter.updateCarrierInfo(c2);

        // listOfCarriers length 1, subscriptionIds length 2, anySims false
        CarrierTextCallbackInfo c3 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0, 1});
        spiedFooter.updateCarrierInfo(c3);

        // listOfCarriers length 1, subscriptionIds length 2, anySims true
        CarrierTextCallbackInfo c4 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0, 1});
        spiedFooter.updateCarrierInfo(c4);
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_invalidSim() {
        QSFooterImpl spiedFooter = Mockito.spy(mFooter);
        when(spiedFooter.getSlotIndex(anyInt())).thenReturn(
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        CarrierTextCallbackInfo c4 = new CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        spiedFooter.updateCarrierInfo(c4);
    }

    @Test // throws no Exception
    public void testSetMobileDataIndicators_invalidSim() {
        QSFooterImpl spiedFooter = Mockito.spy(mFooter);
        when(spiedFooter.getSlotIndex(anyInt())).thenReturn(
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        spiedFooter.setMobileDataIndicators(
                mock(NetworkController.IconState.class),
                mock(NetworkController.IconState.class),
                0, 0, true, true, "", "", true, 0, true);
    }

}
