/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.utils;

import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.settingslib.NetworkPolicyEditor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkPolicyEditorTest {
    private static final long MAX_LIMIT_BYTES = 500000;
    private static final long TEST_LIMIT_BYTES = 2500;
    private static final long[] WARNING_BYTES_LIST = {100, 1000, 2000, 3000, 40000};

    private NetworkTemplate mNetworkTemplate;
    private NetworkPolicyEditor mNetworkPolicyEditor;

    @Before
    public void setUp() {
        mNetworkTemplate = NetworkTemplate.buildTemplateMobileAll("123456789123456");
        NetworkPolicyManager policyManager = NetworkPolicyManager.from(InstrumentationRegistry
                .getContext());
        mNetworkPolicyEditor = new NetworkPolicyEditor(policyManager);
    }

    @Test
    public void setPolicyWarningBytes_withoutLimit_shouldNotCapWarningBytes() {
        // Set the limit to disable so we can change the warning bytes freely
        mNetworkPolicyEditor.setPolicyLimitBytes(mNetworkTemplate, NetworkPolicy.LIMIT_DISABLED);

        for (int i = 0; i < WARNING_BYTES_LIST.length; i++) {
            mNetworkPolicyEditor.setPolicyWarningBytes(mNetworkTemplate, WARNING_BYTES_LIST[i]);
            assertEquals(WARNING_BYTES_LIST[i],
                    mNetworkPolicyEditor.getPolicyWarningBytes(mNetworkTemplate));
        }
    }

    @Test
    public void setPolicyWarningBytes_withLimit_shouldCapWarningBytes() {
        // Set the limit bytes, so warning bytes cannot exceed the limit bytes.
        mNetworkPolicyEditor.setPolicyLimitBytes(mNetworkTemplate, TEST_LIMIT_BYTES);

        for (int i = 0; i < WARNING_BYTES_LIST.length; i++) {
            mNetworkPolicyEditor.setPolicyWarningBytes(mNetworkTemplate, WARNING_BYTES_LIST[i]);
            long expectedWarningBytes = Math.min(WARNING_BYTES_LIST[i], TEST_LIMIT_BYTES);
            assertEquals(expectedWarningBytes,
                    mNetworkPolicyEditor.getPolicyWarningBytes(mNetworkTemplate));
        }
    }

    @Test
    public void setPolicyLimitBytes_warningBytesSmallerThanLimit_shouldNotCapWarningBytes() {
        long testWarningBytes = MAX_LIMIT_BYTES / 2;

        mNetworkPolicyEditor.setPolicyLimitBytes(mNetworkTemplate, MAX_LIMIT_BYTES);
        mNetworkPolicyEditor.setPolicyWarningBytes(mNetworkTemplate, testWarningBytes);

        assertEquals(MAX_LIMIT_BYTES, mNetworkPolicyEditor.getPolicyLimitBytes(mNetworkTemplate));
        assertEquals(testWarningBytes,
                mNetworkPolicyEditor.getPolicyWarningBytes(mNetworkTemplate));
    }

    @Test
    public void setPolicyLimitBytes_warningBytesBiggerThanLimit_shouldCapWarningBytes() {
        long testWarningBytes = TEST_LIMIT_BYTES * 2;

        mNetworkPolicyEditor.setPolicyLimitBytes(mNetworkTemplate, MAX_LIMIT_BYTES);
        mNetworkPolicyEditor.setPolicyWarningBytes(mNetworkTemplate, testWarningBytes);
        mNetworkPolicyEditor.setPolicyLimitBytes(mNetworkTemplate, TEST_LIMIT_BYTES);

        assertEquals(TEST_LIMIT_BYTES, mNetworkPolicyEditor.getPolicyLimitBytes(mNetworkTemplate));
        long expectedWarningBytes = Math.min(testWarningBytes, TEST_LIMIT_BYTES);
        assertEquals(expectedWarningBytes,
                mNetworkPolicyEditor.getPolicyWarningBytes(mNetworkTemplate));
    }

}
