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

package android.net.lowpan;
import android.net.lowpan.ILowpanInterface;
import android.net.lowpan.ILowpanManagerListener;

/** {@hide} */
interface ILowpanManager {

    /* Keep this in sync with Context.LOWPAN_SERVICE */
    const String LOWPAN_SERVICE_NAME = "lowpan";

    ILowpanInterface getInterface(@utf8InCpp String name);

    @utf8InCpp String[] getInterfaceList();

    void addListener(ILowpanManagerListener listener);
    void removeListener(ILowpanManagerListener listener);

    void addInterface(ILowpanInterface lowpan_interface);
    void removeInterface(ILowpanInterface lowpan_interface);
}
