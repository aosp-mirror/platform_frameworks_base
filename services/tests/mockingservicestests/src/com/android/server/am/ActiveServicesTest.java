/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.am;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.am.ActiveServices.FGS_BG_START_USE_EXEMPTION_LIST_CHANGE_ID;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import android.app.compat.CompatChanges;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;



@RunWith(AndroidJUnit4.class)
public class ActiveServicesTest {

    private MockitoSession mMockingSession;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(CompatChanges.class)
                .startMocking();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void checkPackageExempted(String pkg, int uid, boolean expected) {
        assertEquals("Package=" + pkg + " uid=" + uid,
                expected, ActiveServices.isPackageExemptedFromFgsRestriction(pkg, uid));
    }

    @Test
    public void isPackageExemptedFromFgsRestriction() {
        // Compat changes are enabled by default.
        when(CompatChanges.isChangeEnabled(anyLong(), anyInt())).thenReturn(true);

        checkPackageExempted("", 1, false);
        checkPackageExempted("abc", 1, false);
        checkPackageExempted("com.random", 1, false);

        // This package is exempted but not its subpackages.
        checkPackageExempted("com.google.pixel.exo.bootstrapping", 1, true);
        checkPackageExempted("com.google.pixel.exo.bootstrapping.subpackage", 1, false);

        // Subpackages are also exempted.
        checkPackageExempted("com.android.webview", 1, true);
        checkPackageExempted("com.android.webview.beta", 1, true);
        checkPackageExempted("com.chrome", 1, true);
        checkPackageExempted("com.chrome.canary", 1, true);

        checkPackageExempted("com.android.webviewx", 1, false);

        // Now toggle the compat ID for a specific UID.
        when(CompatChanges.isChangeEnabled(FGS_BG_START_USE_EXEMPTION_LIST_CHANGE_ID, 10))
                .thenReturn(false);
        // Exempted package, but compat id is disabled for the UID.
        checkPackageExempted("com.android.webview", 10, false);

        // Exempted package, but compat id is still enabled for the UID.
        checkPackageExempted("com.android.webview", 11, true);
    }
}
