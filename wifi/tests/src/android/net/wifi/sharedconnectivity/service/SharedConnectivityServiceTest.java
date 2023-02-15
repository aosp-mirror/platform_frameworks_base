/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.service;

import static org.junit.Assert.assertNotNull;

import android.content.Intent;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.sharedconnectivity.service.SharedConnectivityService}.
 */
@SmallTest
public class SharedConnectivityServiceTest {

    /**
     * Verifies service returns
     */
    @Test
    public void testOnBind() {
        SharedConnectivityService service = createService();
        assertNotNull(service.onBind(new Intent()));
    }

    @Test
    public void testCallbacks() {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
    }

    private SharedConnectivityService createService() {
        return new SharedConnectivityService(new Handler(new TestLooper().getLooper())) {
            @Override
            public void onConnectTetherNetwork(TetherNetwork network) {}

            @Override
            public void onDisconnectTetherNetwork(TetherNetwork network) {}

            @Override
            public void onConnectKnownNetwork(KnownNetwork network) {}

            @Override
            public void onForgetKnownNetwork(KnownNetwork network) {}
        };
    }
}
