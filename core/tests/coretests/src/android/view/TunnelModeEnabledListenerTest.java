/*
 * Copyright 2021 The Android Open Source Project
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

package android.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class TunnelModeEnabledListenerTest {
    private TestableTunnelModeEnabledListener mListener;

    @Before
    public void init() {
        mListener = new TestableTunnelModeEnabledListener();
    }

    @Test
    public void testRegisterUnregister() {
        TunnelModeEnabledListener.register(mListener);
        TunnelModeEnabledListener.unregister(mListener);
    }

    @Test
    public void testDispatchUpdatesListener() {
        TunnelModeEnabledListener.dispatchOnTunnelModeEnabledChanged(mListener, true);
        assertEquals(true, mListener.mTunnelModeEnabled.get());
        TunnelModeEnabledListener.dispatchOnTunnelModeEnabledChanged(mListener, false);
        assertEquals(false, mListener.mTunnelModeEnabled.get());
    }

    @Test
    public void testRegisterUpdatesListener() throws Exception {
        TunnelModeEnabledListener.register(mListener);
        TimeUnit.SECONDS.sleep(1);
        assertTrue(mListener.mTunnelModeEnabledUpdated.get());
        TunnelModeEnabledListener.unregister(mListener);
    }

    private static class TestableTunnelModeEnabledListener extends TunnelModeEnabledListener {
        AtomicBoolean mTunnelModeEnabled;
        AtomicBoolean mTunnelModeEnabledUpdated;

        TestableTunnelModeEnabledListener() {
            super(Runnable::run);
            mTunnelModeEnabled = new AtomicBoolean(false);
            mTunnelModeEnabledUpdated = new AtomicBoolean();
        }

        @Override
        public void onTunnelModeEnabledChanged(boolean tunnelModeEnabled) {
            mTunnelModeEnabled.set(tunnelModeEnabled);
            mTunnelModeEnabledUpdated.set(true);
        }
    }

}
