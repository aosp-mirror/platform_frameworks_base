/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.overlaytest;

import static com.android.overlaytest.OverlayBaseTest.APP_OVERLAY_ONE_PKG;
import static com.android.overlaytest.OverlayBaseTest.APP_OVERLAY_TWO_PKG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
@MediumTest
public class TransactionTest {

    private Context mContext;
    private Resources mResources;
    private OverlayManager mOverlayManager;
    private int mUserId;
    private UserHandle mUserHandle;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mResources = mContext.getResources();
        mOverlayManager = mContext.getSystemService(OverlayManager.class);
        mUserId = UserHandle.myUserId();
        mUserHandle = UserHandle.of(mUserId);

        LocalOverlayManager.toggleOverlaysAndWait(
                new OverlayIdentifier[]{},
                new OverlayIdentifier[]{APP_OVERLAY_ONE_PKG, APP_OVERLAY_TWO_PKG});
    }

    @Test
    public void testValidTransaction() throws Exception {
        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, false, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, false, mUserId);

        OverlayManagerTransaction t = new OverlayManagerTransaction.Builder()
                .setEnabled(APP_OVERLAY_ONE_PKG, true)
                .setEnabled(APP_OVERLAY_TWO_PKG, true)
                .build();
        mOverlayManager.commit(t);

        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, true, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, true, mUserId);
        List<OverlayInfo> ois =
                mOverlayManager.getOverlayInfosForTarget("com.android.overlaytest", mUserHandle);
        assertEquals(ois.size(), 2);
        assertEquals(ois.get(0).getOverlayIdentifier(), APP_OVERLAY_ONE_PKG);
        assertEquals(ois.get(1).getOverlayIdentifier(), APP_OVERLAY_TWO_PKG);

        OverlayManagerTransaction t2 = new OverlayManagerTransaction.Builder()
                .setEnabled(APP_OVERLAY_TWO_PKG, true)
                .setEnabled(APP_OVERLAY_ONE_PKG, true)
                .build();
        mOverlayManager.commit(t2);

        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, true, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, true, mUserId);
        List<OverlayInfo> ois2 =
                mOverlayManager.getOverlayInfosForTarget("com.android.overlaytest", mUserHandle);
        assertEquals(ois2.size(), 2);
        assertEquals(ois2.get(0).getOverlayIdentifier(), APP_OVERLAY_TWO_PKG);
        assertEquals(ois2.get(1).getOverlayIdentifier(), APP_OVERLAY_ONE_PKG);

        OverlayManagerTransaction t3 = new OverlayManagerTransaction.Builder()
                .setEnabled(APP_OVERLAY_TWO_PKG, false)
                .build();
        mOverlayManager.commit(t3);

        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, true, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, false, mUserId);
        List<OverlayInfo> ois3 =
                mOverlayManager.getOverlayInfosForTarget("com.android.overlaytest", mUserHandle);
        assertEquals(ois3.size(), 2);
        assertEquals(ois3.get(0).getOverlayIdentifier(), APP_OVERLAY_TWO_PKG);
        assertEquals(ois3.get(1).getOverlayIdentifier(), APP_OVERLAY_ONE_PKG);
    }

    @Test
    public void testInvalidRequestHasNoEffect() {
        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, false, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, false, mUserId);

        OverlayManagerTransaction t = new OverlayManagerTransaction.Builder()
                .setEnabled(APP_OVERLAY_ONE_PKG, true)
                .setEnabled(new OverlayIdentifier("does-not-exist"), true)
                .setEnabled(APP_OVERLAY_TWO_PKG, true)
                .build();
        assertThrows(SecurityException.class, () -> mOverlayManager.commit(t));

        assertOverlayIsEnabled(APP_OVERLAY_ONE_PKG, false, mUserId);
        assertOverlayIsEnabled(APP_OVERLAY_TWO_PKG, false, mUserId);
    }

    private void assertOverlayIsEnabled(final OverlayIdentifier overlay, boolean enabled,
            int userId) {
        final OverlayInfo oi = mOverlayManager.getOverlayInfo(overlay, UserHandle.of(userId));
        assertNotNull(oi);
        assertEquals(enabled, oi.isEnabled());
    }
}
