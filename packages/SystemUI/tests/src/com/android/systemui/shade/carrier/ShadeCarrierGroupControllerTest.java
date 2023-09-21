/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.carrier;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.connectivity.IconState;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.kotlin.FlowProviderKt;
import com.android.systemui.utils.leaks.LeakCheckedTest;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import kotlinx.coroutines.flow.MutableStateFlow;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ShadeCarrierGroupControllerTest extends LeakCheckedTest {

    private static final String SINGLE_CARRIER_TEXT = "singleCarrierText";
    private static final String MULTI_CARRIER_TEXT = "multiCarrierText";
    private static final String FIRST_CARRIER_NAME = "carrier1";
    private static final String SECOND_CARRIER_NAME = "carrier2";

    private ShadeCarrierGroupController mShadeCarrierGroupController;
    private SignalCallback mSignalCallback;
    private CarrierTextManager.CarrierTextCallback mCallback;
    @Mock
    private ShadeCarrierGroup mShadeCarrierGroup;
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
    @Mock
    private ShadeCarrier mShadeCarrier1;
    @Mock
    private ShadeCarrier mShadeCarrier2;
    @Mock
    private ShadeCarrier mShadeCarrier3;
    private TestableLooper mTestableLooper;
    @Mock
    private ShadeCarrierGroupController.OnSingleCarrierChangedListener
            mOnSingleCarrierChangedListener;
    @Mock
    private MobileUiAdapter mMobileUiAdapter;
    @Mock
    private MobileIconsViewModel mMobileIconsViewModel;
    @Mock
    private ShadeCarrierGroupMobileIconViewModel mShadeCarrierGroupMobileIconViewModel;
    @Mock
    private MobileViewLogger mMobileViewLogger;
    @Mock
    private MobileContextProvider mMobileContextProvider;
    @Mock
    private StatusBarPipelineFlags mStatusBarPipelineFlags;

    private final MutableStateFlow<Boolean> mIsVisibleFlow =
            FlowProviderKt.getMutableStateFlow(true);

    private FakeSlotIndexResolver mSlotIndexResolver;
    private ClickListenerTextView mNoCarrierTextView;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mTestableLooper = TestableLooper.get(this);
        Handler handler = new FakeHandler(TestableLooper.get(this).getLooper());

        when(mNetworkController.hasVoiceCallingFeature()).thenReturn(true);
        doAnswer(invocation -> mSignalCallback = invocation.getArgument(0))
                .when(mNetworkController)
                .addCallback(any(SignalCallback.class));

        when(mCarrierTextControllerBuilder.setShowAirplaneMode(anyBoolean()))
                .thenReturn(mCarrierTextControllerBuilder);
        when(mCarrierTextControllerBuilder.setShowMissingSim(anyBoolean()))
                .thenReturn(mCarrierTextControllerBuilder);
        when(mCarrierTextControllerBuilder.setDebugLocationString(anyString()))
                .thenReturn(mCarrierTextControllerBuilder);
        when(mCarrierTextControllerBuilder.build()).thenReturn(mCarrierTextManager);

        doAnswer(invocation -> mCallback = invocation.getArgument(0))
                .when(mCarrierTextManager)
                .setListening(any(CarrierTextManager.CarrierTextCallback.class));

        mNoCarrierTextView = new ClickListenerTextView(mContext);
        when(mShadeCarrierGroup.getNoSimTextView()).thenReturn(mNoCarrierTextView);
        when(mShadeCarrierGroup.getCarrier1View()).thenReturn(mShadeCarrier1);
        when(mShadeCarrierGroup.getCarrier2View()).thenReturn(mShadeCarrier2);
        when(mShadeCarrierGroup.getCarrier3View()).thenReturn(mShadeCarrier3);
        when(mShadeCarrierGroup.getCarrierDivider1()).thenReturn(new View(mContext));
        when(mShadeCarrierGroup.getCarrierDivider2()).thenReturn(new View(mContext));

        mSlotIndexResolver = new FakeSlotIndexResolver();

        when(mMobileUiAdapter.getMobileIconsViewModel()).thenReturn(mMobileIconsViewModel);

        mShadeCarrierGroupController = new ShadeCarrierGroupController.Builder(
                mActivityStarter,
                handler,
                TestableLooper.get(this).getLooper(),
                mNetworkController,
                mCarrierTextControllerBuilder,
                mContext,
                mCarrierConfigTracker,
                mSlotIndexResolver,
                mMobileUiAdapter,
                mMobileContextProvider,
                mStatusBarPipelineFlags
        )
                .setShadeCarrierGroup(mShadeCarrierGroup)
                .build();

        mShadeCarrierGroupController.setListening(true);
    }

    private void setupWithNewPipeline() {
        when(mStatusBarPipelineFlags.useNewShadeCarrierGroupMobileIcons()).thenReturn(true);
        when(mMobileContextProvider.getMobileContextForSub(anyInt(), any())).thenReturn(mContext);
        when(mMobileIconsViewModel.getLogger()).thenReturn(mMobileViewLogger);
        when(mShadeCarrierGroupMobileIconViewModel.isVisible()).thenReturn(mIsVisibleFlow);
        when(mMobileIconsViewModel.viewModelForSub(anyInt(), any()))
                .thenReturn(mShadeCarrierGroupMobileIconViewModel);
    }

    @Test
    public void testInitiallyMultiCarrier() {
        assertFalse(mShadeCarrierGroupController.isSingleCarrier());
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_sameLengths() {
        // listOfCarriers length 1, subscriptionIds length 1, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c1 = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 1, subscriptionIds length 1, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c2 = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 2, subscriptionIds length 2, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c3 = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 2, subscriptionIds length 2, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);

        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_differentLength() {
        // listOfCarriers length 2, subscriptionIds length 1, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c1 = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                false,
                new int[]{0});
        mCallback.updateCarrierInfo(c1);

        // listOfCarriers length 2, subscriptionIds length 1, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c2 = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0});
        mCallback.updateCarrierInfo(c2);

        // listOfCarriers length 1, subscriptionIds length 2, anySims false
        CarrierTextManager.CarrierTextCallbackInfo
                c3 = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                false,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c3);

        // listOfCarriers length 1, subscriptionIds length 2, anySims true
        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testUpdateCarrierText_invalidSim() {
        mSlotIndexResolver.overrideInvalid = true;

        CarrierTextManager.CarrierTextCallbackInfo
                c4 = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0, 1});
        mCallback.updateCarrierInfo(c4);
        mTestableLooper.processAllMessages();
    }

    @Test // throws no Exception
    public void testSetMobileDataIndicators_invalidSim() {
        mSlotIndexResolver.overrideInvalid = true;

        MobileDataIndicators indicators = new MobileDataIndicators(
                mock(IconState.class),
                mock(IconState.class),
                0, 0, true, true, "", "", "", 0, true, true);
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
        assertEquals(View.GONE, mShadeCarrierGroup.getNoSimTextView().getVisibility());
    }

    @Test
    public void testVisibleView_airplaneMode_WFCOn() {
        CarrierTextManager.CarrierTextCallbackInfo
                info = new CarrierTextManager.CarrierTextCallbackInfo(
                "",
                new CharSequence[]{FIRST_CARRIER_NAME, ""},
                true,
                new int[]{0, 1},
                false /* airplaneMode */);
        mCallback.updateCarrierInfo(info);
        mTestableLooper.processAllMessages();
        assertEquals(View.VISIBLE, mShadeCarrierGroupController.getShadeCarrierVisibility(0));
    }

    @Test
    public void testListenerNotCalledOnRegistreation() {
        mShadeCarrierGroupController
                .setOnSingleCarrierChangedListener(mOnSingleCarrierChangedListener);

        verify(mOnSingleCarrierChangedListener, never()).onSingleCarrierChanged(anyBoolean());
    }

    @Test
    public void testSingleCarrier() {
        // Only one element in the info
        CarrierTextManager.CarrierTextCallbackInfo
                info = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{SINGLE_CARRIER_TEXT},
                true,
                new int[]{0},
                false /* airplaneMode */);

        mCallback.updateCarrierInfo(info);
        mTestableLooper.processAllMessages();

        verify(mShadeCarrier1).updateState(any(), eq(true));
        verify(mShadeCarrier2).updateState(any(), eq(true));
        verify(mShadeCarrier3).updateState(any(), eq(true));
    }

    @Test
    public void testMultiCarrier() {
        // More than one element in the info
        CarrierTextManager.CarrierTextCallbackInfo
                info = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0, 1},
                false /* airplaneMode */);

        mCallback.updateCarrierInfo(info);
        mTestableLooper.processAllMessages();

        verify(mShadeCarrier1).updateState(any(), eq(false));
        verify(mShadeCarrier2).updateState(any(), eq(false));
        verify(mShadeCarrier3).updateState(any(), eq(false));
    }

    @Test
    public void testSingleMultiCarrierSwitch() {
        CarrierTextManager.CarrierTextCallbackInfo
                singleCarrierInfo = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                true,
                new int[]{0},
                false /* airplaneMode */);

        CarrierTextManager.CarrierTextCallbackInfo
                multiCarrierInfo = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0, 1},
                false /* airplaneMode */);

        mCallback.updateCarrierInfo(singleCarrierInfo);
        mTestableLooper.processAllMessages();

        mShadeCarrierGroupController
                .setOnSingleCarrierChangedListener(mOnSingleCarrierChangedListener);
        reset(mOnSingleCarrierChangedListener);

        mCallback.updateCarrierInfo(multiCarrierInfo);
        mTestableLooper.processAllMessages();
        verify(mOnSingleCarrierChangedListener).onSingleCarrierChanged(false);

        mCallback.updateCarrierInfo(singleCarrierInfo);
        mTestableLooper.processAllMessages();
        verify(mOnSingleCarrierChangedListener).onSingleCarrierChanged(true);
    }

    @Test
    public void testNoCallbackIfSingleCarrierDoesntChange() {
        CarrierTextManager.CarrierTextCallbackInfo
                singleCarrierInfo = new CarrierTextManager.CarrierTextCallbackInfo(
                SINGLE_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME},
                true,
                new int[]{0},
                false /* airplaneMode */);

        mCallback.updateCarrierInfo(singleCarrierInfo);
        mTestableLooper.processAllMessages();

        mShadeCarrierGroupController
                .setOnSingleCarrierChangedListener(mOnSingleCarrierChangedListener);

        mCallback.updateCarrierInfo(singleCarrierInfo);
        mTestableLooper.processAllMessages();

        verify(mOnSingleCarrierChangedListener, never()).onSingleCarrierChanged(anyBoolean());
    }

    @Test
    public void testNoCallbackIfMultiCarrierDoesntChange() {
        CarrierTextManager.CarrierTextCallbackInfo
                multiCarrierInfo = new CarrierTextManager.CarrierTextCallbackInfo(
                MULTI_CARRIER_TEXT,
                new CharSequence[]{FIRST_CARRIER_NAME, SECOND_CARRIER_NAME},
                true,
                new int[]{0, 1},
                false /* airplaneMode */);

        mCallback.updateCarrierInfo(multiCarrierInfo);
        mTestableLooper.processAllMessages();

        mShadeCarrierGroupController
                .setOnSingleCarrierChangedListener(mOnSingleCarrierChangedListener);

        mCallback.updateCarrierInfo(multiCarrierInfo);
        mTestableLooper.processAllMessages();

        verify(mOnSingleCarrierChangedListener, never()).onSingleCarrierChanged(anyBoolean());
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testUpdateModernMobileIcons_addSubscription() {
        setupWithNewPipeline();

        mShadeCarrier1.setVisibility(View.GONE);
        mShadeCarrier2.setVisibility(View.GONE);
        mShadeCarrier3.setVisibility(View.GONE);

        List<Integer> subIds = new ArrayList<>();
        subIds.add(0);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2, never()).addModernMobileView(any());
        verify(mShadeCarrier3, never()).addModernMobileView(any());

        resetShadeCarriers();

        subIds.add(1);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1, times(1)).removeModernMobileView();

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2).addModernMobileView(any());
        verify(mShadeCarrier3, never()).addModernMobileView(any());
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testUpdateModernMobileIcons_removeSubscription() {
        setupWithNewPipeline();

        List<Integer> subIds = new ArrayList<>();
        subIds.add(0);
        subIds.add(1);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2).addModernMobileView(any());
        verify(mShadeCarrier3, never()).addModernMobileView(any());

        resetShadeCarriers();

        subIds.remove(1);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1, times(1)).removeModernMobileView();
        verify(mShadeCarrier2, times(1)).removeModernMobileView();

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2, never()).addModernMobileView(any());
        verify(mShadeCarrier3, never()).addModernMobileView(any());
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testUpdateModernMobileIcons_removeSubscriptionOutOfOrder() {
        setupWithNewPipeline();

        List<Integer> subIds = new ArrayList<>();
        subIds.add(0);
        subIds.add(1);
        subIds.add(2);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2).addModernMobileView(any());
        verify(mShadeCarrier3).addModernMobileView(any());

        resetShadeCarriers();

        subIds.remove(1);
        mShadeCarrierGroupController.updateModernMobileIcons(subIds);

        verify(mShadeCarrier1).removeModernMobileView();
        verify(mShadeCarrier2).removeModernMobileView();
        verify(mShadeCarrier3).removeModernMobileView();

        verify(mShadeCarrier1).addModernMobileView(any());
        verify(mShadeCarrier2, never()).addModernMobileView(any());
        verify(mShadeCarrier3).addModernMobileView(any());
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testProcessSubIdList_moreSubsThanSimSlots_listLimitedToMax() {
        setupWithNewPipeline();

        List<Integer> subIds = Arrays.asList(0, 1, 2, 2);

        assertThat(mShadeCarrierGroupController.processSubIdList(subIds).size()).isEqualTo(3);
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testProcessSubIdList_invalidSimSlotIndexFilteredOut() {
        setupWithNewPipeline();

        List<Integer> subIds = Arrays.asList(0, 1, -1);

        List<ShadeCarrierGroupController.IconData> processedSubs =
                mShadeCarrierGroupController.processSubIdList(subIds);
        assertThat(processedSubs).hasSize(2);
        assertThat(processedSubs.get(0).subId).isNotEqualTo(-1);
        assertThat(processedSubs.get(1).subId).isNotEqualTo(-1);
    }

    @TestableLooper.RunWithLooper(setAsMainLooper = true)
    @Test
    public void testProcessSubIdList_indexGreaterThanSimSlotsFilteredOut() {
        setupWithNewPipeline();

        List<Integer> subIds = Arrays.asList(0, 4);

        List<ShadeCarrierGroupController.IconData> processedSubs =
                mShadeCarrierGroupController.processSubIdList(subIds);
        assertThat(processedSubs).hasSize(1);
        assertThat(processedSubs.get(0).subId).isNotEqualTo(4);
    }


    @Test
    public void testOnlyInternalViewsHaveClickableListener() {
        ArgumentCaptor<View.OnClickListener> captor =
                ArgumentCaptor.forClass(View.OnClickListener.class);

        verify(mShadeCarrier1).setOnClickListener(captor.capture());
        verify(mShadeCarrier2).setOnClickListener(captor.getValue());
        verify(mShadeCarrier3).setOnClickListener(captor.getValue());

        assertThat(mNoCarrierTextView.getOnClickListener()).isSameInstanceAs(captor.getValue());
        verify(mShadeCarrierGroup, never()).setOnClickListener(any());
    }

    @Test
    public void testOnClickListenerDoesntStartActivityIfViewNotVisible() {
        ArgumentCaptor<View.OnClickListener> captor =
                ArgumentCaptor.forClass(View.OnClickListener.class);

        verify(mShadeCarrier1).setOnClickListener(captor.capture());
        when(mShadeCarrier1.isVisibleToUser()).thenReturn(false);

        captor.getValue().onClick(mShadeCarrier1);
        verifyZeroInteractions(mActivityStarter);
    }

    @Test
    public void testOnClickListenerLaunchesActivityIfViewVisible() {
        ArgumentCaptor<View.OnClickListener> listenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);

        verify(mShadeCarrier1).setOnClickListener(listenerCaptor.capture());
        when(mShadeCarrier1.isVisibleToUser()).thenReturn(true);

        listenerCaptor.getValue().onClick(mShadeCarrier1);
        verify(mActivityStarter)
                .postStartActivityDismissingKeyguard(intentCaptor.capture(), anyInt());
        assertThat(intentCaptor.getValue().getAction())
                .isEqualTo(Settings.ACTION_WIRELESS_SETTINGS);
    }

    private void resetShadeCarriers() {
        reset(mShadeCarrier1);
        reset(mShadeCarrier2);
        reset(mShadeCarrier3);
    }

    private class FakeSlotIndexResolver implements ShadeCarrierGroupController.SlotIndexResolver {
        public boolean overrideInvalid;

        @Override
        public int getSlotIndex(int subscriptionId) {
            return overrideInvalid ? -1 : subscriptionId;
        }
    }

    private class ClickListenerTextView extends TextView {
        View.OnClickListener mListener = null;

        ClickListenerTextView(Context context) {
            super(context);
        }

        @Override
        public void setOnClickListener(OnClickListener l) {
            super.setOnClickListener(l);
            mListener = l;
        }

        View.OnClickListener getOnClickListener() {
            return mListener;
        }
    }
}
