/*
 * Copyright 2020 The Android Open Source Project
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

package android.service.quickaccesswallet;

import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.WalletServiceEvent;

/**
 * Interface to receive the result of requests to the wallet application.
 *
 * @hide
 */
interface IQuickAccessWalletServiceCallbacks {
    // Called in response to onWalletCardsRequested on success. May only be called once per request.
    oneway void onGetWalletCardsSuccess(in GetWalletCardsResponse response);
    // Called in response to onWalletCardsRequested when an error occurs. May only be called once
    // per request.
    oneway void onGetWalletCardsFailure(in GetWalletCardsError error);
    // Called in response to registerWalletServiceEventListener. May be called multiple times as
    // long as the event listener is registered.
    oneway void onWalletServiceEvent(in WalletServiceEvent event);
}