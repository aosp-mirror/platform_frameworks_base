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
package android.service.autofill;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.provider.Settings;

import com.android.internal.os.HandlerCaller;
import android.annotation.SdkConstant;
import android.app.Service;import android.content.Intent;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import com.android.internal.os.SomeArgs;

/**
 * An {@code AutofillService} is a service used to automatically fill the contents of the screen
 * on behalf of a given user - for more information about autofill, read
 * <a href="{@docRoot}preview/features/autofill.html">Autofill Framework</a>.
 *
 * <p>An {@code AutofillService} is only bound to the Android System for autofill purposes if:
 * <ol>
 *   <li>It requires the {@code android.permission.BIND_AUTOFILL_SERVICE} permission in its
 *       manifest.
 *   <li>The user explicitly enables it using Android Settings (the
 *       {@link Settings#ACTION_REQUEST_SET_AUTOFILL_SERVICE} intent can be used to launch such
 *       Settings screen).
 * </ol>
 *
 * <h3>Basic usage</h3>
 *
 * <p>The basic autofill process is defined by the workflow below:
 * <ol>
 *   <li>User focus an editable {@link View}.
 *   <li>View calls {@link AutofillManager#notifyViewEntered(android.view.View)}.
 *   <li>A {@link ViewStructure} representing all views in the screen is created.
 *   <li>The Android System binds to the service and calls {@link #onConnected()}.
 *   <li>The service receives the view structure through the
 *       {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)}.
 *   <li>The service replies through {@link FillCallback#onSuccess(FillResponse)}.
 *   <li>The Android System calls {@link #onDisconnected()} and unbinds from the
 *       {@code AutofillService}.
 *   <li>The Android System displays an UI affordance with the options sent by the service.
 *   <li>The user picks an option.
 *   <li>The proper views are autofilled.
 * </ol>
 *
 * <p>This workflow was designed to minimize the time the Android System is bound to the service;
 * for each call, it: binds to service, waits for the reply, and unbinds right away. Furthermore,
 * those calls are considered stateless: if the service needs to keep state between calls, it must
 * do its own state management (keeping in mind that the service's process might be killed by the
 * Android System when unbound; for example, if the device is running low in memory).
 *
 * <p>Typically, the
 * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} will:
 * <ol>
 *   <li>Parse the view structure looking for autofillable views (for example, using
 *       {@link android.app.assist.AssistStructure.ViewNode#getAutofillHints()}.
 *   <li>Match the autofillable views with the user's data.
 *   <li>Create a {@link Dataset} for each set of user's data that match those fields.
 *   <li>Fill the dataset(s) with the proper {@link AutofillId}s and {@link AutofillValue}s.
 *   <li>Add the dataset(s) to the {@link FillResponse} passed to
 *       {@link FillCallback#onSuccess(FillResponse)}.
 * </ol>
 *
 * <p>For example, for a login screen with username and password views where the user only has one
 * account in the service, the response could be:
 *
 * <pre class="prettyprint">
 * new FillResponse.Builder()
 *     .addDataset(new Dataset.Builder()
 *         .setValue(id1, AutofillValue.forText("homer"), createPresentation("homer"))
 *         .setValue(id2, AutofillValue.forText("D'OH!"), createPresentation("password for homer"))
 *         .build())
 *     .build();
 * </pre>
 *
 * <p>But if the user had 2 accounts instead, the response could be:
 *
 * <pre class="prettyprint">
 * new FillResponse.Builder()
 *     .addDataset(new Dataset.Builder()
 *         .setValue(id1, AutofillValue.forText("homer"), createPresentation("homer"))
 *         .setValue(id2, AutofillValue.forText("D'OH!"), createPresentation("password for homer"))
 *         .build())
 *     .addDataset(new Dataset.Builder()
 *         .setValue(id1, AutofillValue.forText("flanders"), createPresentation("flanders"))
 *         .setValue(id2, AutofillValue.forText("OkelyDokelyDo"), createPresentation("password for flanders"))
 *         .build())
 *     .build();
 * </pre>
 *
 * <p>If the service does not find any autofillable view in the view structure, it should pass
 * {@code null} to {@link FillCallback#onSuccess(FillResponse)}; if the service encountered an error
 * processing the request, it should call {@link FillCallback#onFailure(CharSequence)}. For
 * performance reasons, it's paramount that the service calls either
 * {@link FillCallback#onSuccess(FillResponse)} or {@link FillCallback#onFailure(CharSequence)} for
 * each {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} received - if it
 * doesn't, the request will eventually time out and be discarded by the Android System.
 *
 * <h3>Saving user data</h3>
 *
 * <p>If the service is also interested on saving the data filled by the user, it must set a
 * {@link SaveInfo} object in the {@link FillResponse}. See {@link SaveInfo} for more details and
 * examples.
 *
 * <h3>User authentication</h3>
 *
 * <p>The service can provide an extra degree of security by requiring the user to authenticate
 * before an app can be autofilled. The authentication is typically required in 2 scenarios:
 * <ul>
 *   <li>To unlock the user data (for example, using a master password or fingerprint
 *       authentication) - see
 * {@link FillResponse.Builder#setAuthentication(AutofillId[], android.content.IntentSender, android.widget.RemoteViews)}.
 *   <li>To unlock a specific dataset (for example, by providing a CVC for a credit card) - see
 *       {@link Dataset.Builder#setAuthentication(android.content.IntentSender)}.
 * </ul>
 *
 * <p>When using authentication, it is recommended to encrypt only the sensitive data and leave
 * labels unencrypted, so they can be used on presentation views. For example, if the user has a
 * home and a work address, the {@code Home} and {@code Work} labels should be stored unencrypted
 * (since they don't have any sensitive data) while the address data per se could be stored in an
 * encrypted storage. Then when the user chooses the {@code Home} dataset, the platform starts
 * the authentication flow, and the service can decrypt the sensitive data.
 *
 * <p>The authentication mechanism can also be used in scenarios where the service needs multiple
 * steps to determine the datasets that can fill a screen. For example, when autofilling a financial
 * app where the user has accounts for multiple banks, the workflow could be:
 *
 * <ol>
 *   <li>The first {@link FillResponse} contains datasets with the credentials for the financial
 *       app, plus a "fake" dataset whose presentation says "Tap here for banking apps credentials".
 *   <li>When the user selects the fake dataset, the service displays a dialog with available
 *       banking apps.
 *   <li>When the user select a banking app, the service replies with a new {@link FillResponse}
 *       containing the datasets for that bank.
 * </ol>
 *
 * <p>Another example of multiple-steps dataset selection is when the service stores the user
 * credentials in "vaults": the first response would contain fake datasets with the vault names,
 * and the subsequent response would contain the app credentials stored in that vault.
 *
 * <h3>Data partitioning</h3>
 *
 * <p>The autofillable views in a screen should be grouped in logical groups called "partitions".
 * Typical partitions are:
 * <ul>
 *   <li>Credentials (username/email address, password).
 *   <li>Address (street, city, state, zip code, etc).
 *   <li>Payment info (credit card number, expiration date, and verification code).
 * </ul>
 * <p>For security reasons, when a screen has more than one partition, it's paramount that the
 * contents of a dataset do not spawn multiple partitions, specially when one of the partitions
 * contains data that is not specific to the application being autofilled. For example, a dataset
 * should not contain fields for username, password, and credit card information. The reason for
 * this rule is that a malicious app could draft a view structure where the credit card fields
 * are not visible, so when the user selects a dataset from the username UI, the credit card info is
 * released to the application without the user knowledge. Similar, it's recommended to always
 * protect a dataset that contains sensitive information by requiring dataset authentication
 * (see {@link Dataset.Builder#setAuthentication(android.content.IntentSender)}).
 *
 * <p>When the service detects that a screen have multiple partitions, it should return a
 * {@link FillResponse} with just the datasets for the partition that originated the request (i.e.,
 * the partition that has the {@link android.app.assist.AssistStructure.ViewNode} whose
 * {@link android.app.assist.AssistStructure.ViewNode#isFocused()} returns {@code true}); then if
 * the user selects a field from a different partition, the Android System will make another
 * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} call for that partition,
 * and so on.
 *
 * <p>Notice that when the user autofill a partition with the data provided by the service and the
 * user did not change these fields, the autofilled value is sent back to the service in the
 * subsequent calls (and can be obtained by calling
 * {@link android.app.assist.AssistStructure.ViewNode#getAutofillValue()}). This is useful in the
 * cases where the service must create datasets for a partition based on the choice made in a
 * previous partition. For example, the 1st response for a screen that have credentials and address
 * partitions could be:
 *
 * <pre class="prettyprint">
 * new FillResponse.Builder()
 *     .addDataset(new Dataset.Builder() // partition 1 (credentials)
 *         .setValue(id1, AutofillValue.forText("homer"), createPresentation("homer"))
 *         .setValue(id2, AutofillValue.forText("D'OH!"), createPresentation("password for homer"))
 *         .build())
 *     .addDataset(new Dataset.Builder() // partition 1 (credentials)
 *         .setValue(id1, AutofillValue.forText("flanders"), createPresentation("flanders"))
 *         .setValue(id2, AutofillValue.forText("OkelyDokelyDo"), createPresentation("password for flanders"))
 *         .build())
 *     .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD,
 *         new AutofillId[] { id1, id2 })
 *             .build())
 *     .build();
 * </pre>
 *
 * <p>Then if the user selected {@code flanders}, the service would get a new
 * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} call, with the values of
 * the fields {@code id1} and {@code id2} prepopulated, so the service could then fetch the address
 * for the Flanders account and return the following {@link FillResponse} for the address partition:
 *
 * <pre class="prettyprint">
 * new FillResponse.Builder()
 *     .addDataset(new Dataset.Builder() // partition 2 (address)
 *         .setValue(id3, AutofillValue.forText("744 Evergreen Terrace"), createPresentation("744 Evergreen Terrace")) // street
 *         .setValue(id4, AutofillValue.forText("Springfield"), createPresentation("Springfield")) // city
 *         .build())
 *     .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD | SaveInfo.SAVE_DATA_TYPE_ADDRESS,
 *         new AutofillId[] { id1, id2 }) // username and password
 *              .setOptionalIds(new AutofillId[] { id3, id4 }) // state and zipcode
 *             .build())
 *     .build();
 * </pre>
 *
 * <p>When the service returns multiple {@link FillResponse}, the last one overrides the previous;
 * that's why the {@link SaveInfo} in the 2nd request above has the info for both partitions.
 *
 * <h3>Ignoring views</h3>
 *
 * <p>If the service find views that cannot be autofilled (for example, a text field representing
 * the response to a Captcha challenge), it should mark those views as ignored by
 * calling {@link FillResponse.Builder#setIgnoredIds(AutofillId...)} so the system does not trigger
 * a new {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} when these views are
 * focused.
 */
public abstract class AutofillService extends Service {
    private static final String TAG = "AutofillService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUTOFILL_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.autofill.AutofillService";

    /**
     * Name under which a AutoFillService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#AutofillService autofill-service}&gt;</code> tag.
     * This is a a sample XML file configuring an AutoFillService:
     * <pre> &lt;autofill-service
     *     android:settingsActivity="foo.bar.SettingsActivity"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.autofill";

    // Handler messages.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_ON_FILL_REQUEST = 3;
    private static final int MSG_ON_SAVE_REQUEST = 4;

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void onConnectedStateChanged(boolean connected) {
            if (connected) {
                mHandlerCaller.obtainMessage(MSG_CONNECT).sendToTarget();
            } else {
                mHandlerCaller.obtainMessage(MSG_DISCONNECT).sendToTarget();
            }
        }

        @Override
        public void onFillRequest(FillRequest request, IFillCallback callback) {
            ICancellationSignal transport = CancellationSignal.createTransport();
            try {
                callback.onCancellable(transport);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mHandlerCaller.obtainMessageOOO(MSG_ON_FILL_REQUEST, request,
                    CancellationSignal.fromTransport(transport), callback)
                    .sendToTarget();
        }

        @Override
        public void onSaveRequest(SaveRequest request, ISaveCallback callback) {
            mHandlerCaller.obtainMessageOO(MSG_ON_SAVE_REQUEST, request,
                    callback).sendToTarget();
        }
    };

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_CONNECT: {
                onConnected();
                break;
            } case MSG_ON_FILL_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final FillRequest request = (FillRequest) args.arg1;
                final CancellationSignal cancellation = (CancellationSignal) args.arg2;
                final IFillCallback callback = (IFillCallback) args.arg3;
                final FillCallback fillCallback = new FillCallback(callback, request.getId());
                args.recycle();
                onFillRequest(request, cancellation, fillCallback);
                break;
            } case MSG_ON_SAVE_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final SaveRequest request = (SaveRequest) args.arg1;
                final ISaveCallback callback = (ISaveCallback) args.arg2;
                final SaveCallback saveCallback = new SaveCallback(callback);
                args.recycle();
                onSaveRequest(request, saveCallback);
                break;
            } case MSG_DISCONNECT: {
                onDisconnected();
                break;
            } default: {
                Log.w(TAG, "MyCallbacks received invalid message type: " + msg);
            }
        }
    };

    private HandlerCaller mHandlerCaller;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(), mHandlerCallback, true);
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent: " + intent);
        return null;
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
    }

    /**
     * Called by the Android system do decide if a screen can be autofilled by the service.
     *
     * <p>Service must call one of the {@link FillCallback} methods (like
     * {@link FillCallback#onSuccess(FillResponse)}
     * or {@link FillCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param request the {@link FillRequest request} to handle.
     *        See {@link FillResponse} for examples of multiple-sections requests.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onFillRequest(@NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback);

    /**
     * Called when user requests service to save the fields of a screen.
     *
     * <p>Service must call one of the {@link SaveCallback} methods (like
     * {@link SaveCallback#onSuccess()} or {@link SaveCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * <p><b>NOTE: </b>to retrieve the actual value of the field, the service should call
     * {@link android.app.assist.AssistStructure.ViewNode#getAutofillValue()}; if it calls
     * {@link android.app.assist.AssistStructure.ViewNode#getText()} or other methods, there is no
     * guarantee such method will return the most recent value of the field.
     *
     * @param request the {@link SaveRequest request} to handle.
     *        See {@link FillResponse} for examples of multiple-sections requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onSaveRequest(@NonNull SaveRequest request,
            @NonNull SaveCallback callback);

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutofillService}.
     */
    public void onDisconnected() {
    }

    /**
     * Gets the events that happened after the last
     * {@link AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
     * call.
     *
     * <p>This method is typically used to keep track of previous user actions to optimize further
     * requests. For example, the service might return email addresses in alphabetical order by
     * default, but change that order based on the address the user picked on previous requests.
     *
     * <p>The history is not persisted over reboots, and it's cleared every time the service
     * replies to a {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} by calling
     * {@link FillCallback#onSuccess(FillResponse)} or {@link FillCallback#onFailure(CharSequence)}
     * (if the service doesn't call any of these methods, the history will clear out after some
     * pre-defined time). Hence, the service should call {@link #getFillEventHistory()} before
     * finishing the {@link FillCallback}.
     *
     * @return The history or {@code null} if there are no events.
     */
    @Nullable public final FillEventHistory getFillEventHistory() {
        final AutofillManager afm = getSystemService(AutofillManager.class);

        if (afm == null) {
            return null;
        } else {
            return afm.getFillEventHistory();
        }
    }
}
