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

package com.android.overlaytest;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.res.Resources;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

@RunWith(JUnit4.class)
@MediumTest
public class FabricatedOverlaysTest {
    private static final String TAG = "FabricatedOverlaysTest";
    private final String TEST_RESOURCE = "integer/overlaid";
    private final String TEST_OVERLAY_NAME = "Test";

    private Context mContext;
    private Resources mResources;
    private OverlayManager mOverlayManager;
    private int mUserId;
    private UserHandle mUserHandle;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mResources = mContext.getResources();
        mOverlayManager = mContext.getSystemService(OverlayManager.class);
        mUserId = UserHandle.myUserId();
        mUserHandle = UserHandle.of(mUserId);
    }

    @After
    public void tearDown() throws Exception {
        final OverlayManagerTransaction.Builder cleanUp = new OverlayManagerTransaction.Builder();
        mOverlayManager.getOverlayInfosForTarget(mContext.getPackageName(), mUserHandle).forEach(
                info -> {
                    if (info.isFabricated()) {
                        cleanUp.unregisterFabricatedOverlay(info.getOverlayIdentifier());
                    }
                });
        mOverlayManager.commit(cleanUp.build());
    }

    @Test
    public void testFabricatedOverlay() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlay)
                .build());

        OverlayInfo info = mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle);
        assertNotNull(info);
        assertFalse(info.isEnabled());

        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .setEnabled(overlay.getIdentifier(), true, mUserId)
                .build());

        info = mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle);
        assertNotNull(info);
        assertTrue(info.isEnabled());

        waitForResourceValue(1);
        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .unregisterFabricatedOverlay(overlay.getIdentifier())
                .build());

        waitForResourceValue(0);
    }

    @Test
    public void testRegisterEnableAtomic() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlay)
                .setEnabled(overlay.getIdentifier(), true, mUserId)
                .build());

        waitForResourceValue(1);
    }

    @Test
    public void testRegisterTwice() throws Exception {
        FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlay)
                .setEnabled(overlay.getIdentifier(), true, mUserId)
                .build());

        waitForResourceValue(1);
        overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 2)
                .build();

        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlay)
                .build());
        waitForResourceValue(2);
    }

    @Test
    public void testInvalidOwningPackageName() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                "android", TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        assertThrows(SecurityException.class, () ->
            mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                    .registerFabricatedOverlay(overlay)
                    .setEnabled(overlay.getIdentifier(), true, mUserId)
                    .build()));

        assertNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
    }

    @Test
    public void testInvalidOverlayName() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), "invalid@name", mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        assertThrows(SecurityException.class, () ->
                mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                        .registerFabricatedOverlay(overlay)
                        .setEnabled(overlay.getIdentifier(), true, mUserId)
                        .build()));

        assertNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
    }

    @Test
    public void testOverlayIdentifierLongest() throws Exception {
        final int maxLength = 255 - 11; // 11 reserved characters
        final String longestName = String.join("",
                Collections.nCopies(maxLength - mContext.getPackageName().length(), "a"));
        {
            FabricatedOverlay overlay = new FabricatedOverlay.Builder(mContext.getPackageName(),
                    longestName, mContext.getPackageName())
                    .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                    .build();

            mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                    .registerFabricatedOverlay(overlay)
                    .build());
            assertNotNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
        }
        {
            FabricatedOverlay overlay = new FabricatedOverlay.Builder(mContext.getPackageName(),
                    longestName + "a", mContext.getPackageName())
                    .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                    .build();

            assertThrows(SecurityException.class, () ->
                    mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                            .registerFabricatedOverlay(overlay)
                            .build()));

            assertNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
        }
    }

    @Test
    public void testInvalidResourceValues() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                "android", TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .setResourceValue("something", TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        assertThrows(SecurityException.class, () ->
                mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                        .registerFabricatedOverlay(overlay)
                        .setEnabled(overlay.getIdentifier(), true, mUserId)
                        .build()));

        assertNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
    }

    @Test
    public void testTransactionFailRollback() throws Exception {
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE, TypedValue.TYPE_INT_DEC, 1)
                .build();

        waitForResourceValue(0);
        assertThrows(SecurityException.class, () ->
                mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                        .registerFabricatedOverlay(overlay)
                        .setEnabled(overlay.getIdentifier(), true, mUserId)
                        .setEnabled(new OverlayIdentifier("not-valid"), true, mUserId)
                        .build()));

        assertNull(mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle));
    }

    void waitForResourceValue(final int expectedValue) throws TimeoutException {
        final long timeOutDuration = 10000;
        final long endTime = System.currentTimeMillis() + timeOutDuration;
        final String resourceName = TEST_RESOURCE;
        final int resourceId = mResources.getIdentifier(resourceName, "",
                mContext.getPackageName());
        int resourceValue = 0;
        while (System.currentTimeMillis() < endTime) {
            resourceValue = mResources.getInteger(resourceId);
            if (resourceValue == expectedValue) {
                return;
            }
        }
        final String paths = TextUtils.join(",", mResources.getAssets().getApkPaths());
        Log.w(TAG, "current paths: [" + paths + "]", new Throwable());
        throw new TimeoutException("Timed out waiting for '" + resourceName + "' value to equal '"
                + expectedValue + "': current value is '" + resourceValue + "'");
    }
}
