/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.net.LinkAddress;
import android.net.TestNetworkInterface;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/**
 * Interface that allows for creation and management of test-only networks.
 *
 * @hide
 */
interface ITestNetworkManager
{
    TestNetworkInterface createTunInterface(in LinkAddress[] linkAddrs);
    TestNetworkInterface createTapInterface();

    void setupTestNetwork(in String iface, in IBinder binder);

    void teardownTestNetwork(int netId);
}
