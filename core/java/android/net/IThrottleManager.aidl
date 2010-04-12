/**
 * Copyright (c) 2010, The Android Open Source Project
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

import android.os.IBinder;

/**
 * Interface that answers queries about data transfer amounts and throttling
 */
/** {@hide} */
interface IThrottleManager
{
    long getByteCount(String iface, int dir, int period, int ago);

    int getThrottle(String iface);

    long getResetTime(String iface);

    long getPeriodStartTime(String iface);

    long getCliffThreshold(String iface, int cliff);

    int getCliffLevel(String iface, int cliff);

    String getHelpUri();
}
