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
 * limitations under the License.
 */
package android.net;

import static android.net.NetworkPolicyManager.MASK_ALL_NETWORKS;
import static android.net.NetworkPolicyManager.MASK_METERED_NETWORKS;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_ALLOW_METERED;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.RULE_REJECT_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.RULE_TEMPORARY_ALLOW_METERED;
import static android.net.NetworkPolicyManager.uidRulesToString;

import junit.framework.TestCase;

public class NetworkPolicyManagerTest extends TestCase {

    public void testUidRulesToString() {
        uidRulesToStringTest(RULE_NONE, "0 (NONE)");
        uidRulesToStringTest(RULE_ALLOW_METERED, "1 (ALLOW_METERED)");
        uidRulesToStringTest(RULE_TEMPORARY_ALLOW_METERED, "2 (TEMPORARY_ALLOW_METERED)");
        uidRulesToStringTest(RULE_REJECT_METERED, "4 (REJECT_METERED)");
        uidRulesToStringTest(RULE_ALLOW_ALL, "32 (ALLOW_ALL)");
        uidRulesToStringTest(RULE_REJECT_ALL, "64 (REJECT_ALL)");

        uidRulesToStringTest(RULE_ALLOW_METERED | RULE_ALLOW_ALL,
                "33 (ALLOW_METERED|ALLOW_ALL)");
        uidRulesToStringTest(RULE_ALLOW_METERED | RULE_REJECT_ALL,
                "65 (ALLOW_METERED|REJECT_ALL)");
        uidRulesToStringTest(RULE_TEMPORARY_ALLOW_METERED | RULE_ALLOW_ALL,
                "34 (TEMPORARY_ALLOW_METERED|ALLOW_ALL)");
        uidRulesToStringTest(RULE_TEMPORARY_ALLOW_METERED | RULE_REJECT_ALL,
                "66 (TEMPORARY_ALLOW_METERED|REJECT_ALL)");
        uidRulesToStringTest(RULE_REJECT_METERED | RULE_ALLOW_ALL,
                "36 (REJECT_METERED|ALLOW_ALL)");
        uidRulesToStringTest(RULE_REJECT_METERED | RULE_REJECT_ALL,
                "68 (REJECT_METERED|REJECT_ALL)");
    }

    private void uidRulesToStringTest(int uidRules, String expected) {
        final String actual = uidRulesToString(uidRules);
        assertEquals("Wrong string for uidRules " + uidRules, expected, actual);
    }

    public void testMeteredNetworksMask() {
        assertEquals(RULE_NONE, MASK_METERED_NETWORKS
                & RULE_NONE);

        assertEquals(RULE_ALLOW_METERED, MASK_METERED_NETWORKS
                & RULE_ALLOW_METERED);
        assertEquals(RULE_ALLOW_METERED, MASK_METERED_NETWORKS
                & (RULE_ALLOW_METERED | RULE_ALLOW_ALL));
        assertEquals(RULE_ALLOW_METERED, MASK_METERED_NETWORKS
                & (RULE_ALLOW_METERED | RULE_REJECT_ALL));

        assertEquals(RULE_TEMPORARY_ALLOW_METERED, MASK_METERED_NETWORKS
                & RULE_TEMPORARY_ALLOW_METERED);
        assertEquals(RULE_TEMPORARY_ALLOW_METERED, MASK_METERED_NETWORKS
                & (RULE_TEMPORARY_ALLOW_METERED | RULE_ALLOW_ALL));
        assertEquals(RULE_TEMPORARY_ALLOW_METERED, MASK_METERED_NETWORKS
                & (RULE_TEMPORARY_ALLOW_METERED | RULE_REJECT_ALL));

        assertEquals(RULE_REJECT_METERED, MASK_METERED_NETWORKS
                & RULE_REJECT_METERED);
        assertEquals(RULE_REJECT_METERED, MASK_METERED_NETWORKS
                & (RULE_REJECT_METERED | RULE_ALLOW_ALL));
        assertEquals(RULE_REJECT_METERED, MASK_METERED_NETWORKS
                & (RULE_REJECT_METERED | RULE_REJECT_ALL));
    }

    public void testAllNetworksMask() {
        assertEquals(RULE_NONE, MASK_ALL_NETWORKS
                & RULE_NONE);

        assertEquals(RULE_ALLOW_ALL, MASK_ALL_NETWORKS
                & RULE_ALLOW_ALL);
        assertEquals(RULE_ALLOW_ALL, MASK_ALL_NETWORKS
                & (RULE_ALLOW_ALL | RULE_ALLOW_METERED));
        assertEquals(RULE_ALLOW_ALL, MASK_ALL_NETWORKS
                & (RULE_ALLOW_ALL | RULE_TEMPORARY_ALLOW_METERED));
        assertEquals(RULE_ALLOW_ALL, MASK_ALL_NETWORKS
                & (RULE_ALLOW_ALL | RULE_REJECT_METERED));

        assertEquals(RULE_REJECT_ALL, MASK_ALL_NETWORKS
                & RULE_REJECT_ALL);
        assertEquals(RULE_REJECT_ALL, MASK_ALL_NETWORKS
                & (RULE_REJECT_ALL | RULE_ALLOW_METERED));
        assertEquals(RULE_REJECT_ALL, MASK_ALL_NETWORKS
                & (RULE_REJECT_ALL | RULE_TEMPORARY_ALLOW_METERED));
        assertEquals(RULE_REJECT_ALL, MASK_ALL_NETWORKS
                & (RULE_REJECT_ALL | RULE_REJECT_METERED));
    }
}
