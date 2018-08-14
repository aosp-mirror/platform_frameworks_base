package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class HeadsetProfileTest {

    @Mock
    private LocalBluetoothAdapter mAdapter;
    @Mock
    private CachedBluetoothDeviceManager mDeviceManager;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private BluetoothHeadset mService;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    private BluetoothProfile.ServiceListener mServiceListener;
    private HeadsetProfile mProfile;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = spy(RuntimeEnvironment.application);

        doAnswer((invocation) -> {
            mServiceListener = (BluetoothProfile.ServiceListener) invocation.getArguments()[1];
            return null;
        }).when(mAdapter).getProfileProxy(any(Context.class), any(), eq(BluetoothProfile.HEADSET));
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);

        mProfile = new HeadsetProfile(context, mAdapter, mDeviceManager, mProfileManager);
        mServiceListener.onServiceConnected(BluetoothProfile.HEADSET, mService);
    }

    @Test
    public void bluetoothProfile_shouldReturnTheAudioStatusFromBlueToothHeadsetService() {
        when(mService.isAudioOn()).thenReturn(true);
        assertThat(mProfile.isAudioOn()).isTrue();

        when(mService.isAudioOn()).thenReturn(false);
        assertThat(mProfile.isAudioOn()).isFalse();
    }

    @Test
    public void testHeadsetProfile_shouldReturnAudioState() {
        when(mService.getAudioState(mBluetoothDevice)).
                thenReturn(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
        assertThat(mProfile.getAudioState(mBluetoothDevice)).
                isEqualTo(BluetoothHeadset.STATE_AUDIO_DISCONNECTED);

        when(mService.getAudioState(mBluetoothDevice)).
                thenReturn(BluetoothHeadset.STATE_AUDIO_CONNECTED);
        assertThat(mProfile.getAudioState(mBluetoothDevice)).
                isEqualTo(BluetoothHeadset.STATE_AUDIO_CONNECTED);
    }
}
