package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class HeadsetProfileTest {

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
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = spy(RuntimeEnvironment.application);
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());

        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);

        mProfile = new HeadsetProfile(context, mDeviceManager, mProfileManager);
        mServiceListener = mShadowBluetoothAdapter.getServiceListener();
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
