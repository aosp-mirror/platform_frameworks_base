/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.telephony.SubscriptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataIndicators;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.utils.leaks.LeakCheckedTest;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSCarrierGroupControllerTest extends LeakCheckedTest {

    private QSCarrierGroupController mQSCarrierGroupController;
    private NetworkController.SignalCallback mSignalCallback;
    private CarrierTextManager.CarrierTextCallback mCallback;
    @Mock
    private QSCarrierGroup mQSCarrierGroup;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private NetworkController mNetworkController;
    @Mock
    private CarrierTextManager.Builder mCarrierTextControllerBuilder;
    @Mock
    private CarrierTextManager mCarrierTextManager;
    @Mock
    private CarrierConfigTracker mCarrierConfigTracker;
    private TestableLooper mTestableLooper;
    @Mock private FeatureFlags mFeatureFlags;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mTestableLooper = TestableLooper.get(this);
        Handler handler = new FakeHandler(TestableLooper.get(this).getLooper());

        when(mNetworkController.hasVoiceCallingFeature()).thenReturn(true);
        doAnswer(invocation -> mSignalCallback = invocation.getArgument(0))
                .when(mNetworkController)
                .addCallback(any(NetworkController.SignalCallback.class));

        when(mCarrierTextControllerBuilder.setShowAirplaneMode(anyBoolean()))
                .thenReturn(mCarrierTextControllerBuilder);
        when(mCarrierTextControllerBuilder.setShowMissingSim(anyBoolean()))
                .thenReturn(mCarrierTextControllerBuilder);
        when(mCarrierTextControllerBuilder.build()).thenReturn(mCarrierTextManager);

        doAnswer(invocation -> mCallback = invocation.getArgument(0))
                .when(mCarrierTextManager)
                .setListening(any(CarrierTextManager.CarrierTextCallback.class));

        when(mQSCarrierGroup.getNoSimTextView()).thenReturn(new TextView(mContext));
        when(mQSCarrierGroup.getCarrier1View()).thenReturn(mock(QSCarrier.class));
        when(mQSCarrierGroup.getCarrier2View()).thenReturn(mock(QSCarrier.class));
        when(mQSCarrierGroup.getCarrier3View()).thenReturn(mock(QSCarrier.class));
        when(mQSCarrierGroup.getCarrierDivider1()).thenReturn(new View(mContext));
        when(mQSCarrierGroup.getCarrierDivider2()).thenReturn(new View(mContext));

        mQSCarrierGroupController = new QSCarrierGroupController.Builder(
                mActivityStarter, handler, TestableLooper.get(this).getLooper(),
                mNetworkController, mCarrierTextControllerBuilder, mContext, mCarrierConfigTracker,
                mFeatureFlags)
                .setQSCarrierGroup(mQSCarrierGroup)
                .build();

        mQSCarrierGroupController.setListening(true);
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_sameLengths() {
        QSCarrierGroupController spiedCarrierGroupController =
                Mockito.spy(mQSCarrierGroupController);
        when(spiedCarrierGroupController.getSlotIndex(anyInt())).thenAnswer(
                (Answer<Integer>) invocationOnMock -> invocationOnMock.getArgument(0));

        // listOfCarriers length 1, subscriptionIds length 1, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c1 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 1, subscriptionIds length 1, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c2 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 2, subscriptionIds length 2, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c3 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 2, subscriptionIds length 2, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);

        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_differentLength() {
        QSCarrierGroupController spiedCarrierGroupController =
                Mockito.spy(mQSCarrierGroupController);
        when(spiedCarrierGroupController.getSlotIndex(anyInt())).thenAnswer(
                (Answer<Integer>) invocationOnMock -> invocationOnMock.getArgument(0));

        // listOfCarriers length 2, subscriptionIds length 1, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c1 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 2, subscriptionIds length 1, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c2 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 1, subscriptionIds length 2, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c3 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 1, subscriptionIds length 2, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_invalidSim() {
        QSCarrierGroupController spiedCarrierGroupController =
                Mockito.spy(mQSCarrierGroupController);
        when(spiedCarrierGroupController.getSlotIndex(anyInt())).thenReturn(
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);

        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{"", ""},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testSetMobileDataIndicators_invalidSim() {
        MobileDataIndicators indicators = new MobileDataIndicators(
                mock(NetworkController.IconState.class),
                mock(NetworkController.IconState.class),
                0, 0, true, true, "", "", "", true, 0, true, true);
        mSignalCallback.setMobileDataIndicators(indicators);
    }

    @Test
    public void testNoEmptyVisibleView_airplaneMode() {
        CarrierTextManager.CarrierTextCallbackInfo
                info = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{""},
                true,
                new int[]{0},
                true /* airplaneMode */);
        mCallback.updateCarrierInfo(info);
        mTestableLooper.processAllMessages();
        assertEquals(View.GONE, mQSCarrierGroup.getNoSimTextView().getVisibility());
    }
}
