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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link android.net.wifi.sharedconnectivity.service.SharedConnectivityService}.
 */
@SmallTest
public class SharedConnectivityServiceTest {

    @Mock
    Context mContext;

    static class FakeSharedConnectivityService extends SharedConnectivityService {
        public void attachBaseContext(Context context) {
            super.attachBaseContext(context);
        }

        @Override
        public void onConnectTetherNetwork(@NonNull TetherNetwork network) {}

        @Override
        public void onDisconnectTetherNetwork(@NonNull TetherNetwork network) {}

        @Override
        public void onConnectKnownNetwork(@NonNull KnownNetwork network) {}

        @Override
        public void onForgetKnownNetwork(@NonNull KnownNetwork network) {}
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
    }

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
        FakeSharedConnectivityService service = new FakeSharedConnectivityService();
        service.attachBaseContext(mContext);
        return service;
    }
}
