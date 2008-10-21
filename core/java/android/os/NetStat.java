/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

/** @hide */
public class NetStat{

    /**
     * Get total number of tx packets sent through ppp0
     *
     * @return number of Tx packets through ppp0
     */

    public native static int netStatGetTxPkts();

    /**
     *  Get total number of rx packets received through ppp0
     *
     * @return number of Rx packets through ppp0
     */
    public native static int netStatGetRxPkts();

      /**
     *  Get total number of tx bytes received through ppp0
     *
     * @return number of Tx bytes through ppp0
     */
    public native static int netStatGetTxBytes();

    /**
     *  Get total number of rx bytes received through ppp0
     *
     * @return number of Rx bytes through ppp0
     */
    public native static int netStatGetRxBytes();

}
