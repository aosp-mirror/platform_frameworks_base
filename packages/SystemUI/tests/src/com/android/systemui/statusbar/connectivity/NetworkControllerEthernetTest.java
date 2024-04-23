/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity;

import static junit.framework.Assert.assertEquals;

import android.net.NetworkCapabilities;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NetworkControllerEthernetTest extends NetworkControllerBaseTest {

    @Test
    public void testEthernetIcons() {
        verifyLastEthernetIcon(false, 0);

        setEthernetState(true, false);   // Connected, unvalidated.
        verifyLastEthernetIcon(true, EthernetIcons.ETHERNET_ICONS[0][0]);

        setEthernetState(true, true);    // Connected, validated.
        verifyLastEthernetIcon(true, EthernetIcons.ETHERNET_ICONS[1][0]);

        setEthernetState(true, false);   // Connected, unvalidated.
        verifyLastEthernetIcon(true, EthernetIcons.ETHERNET_ICONS[0][0]);

        setEthernetState(false, false);  // Disconnected.
        verifyLastEthernetIcon(false, 0);
    }

    protected void setEthernetState(boolean connected, boolean validated) {
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_ETHERNET, validated, connected, null);
    }

    protected void verifyLastEthernetIcon(boolean visible, int icon) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setEthernetIndicators(
                iconArg.capture());
        IconState iconState = iconArg.getValue();
        assertEquals("Ethernet visible, in status bar", visible, iconState.visible);
        assertEquals("Ethernet icon, in status bar", icon, iconState.icon);
    }
}
