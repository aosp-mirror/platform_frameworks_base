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

package com.android.server.security.advancedprotection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.advancedprotection.AdvancedProtectionFeature;
import android.security.advancedprotection.IAdvancedProtectionCallback;

import androidx.annotation.NonNull;

import com.android.server.security.advancedprotection.features.AdvancedProtectionHook;
import com.android.server.security.advancedprotection.features.AdvancedProtectionProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("VisibleForTests")
@RunWith(JUnit4.class)
public class AdvancedProtectionServiceTest {
    private AdvancedProtectionService mService;
    private FakePermissionEnforcer mPermissionEnforcer;
    private Context mContext;
    private AdvancedProtectionService.AdvancedProtectionStore mStore;
    private TestLooper mLooper;
    AdvancedProtectionFeature mFeature = new AdvancedProtectionFeature("test-id");

    @Before
    public void setup() throws Settings.SettingNotFoundException {
        mContext = mock(Context.class);
        mPermissionEnforcer = new FakePermissionEnforcer();
        mPermissionEnforcer.grant(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE);
        mPermissionEnforcer.grant(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);

        mStore = new AdvancedProtectionService.AdvancedProtectionStore(mContext) {
            private boolean mEnabled = false;

            @Override
            boolean retrieve() {
                return mEnabled;
            }

            @Override
            void store(boolean enabled) {
                this.mEnabled = enabled;
            }
        };

        mLooper = new TestLooper();

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, null, null);
    }

    @Test
    public void testToggleProtection() {
        mService.setAdvancedProtectionEnabled(true);
        assertTrue(mService.isAdvancedProtectionEnabled());

        mService.setAdvancedProtectionEnabled(false);
        assertFalse(mService.isAdvancedProtectionEnabled());
    }

    @Test
    public void testDisableProtection_byDefault() {
        assertFalse(mService.isAdvancedProtectionEnabled());
    }

    @Test
    public void testEnableProtection_withHook() {
        AtomicBoolean callbackCaptor = new AtomicBoolean(false);
        AdvancedProtectionHook hook =
                new AdvancedProtectionHook(mContext, true) {
                    @NonNull
                    @Override
                    public AdvancedProtectionFeature getFeature() {
                        return mFeature;
                    }

                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public void onAdvancedProtectionChanged(boolean enabled) {
                        callbackCaptor.set(enabled);
                    }
                };

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, hook, null);
        mService.setAdvancedProtectionEnabled(true);
        mLooper.dispatchNext();

        assertTrue(callbackCaptor.get());
    }

    @Test
    public void testEnableProtection_withFeature_notAvailable() {
        AtomicBoolean callbackCalledCaptor = new AtomicBoolean(false);
        AdvancedProtectionHook hook =
                new AdvancedProtectionHook(mContext, true) {
                    @NonNull
                    @Override
                    public AdvancedProtectionFeature getFeature() {
                        return mFeature;
                    }

                    @Override
                    public boolean isAvailable() {
                        return false;
                    }

                    @Override
                    public void onAdvancedProtectionChanged(boolean enabled) {
                        callbackCalledCaptor.set(true);
                    }
                };

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, hook, null);

        mService.setAdvancedProtectionEnabled(true);
        mLooper.dispatchNext();
        assertFalse(callbackCalledCaptor.get());
    }

    @Test
    public void testEnableProtection_withFeature_notCalledIfModeNotChanged() {
        AtomicBoolean callbackCalledCaptor = new AtomicBoolean(false);
        AdvancedProtectionHook hook =
                new AdvancedProtectionHook(mContext, true) {
                    @NonNull
                    @Override
                    public AdvancedProtectionFeature getFeature() {
                        return mFeature;
                    }

                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public void onAdvancedProtectionChanged(boolean enabled) {
                        callbackCalledCaptor.set(true);
                    }
                };

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, hook, null);
        mService.setAdvancedProtectionEnabled(true);
        mLooper.dispatchNext();
        assertTrue(callbackCalledCaptor.get());

        callbackCalledCaptor.set(false);
        mService.setAdvancedProtectionEnabled(true);
        mLooper.dispatchAll();
        assertFalse(callbackCalledCaptor.get());
    }

    @Test
    public void testRegisterCallback() throws RemoteException {
        AtomicBoolean callbackCaptor = new AtomicBoolean(false);
        IAdvancedProtectionCallback callback = new IAdvancedProtectionCallback.Stub() {
            @Override
            public void onAdvancedProtectionChanged(boolean enabled) {
                callbackCaptor.set(enabled);
            }
        };

        mService.setAdvancedProtectionEnabled(true);
        mLooper.dispatchAll();

        mService.registerAdvancedProtectionCallback(callback);
        mLooper.dispatchNext();
        assertTrue(callbackCaptor.get());

        mService.setAdvancedProtectionEnabled(false);
        mLooper.dispatchNext();

        assertFalse(callbackCaptor.get());
    }

    @Test
    public void testUnregisterCallback() throws RemoteException {
        AtomicBoolean callbackCalledCaptor = new AtomicBoolean(false);
        IAdvancedProtectionCallback callback = new IAdvancedProtectionCallback.Stub() {
            @Override
            public void onAdvancedProtectionChanged(boolean enabled) {
                callbackCalledCaptor.set(true);
            }
        };

        mService.setAdvancedProtectionEnabled(true);
        mService.registerAdvancedProtectionCallback(callback);
        mLooper.dispatchAll();
        callbackCalledCaptor.set(false);

        mService.unregisterAdvancedProtectionCallback(callback);
        mService.setAdvancedProtectionEnabled(false);

        mLooper.dispatchNext();
        assertFalse(callbackCalledCaptor.get());
    }

    @Test
    public void testGetFeatures() {
        AdvancedProtectionFeature feature1 = new AdvancedProtectionFeature("id-1");
        AdvancedProtectionFeature feature2 = new AdvancedProtectionFeature("id-2");
        AdvancedProtectionHook hook = new AdvancedProtectionHook(mContext, true) {
            @NonNull
            @Override
            public AdvancedProtectionFeature getFeature() {
                return feature1;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        AdvancedProtectionProvider provider = new AdvancedProtectionProvider() {
            @Override
            public List<AdvancedProtectionFeature> getFeatures() {
                return List.of(feature2);
            }
        };

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, hook, provider);
        List<AdvancedProtectionFeature> features = mService.getAdvancedProtectionFeatures();
        assertThat(features, containsInAnyOrder(feature1, feature2));
    }

    @Test
    public void testGetFeatures_featureNotAvailable() {
        AdvancedProtectionFeature feature1 = new AdvancedProtectionFeature("id-1");
        AdvancedProtectionFeature feature2 = new AdvancedProtectionFeature("id-2");
        AdvancedProtectionHook hook = new AdvancedProtectionHook(mContext, true) {
            @NonNull
            @Override
            public AdvancedProtectionFeature getFeature() {
                return feature1;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        AdvancedProtectionProvider provider = new AdvancedProtectionProvider() {
            @Override
            public List<AdvancedProtectionFeature> getFeatures() {
                return List.of(feature2);
            }
        };

        mService = new AdvancedProtectionService(mContext, mStore, mLooper.getLooper(),
                mPermissionEnforcer, hook, provider);
        List<AdvancedProtectionFeature> features = mService.getAdvancedProtectionFeatures();
        assertThat(features, containsInAnyOrder(feature2));
    }


    @Test
    public void testSetProtection_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.MANAGE_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.setAdvancedProtectionEnabled(true));
    }

    @Test
    public void testGetProtection_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.isAdvancedProtectionEnabled());
    }

    @Test
    public void testRegisterCallback_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.registerAdvancedProtectionCallback(
                new IAdvancedProtectionCallback.Default()));
    }

    @Test
    public void testUnregisterCallback_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.unregisterAdvancedProtectionCallback(
                new IAdvancedProtectionCallback.Default()));
    }
}
