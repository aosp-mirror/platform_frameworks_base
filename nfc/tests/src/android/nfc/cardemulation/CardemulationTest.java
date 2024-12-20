/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.nfc.cardemulation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.Constants;
import android.nfc.INfcCardEmulation;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class CardemulationTest {

    private CardEmulation mCardEmulation;
    @Mock
    private Context mContext;
    @Mock
    private INfcCardEmulation mINfcCardEmulation;
    @Mock
    private NfcAdapter mNfcAdapter;
    @Mock
    private PackageManager mPackageManager;
    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .mockStatic(NfcAdapter.class)
                .mockStatic(Settings.Secure.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);

        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
                .thenReturn(true);
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        assertThat(mNfcAdapter).isNotNull();
        when(mNfcAdapter.getCardEmulationService()).thenReturn(mINfcCardEmulation);
        when(mNfcAdapter.getContext()).thenReturn(mContext);
        mCardEmulation = CardEmulation.getInstance(mNfcAdapter);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testIsDefaultServiceForCategory() throws RemoteException {
        ComponentName componentName = mock(ComponentName.class);
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        when(mINfcCardEmulation.isDefaultServiceForCategory(1, componentName,
                "payment")).thenReturn(true);
        boolean result = mCardEmulation.isDefaultServiceForCategory(componentName,
                "payment");
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).isDefaultServiceForCategory(1, componentName,
                "payment");

    }

    @Test
    public void testIsDefaultServiceForAid() throws RemoteException {
        ComponentName componentName = mock(ComponentName.class);
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        when(mINfcCardEmulation.isDefaultServiceForAid(1, componentName,
                "payment")).thenReturn(true);
        boolean result = mCardEmulation.isDefaultServiceForAid(componentName,
                "payment");
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).isDefaultServiceForAid(1, componentName,
                "payment");
    }

    @Test
    public void testCategoryAllowsForegroundPreference() throws Settings.SettingNotFoundException {
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        RoleManager roleManager = mock(RoleManager.class);
        when(roleManager.isRoleAvailable(RoleManager.ROLE_WALLET)).thenReturn(false);
        when(mContext.getSystemService(RoleManager.class)).thenReturn(roleManager);
        ContentResolver contentResolver = mock(ContentResolver.class);
        when(mContext.getContentResolver()).thenReturn(contentResolver);
        when(Settings.Secure.getInt(contentResolver, Constants
                .SETTINGS_SECURE_NFC_PAYMENT_FOREGROUND)).thenReturn(1);
        boolean result = mCardEmulation.categoryAllowsForegroundPreference("payment");
        assertThat(result).isTrue();
    }

    @Test
    public void testGetSelectionModeForCategory() throws RemoteException {
        when(mINfcCardEmulation.isDefaultPaymentRegistered()).thenReturn(true);
        int result = mCardEmulation.getSelectionModeForCategory("payment");
        assertThat(result).isEqualTo(0);
    }

    @Test
    public void testSetShouldDefaultToObserveModeForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.setShouldDefaultToObserveModeForService(1, componentName, true))
                .thenReturn(true);
        boolean result = mCardEmulation
                .setShouldDefaultToObserveModeForService(componentName, true);
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).setShouldDefaultToObserveModeForService(1, componentName, true);
    }

    @Test
    public void testRegisterPollingLoopFilterForService()throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.registerPollingLoopFilterForService(anyInt(),
                any(), anyString(), anyBoolean())).thenReturn(true);
        boolean result = mCardEmulation.registerPollingLoopFilterForService(componentName,
                "A0000000041010", true);
        assertThat(result).isTrue();
        verify(mINfcCardEmulation)
                .registerPollingLoopFilterForService(anyInt(), any(), anyString(), anyBoolean());
    }

    @Test
    public void testRemovePollingLoopFilterForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.removePollingLoopFilterForService(anyInt(), any(), anyString()))
                .thenReturn(true);
        boolean result = mCardEmulation
                .removePollingLoopFilterForService(componentName, "A0000000041010");
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).removePollingLoopFilterForService(anyInt(), any(), anyString());
    }

    @Test
    public void testRegisterPollingLoopPatternFilterForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.registerPollingLoopPatternFilterForService(anyInt(), any(),
                anyString(), anyBoolean())).thenReturn(true);
        boolean result = mCardEmulation.registerPollingLoopPatternFilterForService(componentName,
                "A0000000041010", true);
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).registerPollingLoopPatternFilterForService(anyInt(), any(),
                anyString(), anyBoolean());
    }

    @Test
    public void testRemovePollingLoopPatternFilterForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.removePollingLoopPatternFilterForService(anyInt(), any(),
                anyString())).thenReturn(true);
        boolean result = mCardEmulation.removePollingLoopPatternFilterForService(componentName,
                "A0000000041010");
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).removePollingLoopPatternFilterForService(anyInt(), any(),
                anyString());
    }

    @Test
    public void testRegisterAidsForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.registerAidGroupForService(anyInt(), any(),
                any())).thenReturn(true);
        List<String> aids = new ArrayList<>();
        aids.add("A0000000041010");
        boolean result = mCardEmulation.registerAidsForService(componentName, "payment",
                aids);
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).registerAidGroupForService(anyInt(), any(),
                any());
    }

    @Test
    public void testUnsetOffHostForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.unsetOffHostForService(1, componentName)).thenReturn(true);
        boolean result = mCardEmulation.unsetOffHostForService(componentName);
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).unsetOffHostForService(1, componentName);
    }

    @Test
    public void testSetOffHostForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        when(NfcAdapter.getDefaultAdapter(any())).thenReturn(mNfcAdapter);
        List<String> elements = new ArrayList<>();
        elements.add("eSE");
        when(mNfcAdapter.getSupportedOffHostSecureElements()).thenReturn(elements);
        ComponentName componentName = mock(ComponentName.class);
        when(mINfcCardEmulation.setOffHostForService(anyInt(), any(), anyString()))
                .thenReturn(true);
        boolean result = mCardEmulation.setOffHostForService(componentName,
                "eSE");
        assertThat(result).isTrue();
        verify(mINfcCardEmulation).setOffHostForService(anyInt(), any(), anyString());
    }

    @Test
    public void testGetAidsForService() throws RemoteException {
        UserHandle userHandle = mock(UserHandle.class);
        when(userHandle.getIdentifier()).thenReturn(1);
        when(mContext.getUser()).thenReturn(userHandle);
        ComponentName componentName = mock(ComponentName.class);
        List<String> elements = new ArrayList<>();
        elements.add("eSE");
        AidGroup aidGroup = mock(AidGroup.class);
        when(aidGroup.getAids()).thenReturn(elements);
        when(mINfcCardEmulation.getAidGroupForService(1, componentName, "payment"))
                .thenReturn(aidGroup);
        List<String> result = mCardEmulation.getAidsForService(componentName, "payment");
        assertThat(result).isNotNull();
        assertThat(result.size()).isGreaterThan(0);
        verify(mINfcCardEmulation).getAidGroupForService(1, componentName, "payment");
    }
}
