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

package com.android.systemui.statusbar.policy

import android.service.quickaccesswallet.QuickAccessWalletClient
import android.util.Log

import com.android.systemui.dagger.SysUISingleton

import javax.inject.Inject

/**
 * Check if the wallet service is available for use, and place the tile.
 */
@SysUISingleton
public class WalletControllerImpl @Inject constructor(
    private val quickAccessWalletClient: QuickAccessWalletClient
) : WalletController {

    companion object {
        private const val TAG = "WalletControllerImpl"
        internal const val QS_PRIORITY_POSITION = 3
    }

    /**
     * @return QS_PRIORITY_POSITION or null to indicate no tile should be set
     */
    override fun getWalletPosition(): Int? {
        return if (quickAccessWalletClient.isWalletServiceAvailable()) {
            Log.i(TAG, "Setting WalletTile position: $QS_PRIORITY_POSITION")
            QS_PRIORITY_POSITION
        } else {
            Log.i(TAG, "Setting WalletTile position: null")
            null
        }
    }
}
