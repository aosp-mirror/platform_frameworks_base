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

package com.android.mediaroutertest;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.display.DisplayManagerGlobal;
import android.media.MediaRouter;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRouteInfoTest {
    private TestableRouteInfo mRouteInfo;
    private static Display sWifiDisplay;
    private static Display sExternalDisplay;
    private static Display sInternalDisplay;
    private static final String FAKE_MAC_ADDRESS = "fake MAC address";

    @BeforeClass
    public static void setUpOnce() {
        final DisplayManagerGlobal global = DisplayManagerGlobal.getInstance();
        final DisplayInfo wifiInfo = new DisplayInfo();
        wifiInfo.flags = Display.FLAG_PRESENTATION;
        wifiInfo.type = Display.TYPE_WIFI;
        wifiInfo.address = DisplayAddress.fromMacAddress(FAKE_MAC_ADDRESS);
        sWifiDisplay = new Display(global, 2, wifiInfo, new DisplayAdjustments());

        final DisplayInfo externalInfo = new DisplayInfo();
        externalInfo.flags = Display.FLAG_PRESENTATION;
        externalInfo.type = Display.TYPE_EXTERNAL;
        sExternalDisplay = new Display(global, 3, externalInfo,  new DisplayAdjustments());

        final DisplayInfo internalInfo = new DisplayInfo();
        internalInfo.flags = Display.FLAG_PRESENTATION;
        internalInfo.type = Display.TYPE_INTERNAL;
        sInternalDisplay = new Display(global, 4, internalInfo,  new DisplayAdjustments());
    }

    @Before
    public void setUp() {
        mRouteInfo = new TestableRouteInfo();
    }

    @Test
    public void testGetPresentationDisplay_notLiveVideo() {
        mRouteInfo.setPresentationDisplays(sWifiDisplay);
        mRouteInfo.mSupportedType = MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY;

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isNull();
    }

    @Test
    public void testGetPresentationDisplay_includesLiveVideo() {
        mRouteInfo.setPresentationDisplays(sWifiDisplay);
        mRouteInfo.mSupportedType |= MediaRouter.ROUTE_TYPE_LIVE_AUDIO;

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sWifiDisplay);
    }

    @Test
    public void testGetPresentationDisplay_noPresentationDisplay() {
        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isNull();
    }

    @Test
    public void testGetPresentationDisplay_wifiDisplayOnly() {
        mRouteInfo.setPresentationDisplays(sWifiDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sWifiDisplay);
    }

    @Test
    public void testGetPresentationDisplay_externalDisplayOnly() {
        mRouteInfo.setPresentationDisplays(sExternalDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sExternalDisplay);
    }

    @Test
    public void testGetPresentationDisplay_internalDisplayOnly() {
        mRouteInfo.setPresentationDisplays(sInternalDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sInternalDisplay);
    }

    @Test
    public void testGetPresentationDisplay_addressNotMatch() {
        mRouteInfo.setPresentationDisplays(sWifiDisplay);
        mRouteInfo.mDeviceAddress = "Not match";

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isNull();
    }

    @Test
    public void testGetPresentationDisplay_containsWifiAndExternalDisplays_returnWifiDisplay() {
        mRouteInfo.setPresentationDisplays(sExternalDisplay, sWifiDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sWifiDisplay);
    }

    @Test
    public void testGetPresentationDisplay_containsExternalAndInternalDisplays_returnExternal() {
        mRouteInfo.setPresentationDisplays(sExternalDisplay, sInternalDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sExternalDisplay);
    }

    @Test
    public void testGetPresentationDisplay_containsAllDisplays_returnWifiDisplay() {
        mRouteInfo.setPresentationDisplays(sExternalDisplay, sInternalDisplay, sWifiDisplay);

        mRouteInfo.updatePresentationDisplay();

        assertThat(mRouteInfo.getPresentationDisplay()).isEqualTo(sWifiDisplay);
    }

    private static class TestableRouteInfo extends MediaRouter.RouteInfo {
        private Display[] mDisplays = new Display[0];
        private int mSupportedType = MediaRouter.ROUTE_TYPE_LIVE_VIDEO;
        private String mDeviceAddress = FAKE_MAC_ADDRESS;
        private MediaRouter.RouteInfo mDefaultRouteInfo = null;

        private TestableRouteInfo() {
            super(null);
        }

        private void setPresentationDisplays(Display ...displays) {
            mDisplays = new Display[displays.length];
            System.arraycopy(displays, 0, mDisplays, 0, displays.length);
        }

        @Override
        public Display[] getAllPresentationDisplays() {
            return mDisplays;
        }

        @Override
        public int getSupportedTypes() {
            return mSupportedType;
        }

        @Override
        public String getDeviceAddress() {
            return mDeviceAddress;
        }

        @Override
        public MediaRouter.RouteInfo getDefaultAudioVideo() {
            return mDefaultRouteInfo;
        }
    }
}
