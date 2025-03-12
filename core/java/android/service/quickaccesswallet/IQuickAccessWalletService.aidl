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

import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.IQuickAccessWalletServiceCallbacks;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletServiceEvent;
import android.service.quickaccesswallet.WalletServiceEventListenerRequest;

/**
 * Implemented by QuickAccessWalletService in the payment application
 *
 * @hide
 */
interface IQuickAccessWalletService {
    // Request to get cards, which should be provided using the callback.
    oneway void onWalletCardsRequested(
        in GetWalletCardsRequest request, in IQuickAccessWalletServiceCallbacks callback);
    // Indicates that a card has been selected.
    oneway void onWalletCardSelected(in SelectWalletCardRequest request);
    // Sent when the wallet is dismissed or closed.
    oneway void onWalletDismissed();
    // Register an event listener
    oneway void registerWalletServiceEventListener(
        in WalletServiceEventListenerRequest request,
        in IQuickAccessWalletServiceCallbacks callback);
    // Unregister an event listener
    oneway void unregisterWalletServiceEventListener(in WalletServiceEventListenerRequest request);
    // Request to get a PendingIntent to launch an activity from which the user can manage their cards.
    oneway void onTargetActivityIntentRequested(in IQuickAccessWalletServiceCallbacks callbacks);
    // Request to get a PendingIntent to launch an activity, triggered when the user performs a gesture.
    oneway void onGestureTargetActivityIntentRequested(in IQuickAccessWalletServiceCallbacks callbacks);
   }