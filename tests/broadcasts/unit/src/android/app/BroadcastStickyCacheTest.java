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

package android.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.IpcDataCache;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.annotations.Keep;
import com.android.modules.utils.testing.ExtendedMockitoRule;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(JUnitParamsRunner.class)
public class BroadcastStickyCacheTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(IpcDataCache.class)
            .mockStatic(ActivityManager.class)
            .build();

    @Mock
    private IActivityManager mActivityManagerMock;

    @Mock
    private IApplicationThread mIApplicationThreadMock;

    @Keep
    private static Object stickyBroadcastList() {
        return BroadcastStickyCache.STICKY_BROADCAST_ACTIONS;
    }

    @Before
    public void setUp() {
        BroadcastStickyCache.clearCacheForTest();

        doNothing().when(() -> IpcDataCache.invalidateCache(anyString(), anyString()));
    }

    @Test
    @DisableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void useCache_flagDisabled_returnsFalse() {
        assertFalse(BroadcastStickyCache.useCache(new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void useCache_nullFilter_returnsFalse() {
        assertFalse(BroadcastStickyCache.useCache(null));
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void useCache_filterWithoutAction_returnsFalse() {
        assertFalse(BroadcastStickyCache.useCache(new IntentFilter()));
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void useCache_filterWithoutStickyBroadcastAction_returnsFalse() {
        assertFalse(BroadcastStickyCache.useCache(new IntentFilter(Intent.ACTION_BOOT_COMPLETED)));
    }

    @Test
    @DisableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void invalidateCache_flagDisabled_cacheNotInvalidated() {
        final String apiName = BroadcastStickyCache.sActionApiNameMap.get(
                AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);

        BroadcastStickyCache.invalidateCache(
                AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);

        ExtendedMockito.verify(
                () -> IpcDataCache.invalidateCache(eq(IpcDataCache.MODULE_SYSTEM), eq(apiName)),
                times(0));
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void invalidateCache_broadcastNotSticky_cacheNotInvalidated() {
        BroadcastStickyCache.invalidateCache(Intent.ACTION_AIRPLANE_MODE_CHANGED);

        ExtendedMockito.verify(
                () -> IpcDataCache.invalidateCache(eq(IpcDataCache.MODULE_SYSTEM), anyString()),
                times(0));
    }

    @Test
    @EnableFlags(Flags.FLAG_USE_STICKY_BCAST_CACHE)
    public void invalidateCache_withStickyBroadcast_cacheInvalidated() {
        final String apiName = BroadcastStickyCache.sActionApiNameMap.get(
                Intent.ACTION_BATTERY_CHANGED);

        BroadcastStickyCache.invalidateCache(Intent.ACTION_BATTERY_CHANGED);

        ExtendedMockito.verify(
                () -> IpcDataCache.invalidateCache(eq(IpcDataCache.MODULE_SYSTEM), eq(apiName)),
                times(1));
    }

    @Test
    public void invalidateAllCaches_cacheInvalidated() {
        BroadcastStickyCache.invalidateAllCaches();

        for (int i = BroadcastStickyCache.sActionApiNameMap.size() - 1; i > -1; i--) {
            final String apiName = BroadcastStickyCache.sActionApiNameMap.valueAt(i);
            ExtendedMockito.verify(() -> IpcDataCache.invalidateCache(anyString(),
                    eq(apiName)), times(1));
        }
    }

    @Test
    @Parameters(method = "stickyBroadcastList")
    public void getIntent_createNewCache_verifyRegisterReceiverIsCalled(String action)
            throws RemoteException {
        setActivityManagerMock(action);
        final IntentFilter filter = new IntentFilter(action);
        final Intent intent = queryIntent(filter);

        assertNotNull(intent);
        assertEquals(intent.getAction(), action);
        verify(mActivityManagerMock, times(1)).registerReceiverWithFeature(
                eq(mIApplicationThreadMock), anyString(), anyString(), anyString(), any(),
                eq(filter), anyString(), anyInt(), anyInt());
    }

    @Test
    public void getIntent_querySameValueTwice_verifyRegisterReceiverIsCalledOnce()
            throws RemoteException {
        setActivityManagerMock(Intent.ACTION_DEVICE_STORAGE_LOW);
        final Intent intent = queryIntent(new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        final Intent cachedIntent = queryIntent(new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));

        assertNotNull(intent);
        assertEquals(intent.getAction(), Intent.ACTION_DEVICE_STORAGE_LOW);
        assertNotNull(cachedIntent);
        assertEquals(cachedIntent.getAction(), Intent.ACTION_DEVICE_STORAGE_LOW);

        verify(mActivityManagerMock, times(1)).registerReceiverWithFeature(
                eq(mIApplicationThreadMock), anyString(), anyString(), anyString(), any(),
                any(), anyString(), anyInt(), anyInt());
    }

    @Test
    public void getIntent_queryActionTwiceWithNullResult_verifyRegisterReceiverIsCalledOnce()
            throws RemoteException {
        setActivityManagerMock(null);
        final Intent intent = queryIntent(new IntentFilter(Intent.ACTION_DEVICE_STORAGE_FULL));
        final Intent cachedIntent = queryIntent(
                new IntentFilter(Intent.ACTION_DEVICE_STORAGE_FULL));

        assertNull(intent);
        assertNull(cachedIntent);

        verify(mActivityManagerMock, times(1)).registerReceiverWithFeature(
                eq(mIApplicationThreadMock), anyString(), anyString(), anyString(), any(),
                any(), anyString(), anyInt(), anyInt());
    }

    @Test
    public void getIntent_querySameActionWithDifferentFilter_verifyRegisterReceiverCalledTwice()
            throws RemoteException {
        setActivityManagerMock(Intent.ACTION_DEVICE_STORAGE_LOW);
        final IntentFilter filter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        final Intent intent = queryIntent(filter);

        final IntentFilter newFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        newFilter.addDataScheme("file");
        final Intent newIntent = queryIntent(newFilter);

        assertNotNull(intent);
        assertEquals(intent.getAction(), Intent.ACTION_DEVICE_STORAGE_LOW);
        assertNotNull(newIntent);
        assertEquals(newIntent.getAction(), Intent.ACTION_DEVICE_STORAGE_LOW);

        verify(mActivityManagerMock, times(1)).registerReceiverWithFeature(
                eq(mIApplicationThreadMock), anyString(), anyString(), anyString(), any(),
                eq(filter), anyString(), anyInt(), anyInt());

        verify(mActivityManagerMock, times(1)).registerReceiverWithFeature(
                eq(mIApplicationThreadMock), anyString(), anyString(), anyString(), any(),
                eq(newFilter), anyString(), anyInt(), anyInt());
    }

    private Intent queryIntent(IntentFilter filter) {
        return BroadcastStickyCache.getIntent(
                mIApplicationThreadMock,
                "android",
                "android",
                filter,
                "system",
                0,
                0
        );
    }

    private void setActivityManagerMock(String action) throws RemoteException {
        when(ActivityManager.getService()).thenReturn(mActivityManagerMock);
        when(mActivityManagerMock.registerReceiverWithFeature(any(), anyString(),
                anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyInt())).thenReturn(action != null ? new Intent(action) : null);
    }
}
