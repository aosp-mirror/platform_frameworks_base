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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.nfc.INfcCardEmulation;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.os.UserHandle;

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
}
