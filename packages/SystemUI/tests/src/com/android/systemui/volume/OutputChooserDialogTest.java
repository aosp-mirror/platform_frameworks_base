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

package com.android.systemui.volume;

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothProfile;
import android.net.wifi.WifiManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.v7.media.MediaRouter;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.TextView;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.statusbar.policy.BluetoothController;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Ignore
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OutputChooserDialogTest extends SysuiTestCase {

    OutputChooserDialog mDialog;

    @Mock
    private VolumeDialogController mVolumeController;
    @Mock
    private BluetoothController mController;
    @Mock
    private WifiManager mWifiManager;
    @Mock
    private TelecomManager mTelecomManager;

    @Mock
    private MediaRouterWrapper mRouter;


    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mVolumeController = mDependency.injectMockDependency(VolumeDialogController.class);
        mController = mDependency.injectMockDependency(BluetoothController.class);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);

        getContext().addMockSystemService(WifiManager.class, mWifiManager);
        getContext().addMockSystemService(TelecomManager.class, mTelecomManager);

        mDialog = new OutputChooserDialog(getContext(), mRouter);
    }

    @After
    public void tearDown() throws Exception {
        TestableLooper.get(this).processAllMessages();
    }
/*
    @Test
    public void testClickMediaRouterItemConnectsMedia() {
        mDialog.show();

        OutputChooserLayout.Item item = new OutputChooserLayout.Item();
        item.deviceType = OutputChooserLayout.Item.DEVICE_TYPE_MEDIA_ROUTER;
        MediaRouter.RouteInfo info = mock(MediaRouter.RouteInfo.class);
        when(info.isEnabled()).thenReturn(true);
        item.tag = info;

        mDialog.onDetailItemClick(item);
        verify(info, times(1)).select();
        verify(mController, never()).connect(any());
        mDialog.dismiss();
    }

    @Test
    public void testClickBtItemConnectsBt() {
        mDialog.show();

        OutputChooserLayout.Item item = new OutputChooserLayout.Item();
        item.deviceType = OutputChooserLayout.Item.DEVICE_TYPE_BT;
        CachedBluetoothDevice btDevice = mock(CachedBluetoothDevice.class);
        when(btDevice.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_DISCONNECTED);
        item.tag = btDevice;

        mDialog.onDetailItemClick(item);
        verify(mController, times(1)).connect(any());
        mDialog.dismiss();
    }

    @Test
    public void testTitleNotInCall() {
        mDialog.show();

        assertTrue(((TextView) mDialog.findViewById(R.id.title))
                .getText().toString().contains("Media"));
        mDialog.dismiss();
    }

    @Test
    public void testTitleInCall() {
        mDialog.setIsInCall(true);
        mDialog.show();

        assertTrue(((TextView) mDialog.findViewById(R.id.title))
                .getText().toString().contains("Phone"));
        mDialog.dismiss();
    }
*/
    @Test
    public void testNoMediaScanIfInCall() {
        mDialog.setIsInCall(true);
        mDialog.onAttachedToWindow();

        verify(mRouter, never()).addCallback(any(), any(), anyInt());
    }

    @Test
    public void testMediaScanIfNotInCall() {
        mDialog.setIsInCall(false);
        mDialog.onAttachedToWindow();

        verify(mRouter, times(1)).addCallback(any(), any(), anyInt());
    }

    @Test
    public void testRegisterCallbacks() {
        mDialog.setIsInCall(false);
        mDialog.onAttachedToWindow();

        verify(mRouter, times(1)).addCallback(any(), any(), anyInt());
        verify(mController, times(1)).addCallback(any());
        verify(mVolumeController, times(1)).addCallback(any(), any());
    }

    @Test
    public void testUnregisterCallbacks() {
        mDialog.setIsInCall(false);
        mDialog.onDetachedFromWindow();

        verify(mRouter, times(1)).removeCallback(any());
        verify(mController, times(1)).removeCallback(any());
        verify(mVolumeController, times(1)).removeCallback(any());
    }
}
