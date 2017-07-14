/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.net.NetworkStats;

/**
 * Interface that allows NetworkManagementService to query for tethering statistics.
 *
 * TODO: this does not really need to be an interface since Tethering runs in the same process
 * as NetworkManagementService. Consider refactoring Tethering to use direct access to
 * NetworkManagementService instead of using INetworkManagementService, and then deleting this
 * interface.
 *
 * @hide
 */
interface ITetheringStatsProvider {
    NetworkStats getTetherStats();
}
