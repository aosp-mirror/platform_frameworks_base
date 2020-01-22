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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;

import java.util.function.Consumer;

/**
 * Facilitates accessing cards from the {@link QuickAccessWalletService}.
 *
 * @hide
 */
public interface QuickAccessWalletClient {

    /**
     * Create a client for accessing wallet cards from the {@link QuickAccessWalletService}. If the
     * service is unavailable, {@link #isWalletServiceAvailable()} will return false.
     */
    @NonNull
    static QuickAccessWalletClient create(@NonNull Context context) {
        return new QuickAccessWalletClientImpl(context);
    }

    /**
     * @return true if the {@link QuickAccessWalletService} is available.
     */
    boolean isWalletServiceAvailable();

    /**
     * Get wallet cards from the {@link QuickAccessWalletService}.
     */
    void getWalletCards(
            @NonNull GetWalletCardsRequest request,
            @NonNull Consumer<GetWalletCardsResponse> onSuccessListener,
            @NonNull Consumer<GetWalletCardsError> onFailureListener);

    /**
     * Notify the {@link QuickAccessWalletService} service that a wallet card was selected.
     */
    void selectWalletCard(@NonNull SelectWalletCardRequest request);

    /**
     * Notify the {@link QuickAccessWalletService} service that the Wallet was dismissed.
     */
    void notifyWalletDismissed();

    /**
     * Unregister event listener.
     */
    void registerWalletServiceEventListener(Consumer<WalletServiceEvent> listener);

    /**
     * Unregister event listener
     */
    void unregisterWalletServiceEventListener(Consumer<WalletServiceEvent> listener);

    /**
     * The manifest entry for the QuickAccessWalletService may also publish information about the
     * activity that hosts the Wallet view. This is typically the home screen of the Wallet
     * application.
     */
    @Nullable
    Intent getWalletActivity();

    /**
     * The manifest entry for the {@link QuickAccessWalletService} may publish the activity that
     * hosts the settings
     */
    @Nullable
    Intent getSettingsActivity();
}
