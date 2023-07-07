/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.vpndialogs;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class VpnDialogTest {
    private ActivityScenario<ConfirmDialog> mActivityScenario;

    @SuppressWarnings("StaticMockMember")
    @Mock
    private static PackageManager sPm;

    @SuppressWarnings("StaticMockMember")
    @Mock
    private static VpnManager sVm;

    @Mock
    private ApplicationInfo mAi;

    private static final String VPN_APP_NAME = "VpnApp";
    private static final String VPN_APP_PACKAGE_NAME = "com.android.vpndialogs.VpnDialogTest";
    private static final String VPN_LABEL_CONTAINS_HTML_TAG =
            "<b><a href=\"https://www.malicious.vpn.app.com\">Google Play</a>";
    private static final String VPN_LABEL_CONTAINS_HTML_TAG_AND_VIOLATE_LENGTH_RESTRICTION =
            "<b><a href=\"https://www.malicious.vpn.app.com\">Google Play</a></b>"
            + " Wants to connect the network. <br></br><br></br><br></br><br></br><br></br>"
            + " <br></br><br></br><br></br><br></br><br></br><br></br><br></br><br></br> Deny it?";
    private static final String VPN_LABEL_VIOLATES_LENGTH_RESTRICTION = "This is a VPN label"
            + " which violates the length restriction. The length restriction here are 150 code"
            + " points. So the VPN label should be sanitized, and shows the package name to the"
            + " user.";

    public static class InstrumentedConfirmDialog extends ConfirmDialog {
        @Override
        public PackageManager getPackageManager() {
            return sPm;
        }

        @Override
        public @Nullable Object getSystemService(@ServiceName @NonNull String name) {
            switch (name) {
                case Context.VPN_MANAGEMENT_SERVICE:
                    return sVm;
                default:
                    return super.getSystemService(name);
            }
        }

        @Override
        public String getCallingPackage() {
            return VPN_APP_PACKAGE_NAME;
        }
    }

    private void launchActivity() {
        final Context context = getInstrumentation().getContext();
        mActivityScenario = ActivityScenario.launch(
                new Intent(context, InstrumentedConfirmDialog.class));
    }

    @Test
    public void testGetSanitizedVpnLabel_withNormalCase() throws Exception {
        // Test the normal case that the VPN label showed in the VpnDialog is the app name.
        doReturn(VPN_APP_NAME).when(mAi).loadLabel(sPm);
        launchActivity();
        mActivityScenario.onActivity(activity -> {
            assertTrue(activity.getWarningText().toString().contains(VPN_APP_NAME));
        });
    }

    private void verifySanitizedVpnLabel(String originalLabel) {
        doReturn(originalLabel).when(mAi).loadLabel(sPm);
        launchActivity();
        mActivityScenario.onActivity(activity -> {
            // The VPN label was sanitized because violating length restriction or having a html
            // tag, so the warning message will contain the package name.
            assertTrue(activity.getWarningText().toString().contains(activity.getCallingPackage()));
            // Also, the length of sanitized VPN label shouldn't longer than MAX_VPN_LABEL_LENGTH
            // and it shouldn't contain html tag.
            final String sanitizedVpnLabel =
                    activity.getSanitizedVpnLabel(originalLabel, VPN_APP_PACKAGE_NAME);
            assertTrue(sanitizedVpnLabel.codePointCount(0, sanitizedVpnLabel.length())
                    < ConfirmDialog.MAX_VPN_LABEL_LENGTH);
            assertFalse(sanitizedVpnLabel.contains("<b>"));
        });
    }

    @Test
    public void testGetSanitizedVpnLabel_withHtmlTag() throws Exception {
        // Test the case that the VPN label was sanitized because there is a html tag.
        verifySanitizedVpnLabel(VPN_LABEL_CONTAINS_HTML_TAG);
    }

    @Test
    public void testGetSanitizedVpnLabel_withHtmlTagAndViolateLengthRestriction() throws Exception {
        // Test the case that the VPN label was sanitized because there is a html tag.
        verifySanitizedVpnLabel(VPN_LABEL_CONTAINS_HTML_TAG_AND_VIOLATE_LENGTH_RESTRICTION);
    }

    @Test
    public void testGetSanitizedVpnLabel_withLengthRestriction() throws Exception {
        // Test the case that the VPN label was sanitized because hitting the length restriction.
        verifySanitizedVpnLabel(VPN_LABEL_VIOLATES_LENGTH_RESTRICTION);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(false).when(sVm).prepareVpn(anyString(), anyString(), anyInt());
        doReturn(null).when(sPm).queryIntentServices(any(), anyInt());
        doReturn(mAi).when(sPm).getApplicationInfo(anyString(), anyInt());
    }
}
