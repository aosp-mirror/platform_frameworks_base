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

package com.android.overlaytest.overlayresapp;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.fail;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.TypedValue;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class OverlayResTest {
    // Default timeout value
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final String TEST_OVERLAY_NAME = "Test";
    private static final String TEST_RESOURCE_INTEGER = "integer/test_integer";
    private static final String TEST_RESOURCE_STRING = "string/test_string";
    private static final int TEST_INTEGER = 0;
    private static final int TEST_FRRO_INTEGER = 1;
    private static final String TEST_STRING = "Test String";
    private static final String TEST_FRRO_STRING = "FRRO Test String";
    private OverlayResActivity mActivity;
    private Context mContext;
    private OverlayManager mOverlayManager;
    private int mUserId;
    private UserHandle mUserHandle;

    @Rule
    public ActivityScenarioRule<OverlayResActivity> mActivityScenarioRule =
            new ActivityScenarioRule<>(OverlayResActivity.class);

    @Before
    public void setUp() {
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            assertThat(activity).isNotNull();
            mActivity = activity;
        });
        mContext = mActivity.getApplicationContext();
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
    public void overlayRes_onConfigurationChanged() throws Exception {
        final CountDownLatch latch1 = new CountDownLatch(1);
        mActivity.setConfigurationChangedCallback(() -> {
            Resources r = mActivity.getApplicationContext().getResources();
            assertThat(r.getInteger(R.integer.test_integer)).isEqualTo(TEST_FRRO_INTEGER);
            assertThat(r.getString(R.string.test_string)).isEqualTo(TEST_FRRO_STRING);
            latch1.countDown();
        });

        // Create and enable FRRO
        final FabricatedOverlay overlay = new FabricatedOverlay.Builder(
                mContext.getPackageName(), TEST_OVERLAY_NAME, mContext.getPackageName())
                .setResourceValue(TEST_RESOURCE_INTEGER, TypedValue.TYPE_INT_DEC, TEST_FRRO_INTEGER)
                .setResourceValue(TEST_RESOURCE_STRING, TypedValue.TYPE_STRING, TEST_FRRO_STRING)
                .build();

        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .registerFabricatedOverlay(overlay)
                .build());

        OverlayInfo info = mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle);
        assertNotNull(info);
        assertThat(info.isEnabled()).isFalse();

        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .setEnabled(overlay.getIdentifier(), true, mUserId)
                .build());

        info = mOverlayManager.getOverlayInfo(overlay.getIdentifier(), mUserHandle);
        assertNotNull(info);
        assertThat(info.isEnabled()).isTrue();

        if (!latch1.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("Fail to wait configuration changes for build and enabling frro, "
                    + "onConfigurationChanged() has not been invoked.");
        }

        final CountDownLatch latch2 = new CountDownLatch(1);
        mActivity.setConfigurationChangedCallback(() -> {
            Resources r = mActivity.getApplicationContext().getResources();
            assertThat(r.getInteger(R.integer.test_integer)).isEqualTo(TEST_INTEGER);
            assertThat(r.getString(R.string.test_string)).isEqualTo(TEST_STRING);
            latch2.countDown();
        });

        // unregister FRRO
        mOverlayManager.commit(new OverlayManagerTransaction.Builder()
                .unregisterFabricatedOverlay(overlay.getIdentifier())
                .build());

        if (!latch2.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("Fail to wait configuration changes after unregister frro,"
                    + " onConfigurationChanged() has not been invoked.");
        }
    }
}
