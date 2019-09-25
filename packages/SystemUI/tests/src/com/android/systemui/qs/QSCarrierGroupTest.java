/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.telephony.SubscriptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextController;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSCarrierGroupTest extends LeakCheckedTest {

    private QSCarrierGroup mCarrierGroup;
    private CarrierTextController.CarrierTextCallback mCallback;
    private TestableLooper mTestableLooper;

    @Before
    public void setup() throws Exception {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mTestableLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(
                Dependency.BG_HANDLER, new Handler(mTestableLooper.getLooper()));
        mDependency.injectTestDependency(Dependency.MAIN_LOOPER, mTestableLooper.getLooper());
        mTestableLooper.runWithLooper(
                () -> mCarrierGroup = (QSCarrierGroup) LayoutInflater.from(mContext).inflate(
                        R.layout.qs_carrier_group, null));
        mCallback = mCarrierGroup.getCallback();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_sameLengths() {
        QSCarrierGroup spiedCarrierGroup = Mockito.spy(mCarrierGroup);
        when(spiedCarrierGroup.getSlotIndex(anyInt())).thenAnswer(
                new Answer<Integer>() {
                    @Override
                    public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
                        return invocationOnMock.getArgument(0);
                    }
                });

        // listOfCarriers length 1, subscriptionIds length 1, anySims false
        CarrierTextController.CarrierTextCallbackInfo
                c1 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 1, subscriptionIds length 1, anySims true
        CarrierTextController.CarrierTextCallbackInfo
                c2 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 2, subscriptionIds length 2, anySims false
        CarrierTextController.CarrierTextCallbackInfo
                c3 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 2, subscriptionIds length 2, anySims true
        CarrierTextController.CarrierTextCallbackInfo
                c4 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);

        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_differentLength() {
        QSCarrierGroup spiedCarrierGroup = Mockito.spy(mCarrierGroup);
        when(spiedCarrierGroup.getSlotIndex(anyInt())).thenAnswer(
                new Answer<Integer>() {
                    @Override
                    public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
                        return invocationOnMock.getArgument(0);
                    }
                });

        // listOfCarriers length 2, subscriptionIds length 1, anySims false
        CarrierTextController.CarrierTextCallbackInfo
                c1 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 2, subscriptionIds length 1, anySims true
        CarrierTextController.CarrierTextCallbackInfo
                c2 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 1, subscriptionIds length 2, anySims false
        CarrierTextController.CarrierTextCallbackInfo
                c3 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 1, subscriptionIds length 2, anySims true
        CarrierTextController.CarrierTextCallbackInfo
                c4 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_invalidSim() {
        QSCarrierGroup spiedCarrierGroup = Mockito.spy(mCarrierGroup);
        when(spiedCarrierGroup.getSlotIndex(anyInt())).thenReturn(
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        CarrierTextController.CarrierTextCallbackInfo
                c4 = new CarrierTextController.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testSetMobileDataIndicators_invalidSim() {
        QSCarrierGroup spiedCarrierGroup = Mockito.spy(mCarrierGroup);
        when(spiedCarrierGroup.getSlotIndex(anyInt())).thenReturn(
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        spiedCarrierGroup.setMobileDataIndicators(
                mock(NetworkController.IconState.class),
                mock(NetworkController.IconState.class),
                0, 0, true, true, "", "", true, 0, true);
    }
}
