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
package android.net.vcn;

public class VcnUnderlyingNetworkTemplateTestBase {
    // Public for use in NetworkPriorityClassifierTest
    public static final int TEST_MIN_ENTRY_UPSTREAM_BANDWIDTH_KBPS = 200;
    public static final int TEST_MIN_EXIT_UPSTREAM_BANDWIDTH_KBPS = 100;
    public static final int TEST_MIN_ENTRY_DOWNSTREAM_BANDWIDTH_KBPS = 400;
    public static final int TEST_MIN_EXIT_DOWNSTREAM_BANDWIDTH_KBPS = 300;
}
