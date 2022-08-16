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
import android.annotation.SdkConstant;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

/**
 * A {@code QuickAccessWalletService} provides a list of {@code WalletCard}s shown in the Quick
 * Access Wallet. The Quick Access Wallet allows the user to change their selected payment method
 * and access other important passes, such as tickets and transit passes, without leaving the
 * context of their current app.
 *
 * <p>An {@code QuickAccessWalletService} is only bound to the Android System for the purposes of
 * showing wallet cards if:
 * <ol>
 *   <li>The application hosting the QuickAccessWalletService is also the default NFC payment
 *   application. This means that the same application must also have a
 *   {@link android.nfc.cardemulation.HostApduService} or
 *   {@link android.nfc.cardemulation.OffHostApduService} that requires the
 *   android.permission.BIND_NFC_SERVICE permission.
 *   <li>The user explicitly selected the application as the default payment application in
 *   the Tap &amp; pay settings screen.
 *   <li>The QuickAccessWalletService requires that the binding application hold the
 *   {@code android.permission.BIND_QUICK_ACCESS_WALLET_SERVICE} permission, which only the System
 *   Service can hold.
 *   <li>The user explicitly enables it using Android Settings (the
 *       {@link Settings#ACTION_QUICK_ACCESS_WALLET_SETTINGS} intent can be used to launch it).
 * </ol>
 *
 * <a name="BasicUsage"></a>
 * <h3>Basic usage</h3>
 *
 * <p>The basic Quick Access Wallet process is defined by the workflow below:
 * <ol>
 *   <li>User performs a gesture to bring up the Quick Access Wallet, which is displayed by the
 *   Android System.
 *   <li>The Android System creates a {@link GetWalletCardsRequest}, binds to the
 *   {@link QuickAccessWalletService}, and delivers the request.
 *   <li>The service receives the request through {@link #onWalletCardsRequested}
 *   <li>The service responds by calling {@link GetWalletCardsCallback#onSuccess} with a
 *   {@link GetWalletCardsResponse response} that contains between 1 and
 *   {@link GetWalletCardsRequest#getMaxCards() maxCards} cards.
 *   <li>The Android System displays the Quick Access Wallet containing the provided cards. The
 *   card at the {@link GetWalletCardsResponse#getSelectedIndex() selectedIndex} will initially
 *   be presented as the 'selected' card.
 *   <li>As soon as the cards are displayed, the Android System will notify the service that the
 *   card at the selected index has been selected through {@link #onWalletCardSelected}.
 *   <li>The user interacts with the wallet and may select one or more cards in sequence. Each time
 *   a new card is selected, the Android System will notify the service through
 *   {@link #onWalletCardSelected} and will provide the {@link WalletCard#getCardId() cardId} of the
 *   card that is now selected.
 *   <li>If the user commences an NFC payment, the service may send a {@link WalletServiceEvent}
 *   to the System indicating that the wallet application now needs to show the activity associated
 *   with making a payment. Sending a {@link WalletServiceEvent} of type
 *   {@link WalletServiceEvent#TYPE_NFC_PAYMENT_STARTED} should cause the quick access wallet UI
 *   to be dismissed.
 *   <li>When the wallet is dismissed, the Android System will notify the service through
 *   {@link #onWalletDismissed}.
 * </ol>
 *
 * <p>The workflow is designed to minimize the time that the Android System is bound to the
 * service, but connections may be cached and reused to improve performance and conserve memory.
 * All calls should be considered stateless: if the service needs to keep state between calls, it
 * must do its own state management (keeping in mind that the service's process might be killed
 * by the Android System when unbound; for example, if the device is running low in memory).
 *
 * <p>
 * <a name="ErrorHandling"></a>
 * <h3>Error handling</h3>
 * <p>If the service encountered an error processing the request, it should call
 * {@link GetWalletCardsCallback#onFailure}.
 * For performance reasons, it's paramount that the service calls either
 * {@link GetWalletCardsCallback#onSuccess} or
 * {@link GetWalletCardsCallback#onFailure} for each
 * {@link #onWalletCardsRequested} received - if it doesn't, the request will eventually time out
 * and be discarded by the Android System.
 *
 * <p>
 * <a name="ManifestEntry"></a>
 * <h3>Manifest entry</h3>
 *
 * <p>QuickAccessWalletService must require the permission
 * "android.permission.BIND_QUICK_ACCESS_WALLET_SERVICE".
 *
 * <pre class="prettyprint">
 * {@literal
 * <service
 *     android:name=".MyQuickAccessWalletService"
 *     android:label="@string/my_default_tile_label"
 *     android:icon="@drawable/my_default_icon_label"
 *     android:logo="@drawable/my_wallet_logo"
 *     android:permission="android.permission.BIND_QUICK_ACCESS_WALLET_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.service.quickaccesswallet.QuickAccessWalletService" />
 *         <category android:name="android.intent.category.DEFAULT"/>
 *     </intent-filter>
 *     <meta-data android:name="android.quickaccesswallet"
 *          android:resource="@xml/quickaccesswallet_configuration" />;
 * </service>}
 * </pre>
 * <p>
 * The {@literal <meta-data>} element includes an android:resource attribute that points to an
 * XML resource with further details about the service. The {@code quickaccesswallet_configuration}
 * in the example above specifies an activity that allows the users to view the entire wallet.
 * The following example shows the quickaccesswallet_configuration XML resource:
 * <p>
 * <pre class="prettyprint">
 * {@literal
 * <quickaccesswallet-service
 *   xmlns:android="http://schemas.android.com/apk/res/android"
 *   android:settingsActivity="com.example.android.SettingsActivity"
 *   android:shortcutLongLabel="@string/my_wallet_empty_state_text"
 *   android:shortcutShortLabel="@string/my_wallet_button_text"
 *   android:targetActivity="com.example.android.WalletActivity"/>
 * }
 * </pre>
 *
 * <p>The entry for {@code settingsActivity} should contain the fully qualified class name of an
 * activity that allows the user to modify the settings for this service. The {@code targetActivity}
 * entry should contain the fully qualified class name of an activity that allows the user to view
 * their entire wallet. The {@code targetActivity} will be started with the Intent action
 * {@link #ACTION_VIEW_WALLET} and the {@code settingsActivity} will be started with the Intent
 * action {@link #ACTION_VIEW_WALLET_SETTINGS}.
 *
 * <p>The {@code shortcutShortLabel} and {@code shortcutLongLabel} are used by the QuickAccessWallet
 * in the buttons that navigate to the wallet app. The {@code shortcutShortLabel} is displayed next
 * to the cards that are returned by the service and should be no more than 20 characters. The
 * {@code shortcutLongLabel} is displayed when no cards are returned. This 'empty state' view also
 * displays the service logo, specified by the {@code android:logo} manifest entry. If the logo is
 * not specified, the empty state view will show the app icon instead.
 */
public abstract class QuickAccessWalletService extends Service {

    private static final String TAG = "QAWalletService";

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the
     * {@link android.Manifest.permission#BIND_QUICK_ACCESS_WALLET_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.quickaccesswallet.QuickAccessWalletService";

    /**
     * Intent action to launch an activity to display the wallet.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW_WALLET =
            "android.service.quickaccesswallet.action.VIEW_WALLET";

    /**
     * Intent action to launch an activity to display quick access wallet settings.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_VIEW_WALLET_SETTINGS =
            "android.service.quickaccesswallet.action.VIEW_WALLET_SETTINGS";

    /**
     * Name under which a QuickAccessWalletService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#QuickAccessWalletService quickaccesswallet-service}&gt;</code> tag. This
     * is a a sample XML file configuring an QuickAccessWalletService:
     * <pre> &lt;quickaccesswallet-service
     *     android:walletActivity="foo.bar.WalletActivity"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.quickaccesswallet";

    /**
     * Name of the QuickAccessWallet tile service meta-data.
     *
     * @hide
     */
    public static final String TILE_SERVICE_META_DATA = "android.quickaccesswallet.tile";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * The service currently only supports one listener at a time. Multiple connections that
     * register different listeners will clobber the listener. This field may only be accessed from
     * the main thread.
     */
    @Nullable
    private String mEventListenerId;

    /**
     * The service currently only supports one listener at a time. Multiple connections that
     * register different listeners will clobber the listener. This field may only be accessed from
     * the main thread.
     */
    @Nullable
    private IQuickAccessWalletServiceCallbacks mEventListener;

    private final IQuickAccessWalletService mInterface = new IQuickAccessWalletService.Stub() {
        @Override
        public void onWalletCardsRequested(
                @NonNull GetWalletCardsRequest request,
                @NonNull IQuickAccessWalletServiceCallbacks callback) {
            mHandler.post(() -> onWalletCardsRequestedInternal(request, callback));
        }

        @Override
        public void onWalletCardSelected(@NonNull SelectWalletCardRequest request) {
            mHandler.post(() -> QuickAccessWalletService.this.onWalletCardSelected(request));
        }

        @Override
        public void onWalletDismissed() {
            mHandler.post(QuickAccessWalletService.this::onWalletDismissed);
        }

        @Override
        public void onTargetActivityIntentRequested(
                @NonNull IQuickAccessWalletServiceCallbacks callbacks) {
            mHandler.post(
                    () -> QuickAccessWalletService.this.onTargetActivityIntentRequestedInternal(
                            callbacks));
        }

        public void registerWalletServiceEventListener(
                @NonNull WalletServiceEventListenerRequest request,
                @NonNull IQuickAccessWalletServiceCallbacks callback) {
            mHandler.post(() -> registerDismissWalletListenerInternal(request, callback));
        }

        public void unregisterWalletServiceEventListener(
                @NonNull WalletServiceEventListenerRequest request) {
            mHandler.post(() -> unregisterDismissWalletListenerInternal(request));
        }
    };

    private void onWalletCardsRequestedInternal(
            GetWalletCardsRequest request,
            IQuickAccessWalletServiceCallbacks callback) {
        onWalletCardsRequested(request,
                new GetWalletCardsCallbackImpl(request, callback, mHandler));
    }

    private void onTargetActivityIntentRequestedInternal(
            IQuickAccessWalletServiceCallbacks callbacks) {
        try {
            callbacks.onTargetActivityPendingIntentReceived(getTargetActivityPendingIntent());
        } catch (RemoteException e) {
            Log.w(TAG, "Error returning wallet cards", e);
        }
    }

    @Override
    @Nullable
    public IBinder onBind(@NonNull Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Binding to the QuickAccessWalletService is protected by the
            // android.permission.BIND_QUICK_ACCESS_WALLET_SERVICE permission, which is defined in
            // R. Pre-R devices can have other side-loaded applications that claim this permission.
            // Ensures that the service is only enabled when properly permission protected.
            Log.w(TAG, "Warning: binding on pre-R device");
        }
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.w(TAG, "Wrong action");
            return null;
        }
        return mInterface.asBinder();
    }

    /**
     * Called when the user requests the service to provide wallet cards.
     *
     * <p>This method will be called on the main thread, but the callback may be called from any
     * thread. The callback should be called as quickly as possible. The service must always call
     * either {@link GetWalletCardsCallback#onSuccess(GetWalletCardsResponse)} or {@link
     * GetWalletCardsCallback#onFailure(GetWalletCardsError)}. Calling multiple times or calling
     * both methods will cause an exception to be thrown.
     */
    public abstract void onWalletCardsRequested(
            @NonNull GetWalletCardsRequest request,
            @NonNull GetWalletCardsCallback callback);

    /**
     * A wallet card was selected. Sent when the user selects a wallet card from the list of cards.
     * Selection may indicate that the card is now in the center of the screen, or highlighted in
     * some other fashion. It does not mean that the user clicked on the card -- clicking on the
     * card will cause the {@link WalletCard#getPendingIntent()} to be sent.
     *
     * <p>Card selection events are especially important to NFC payment applications because
     * many NFC terminals can only accept one payment card at a time. If the user has several NFC
     * cards in their wallet, selecting different cards can change which payment method is presented
     * to the terminal.
     */
    public abstract void onWalletCardSelected(@NonNull SelectWalletCardRequest request);

    /**
     * Indicates that the wallet was dismissed. This is received when the Quick Access Wallet is no
     * longer visible.
     */
    public abstract void onWalletDismissed();

    /**
     * Send a {@link WalletServiceEvent} to the Quick Access Wallet.
     * <p>
     * Background events may require that the Quick Access Wallet view be updated. For example, if
     * the wallet application hosting this service starts to handle an NFC payment while the Quick
     * Access Wallet is being shown, the Quick Access Wallet will need to be dismissed so that the
     * Activity showing the payment can be displayed to the user.
     */
    public final void sendWalletServiceEvent(@NonNull WalletServiceEvent serviceEvent) {
        mHandler.post(() -> sendWalletServiceEventInternal(serviceEvent));
    }

    /**
     * Specify a {@link PendingIntent} to be launched as the "Quick Access" activity.
     *
     * This activity will be launched directly by the system in lieu of the card switcher activity
     * provided by the system.
     *
     * In order to use the system-provided card switcher activity, return null from this method.
     */
    @Nullable
    public PendingIntent getTargetActivityPendingIntent() {
        return null;
    }

    private void sendWalletServiceEventInternal(WalletServiceEvent serviceEvent) {
        if (mEventListener == null) {
            Log.i(TAG, "No dismiss listener registered");
            return;
        }
        try {
            mEventListener.onWalletServiceEvent(serviceEvent);
        } catch (RemoteException e) {
            Log.w(TAG, "onWalletServiceEvent error", e);
            mEventListenerId = null;
            mEventListener = null;
        }
    }

    private void registerDismissWalletListenerInternal(
            @NonNull WalletServiceEventListenerRequest request,
            @NonNull IQuickAccessWalletServiceCallbacks callback) {
        mEventListenerId = request.getListenerId();
        mEventListener = callback;
    }

    private void unregisterDismissWalletListenerInternal(
            @NonNull WalletServiceEventListenerRequest request) {
        if (mEventListenerId != null && mEventListenerId.equals(request.getListenerId())) {
            mEventListenerId = null;
            mEventListener = null;
        } else {
            Log.w(TAG, "dismiss listener missing or replaced");
        }
    }
}
