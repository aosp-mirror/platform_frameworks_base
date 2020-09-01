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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.io.Closeable;
import java.util.concurrent.Executor;

/**
 * Facilitates accessing cards from the {@link QuickAccessWalletService}.
 *
 * @hide
 */
@TestApi
public interface QuickAccessWalletClient extends Closeable {

    /**
     * Create a client for accessing wallet cards from the {@link QuickAccessWalletService}. If the
     * service is unavailable, {@link #isWalletServiceAvailable()} will return false.
     */
    @NonNull
    static QuickAccessWalletClient create(@NonNull Context context) {
        return new QuickAccessWalletClientImpl(context);
    }

    /**
     * @return true if the {@link QuickAccessWalletService} is available. This means that the
     * default NFC payment application has an exported service that can provide cards to the Quick
     * Access Wallet. However, it does not mean that (1) the call will necessarily be successful,
     * nor does it mean that cards may be displayed at this time. Addition checks are required:
     * <ul>
     *     <li>If {@link #isWalletFeatureAvailable()} is false, cards should not be displayed
     *     <li>If the device is locked and {@link #isWalletFeatureAvailableWhenDeviceLocked} is
     *     false, cards should not be displayed while the device remains locked. (A message
     *     prompting the user to unlock to view cards may be appropriate).</li>
     * </ul>
     */
    boolean isWalletServiceAvailable();

    /**
     * Wallet cards should not be displayed if:
     * <ul>
     *     <li>The wallet service is unavailable</li>
     *     <li>The device is not provisioned, ie user setup is incomplete</li>
     *     <li>If the wallet feature has been disabled by the user</li>
     *     <li>If the phone has been put into lockdown mode</li>
     * </ul>
     * <p>
     * Quick Access Wallet implementers should call this method before calling
     * {@link #getWalletCards} to ensure that cards may be displayed.
     */
    boolean isWalletFeatureAvailable();

    /**
     * Wallet cards may not be displayed on the lock screen if the user has opted to hide
     * notifications or sensitive content on the lock screen.
     * <ul>
     *     <li>The device is not provisioned, ie user setup is incomplete</li>
     *     <li>If the wallet feature has been disabled by the user</li>
     *     <li>If the phone has been put into lockdown mode</li>
     * </ul>
     *
     * <p>
     * Quick Access Wallet implementers should call this method before calling
     * {@link #getWalletCards} if the device is currently locked.
     *
     * @return true if cards may be displayed on the lock screen.
     */
    boolean isWalletFeatureAvailableWhenDeviceLocked();

    /**
     * Get wallet cards from the {@link QuickAccessWalletService}.
     */
    void getWalletCards(
            @NonNull GetWalletCardsRequest request,
            @NonNull OnWalletCardsRetrievedCallback callback);

    /**
     * Get wallet cards from the {@link QuickAccessWalletService}.
     */
    void getWalletCards(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GetWalletCardsRequest request,
            @NonNull OnWalletCardsRetrievedCallback callback);

    /**
     * Callback for getWalletCards
     */
    interface OnWalletCardsRetrievedCallback {
        void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response);

        void onWalletCardRetrievalError(@NonNull GetWalletCardsError error);
    }

    /**
     * Notify the {@link QuickAccessWalletService} service that a wallet card was selected.
     */
    void selectWalletCard(@NonNull SelectWalletCardRequest request);

    /**
     * Notify the {@link QuickAccessWalletService} service that the Wallet was dismissed.
     */
    void notifyWalletDismissed();

    /**
     * Register an event listener.
     */
    void addWalletServiceEventListener(@NonNull WalletServiceEventListener listener);

    /**
     * Register an event listener.
     */
    void addWalletServiceEventListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull WalletServiceEventListener listener);

    /**
     * Unregister an event listener
     */
    void removeWalletServiceEventListener(@NonNull WalletServiceEventListener listener);

    /**
     * A listener for {@link WalletServiceEvent walletServiceEvents}
     */
    interface WalletServiceEventListener {
        void onWalletServiceEvent(@NonNull WalletServiceEvent event);
    }

    /**
     * Unregister all event listeners and disconnect from the service.
     */
    void disconnect();

    /**
     * The manifest entry for the QuickAccessWalletService may also publish information about the
     * activity that hosts the Wallet view. This is typically the home screen of the Wallet
     * application.
     */
    @Nullable
    Intent createWalletIntent();

    /**
     * The manifest entry for the {@link QuickAccessWalletService} may publish the activity that
     * hosts the settings
     */
    @Nullable
    Intent createWalletSettingsIntent();

    /**
     * Returns the logo associated with the {@link QuickAccessWalletService}. This is specified by
     * {@code android:logo} manifest entry. If the logo is not specified, the app icon will be
     * returned instead ({@code android:icon}).
     *
     * @hide
     */
    @Nullable
    Drawable getLogo();

    /**
     * Returns the service label specified by {@code android:label} in the service manifest entry.
     *
     * @hide
     */
    @Nullable
    CharSequence getServiceLabel();

    /**
     * Returns the text specified by the {@link android:shortcutShortLabel} in the service manifest
     * entry. If the shortcutShortLabel isn't specified, the service label ({@code android:label})
     * will be returned instead.
     *
     * @hide
     */
    @Nullable
    CharSequence getShortcutShortLabel();

    /**
     * Returns the text specified by the {@link android:shortcutLongLabel} in the service manifest
     * entry. If the shortcutShortLabel isn't specified, the service label ({@code android:label})
     * will be returned instead.
     *
     * @hide
     */
    @Nullable
    CharSequence getShortcutLongLabel();
}
