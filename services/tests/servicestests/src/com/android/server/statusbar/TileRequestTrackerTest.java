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

package com.android.server.statusbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class TileRequestTrackerTest {

    private static final String TEST_PACKAGE = "test_pkg";
    private static final String TEST_SERVICE = "test_svc";
    private static final String TEST_SERVICE_OTHER = "test_svc_other";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PACKAGE,
            TEST_SERVICE);
    private static final ComponentName TEST_COMPONENT_OTHER = new ComponentName(TEST_PACKAGE,
            TEST_SERVICE_OTHER);
    private static final ComponentName TEST_COMPONENT_OTHER_PACKAGE = new ComponentName("other",
            TEST_SERVICE);
    private static final int USER_ID = 0;
    private static final int USER_ID_OTHER = 10;
    private static final int APP_UID = 12345;
    private static final int USER_UID = UserHandle.getUid(USER_ID, APP_UID);
    private static final int USER_OTHER_UID = UserHandle.getUid(USER_ID_OTHER, APP_UID);

    @Rule
    public final NoBroadcastContextWrapper mContext =
            new NoBroadcastContextWrapper(InstrumentationRegistry.getContext());

    private TileRequestTracker mTileRequestTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTileRequestTracker = new TileRequestTracker(mContext);
    }

    @Test
    public void testBroadcastReceiverRegistered() {
        NoBroadcastContextWrapper.BroadcastReceiverRegistration reg = getReceiverRegistration();

        assertEquals(UserHandle.ALL, reg.mUser);
        assertNull(reg.mBroadcastPermission);
        assertNotNull(reg.mReceiver);

        IntentFilter filter = reg.mIntentFilter;
        assertEquals(2, filter.countActions());
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGE_REMOVED));
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGE_DATA_CLEARED));
        assertTrue(filter.hasDataScheme("package"));
    }

    @Test
    public void testNoDenialsFromStart() {
        // Certainly not an exhaustive test
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID_OTHER, TEST_COMPONENT));
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT_OTHER));
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID_OTHER, TEST_COMPONENT_OTHER));
    }

    @Test
    public void testNoDenialBeforeMax() {
        for (int i = 1; i < TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }

        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
    }

    @Test
    public void testDenialOnMax() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }
        assertTrue(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
    }

    @Test
    public void testDenialPerUser() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }

        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID_OTHER, TEST_COMPONENT));
    }

    @Test
    public void testDenialPerComponent() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }

        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT_OTHER));
    }

    @Test
    public void testPackageUninstallRemovesDenials_allComponents() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT_OTHER);
        }

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, USER_UID);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE));
        getReceiverRegistration().mReceiver.onReceive(mContext, intent);

        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT_OTHER));
    }

    @Test
    public void testPackageUninstallRemoveDenials_differentUsers() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
            mTileRequestTracker.addDenial(USER_ID_OTHER, TEST_COMPONENT);
        }

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, USER_OTHER_UID);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE));
        getReceiverRegistration().mReceiver.onReceive(mContext, intent);

        // User 0 package was not removed
        assertTrue(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
        // User 10 package was removed
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID_OTHER, TEST_COMPONENT));
    }

    @Test
    public void testPackageUninstallRemoveDenials_differentPackages() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT_OTHER_PACKAGE);
        }

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_UID, USER_UID);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE));
        getReceiverRegistration().mReceiver.onReceive(mContext, intent);

        // Package TEST_PACKAGE removed
        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
        // Package "other" not removed
        assertTrue(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT_OTHER_PACKAGE));
    }

    @Test
    public void testPackageUpdateDoesntRemoveDenials() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }

        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        intent.putExtra(Intent.EXTRA_UID, USER_UID);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE));
        getReceiverRegistration().mReceiver.onReceive(mContext, intent);

        assertTrue(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
    }

    @Test
    public void testClearPackageDataRemovesDenials() {
        for (int i = 1; i <= TileRequestTracker.MAX_NUM_DENIALS; i++) {
            mTileRequestTracker.addDenial(USER_ID, TEST_COMPONENT);
        }

        Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED);
        intent.putExtra(Intent.EXTRA_UID, USER_UID);
        intent.setData(Uri.parse("package:" + TEST_PACKAGE));
        getReceiverRegistration().mReceiver.onReceive(mContext, intent);

        assertFalse(mTileRequestTracker.shouldBeDenied(USER_ID, TEST_COMPONENT));
    }

    private NoBroadcastContextWrapper.BroadcastReceiverRegistration getReceiverRegistration() {
        assertEquals(1, mContext.mRegistrationList.size());
        return mContext.mRegistrationList.get(0);
    }
}
