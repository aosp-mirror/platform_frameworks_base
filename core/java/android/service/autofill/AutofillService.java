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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.os.BaseBundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.os.IResultReceiver;

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
 * <a name="BasicUsage"></a>
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
 *   <li>The Android System displays an autofill UI with the options sent by the service.
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
 * <a name="SavingUserData"></a>
 * <h3>Saving user data</h3>
 *
 * <p>If the service is also interested on saving the data filled by the user, it must set a
 * {@link SaveInfo} object in the {@link FillResponse}. See {@link SaveInfo} for more details and
 * examples.
 *
 * <a name="UserAuthentication"></a>
 * <h3>User authentication</h3>
 *
 * <p>The service can provide an extra degree of security by requiring the user to authenticate
 * before an app can be autofilled. The authentication is typically required in 2 scenarios:
 * <ul>
 *   <li>To unlock the user data (for example, using a main password or fingerprint
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
 * <a name="DataPartioning"></a>
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
 * released to the application without the user knowledge. Similarly, it's recommended to always
 * protect a dataset that contains sensitive information by requiring dataset authentication
 * (see {@link Dataset.Builder#setAuthentication(android.content.IntentSender)}), and to include
 * info about the "primary" field of the partition in the custom presentation for "secondary"
 * fields&mdash;that would prevent a malicious app from getting the "primary" fields without the
 * user realizing they're being released (for example, a malicious app could have fields for a
 * credit card number, verification code, and expiration date crafted in a way that just the latter
 * is visible; by explicitly indicating the expiration date is related to a given credit card
 * number, the service would be providing a visual clue for the users to check what would be
 * released upon selecting that field).
 *
 * <p>When the service detects that a screen has multiple partitions, it should return a
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
 * <a name="PackageVerification"></a>
 * <h3>Package verification</h3>
 *
 * <p>When autofilling app-specific data (like username and password), the service must verify
 * the authenticity of the request by obtaining all signing certificates of the app being
 * autofilled, and only fulfilling the request when they match the values that were
 * obtained when the data was first saved &mdash; such verification is necessary to avoid phishing
 * attempts by apps that were sideloaded in the device with the same package name of another app.
 * Here's an example on how to achieve that by hashing the signing certificates:
 *
 * <pre class="prettyprint">
 * private String getCertificatesHash(String packageName) throws Exception {
 *   PackageManager pm = mContext.getPackageManager();
 *   PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
 *   ArrayList<String> hashes = new ArrayList<>(info.signatures.length);
 *   for (Signature sig : info.signatures) {
 *     byte[] cert = sig.toByteArray();
 *     MessageDigest md = MessageDigest.getInstance("SHA-256");
 *     md.update(cert);
 *     hashes.add(toHexString(md.digest()));
 *   }
 *   Collections.sort(hashes);
 *   StringBuilder hash = new StringBuilder();
 *   for (int i = 0; i < hashes.size(); i++) {
 *     hash.append(hashes.get(i));
 *   }
 *   return hash.toString();
 * }
 * </pre>
 *
 * <p>If the service did not store the signing certificates data the first time the data was saved
 * &mdash; for example, because the data was created by a previous version of the app that did not
 * use the Autofill Framework &mdash; the service should warn the user that the authenticity of the
 * app cannot be confirmed (see an example on how to show such warning in the
 * <a href="#WebSecurityDisclaimer">Web security</a> section below), and if the user agrees,
 * then the service could save the data from the signing ceriticates for future use.
 *
 * <a name="IgnoringViews"></a>
 * <h3>Ignoring views</h3>
 *
 * <p>If the service find views that cannot be autofilled (for example, a text field representing
 * the response to a Captcha challenge), it should mark those views as ignored by
 * calling {@link FillResponse.Builder#setIgnoredIds(AutofillId...)} so the system does not trigger
 * a new {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} when these views are
 * focused.
 *
 * <a name="WebSecurity"></a>
 * <h3>Web security</h3>
 *
 * <p>When handling autofill requests that represent web pages (typically
 * view structures whose root's {@link android.app.assist.AssistStructure.ViewNode#getClassName()}
 * is a {@link android.webkit.WebView}), the service should take the following steps to verify if
 * the structure can be autofilled with the data associated with the app requesting it:
 *
 * <ol>
 *   <li>Use the {@link android.app.assist.AssistStructure.ViewNode#getWebDomain()} to get the
 *       source of the document.
 *   <li>Get the canonical domain using the
 *       <a href="https://publicsuffix.org/">Public Suffix List</a> (see example below).
 *   <li>Use <a href="https://developers.google.com/digital-asset-links/">Digital Asset Links</a>
 *       to obtain the package name and certificate fingerprint of the package corresponding to
 *       the canonical domain.
 *   <li>Make sure the certificate fingerprint matches the value returned by Package Manager
 *       (see "Package verification" section above).
 * </ol>
 *
 * <p>Here's an example on how to get the canonical domain using
 * <a href="https://github.com/google/guava">Guava</a>:
 *
 * <pre class="prettyprint">
 * private static String getCanonicalDomain(String domain) {
 *   InternetDomainName idn = InternetDomainName.from(domain);
 *   while (idn != null && !idn.isTopPrivateDomain()) {
 *     idn = idn.parent();
 *   }
 *   return idn == null ? null : idn.toString();
 * }
 * </pre>
 *
 * <a name="WebSecurityDisclaimer"></a>
 * <p>If the association between the web domain and app package cannot be verified through the steps
 * above, but the service thinks that it is appropriate to fill persisted credentials that are
 * stored for the web domain, the service should warn the user about the potential data
 * leakage first, and ask for the user to confirm. For example, the service could:
 *
 * <ol>
 *   <li>Create a dataset that requires
 *       {@link Dataset.Builder#setAuthentication(android.content.IntentSender) authentication} to
 *       unlock.
 *   <li>Include the web domain in the custom presentation for the
 *       {@link Dataset.Builder#setValue(AutofillId, AutofillValue, android.widget.RemoteViews)
 *       dataset value}.
 *   <li>When the user selects that dataset, show a disclaimer dialog explaining that the app is
 *       requesting credentials for a web domain, but the service could not verify if the app owns
 *       that domain. If the user agrees, then the service can unlock the dataset.
 *   <li>Similarly, when adding a {@link SaveInfo} object for the request, the service should
 *       include the above disclaimer in the {@link SaveInfo.Builder#setDescription(CharSequence)}.
 * </ol>
 *
 * <p>This same procedure could also be used when the autofillable data is contained inside an
 * {@code IFRAME}, in which case the WebView generates a new autofill context when a node inside
 * the {@code IFRAME} is focused, with the root node containing the {@code IFRAME}'s {@code src}
 * attribute on {@link android.app.assist.AssistStructure.ViewNode#getWebDomain()}. A typical and
 * legitimate use case for this scenario is a financial app that allows the user
 * to login on different bank accounts. For example, a financial app {@code my_financial_app} could
 * use a WebView that loads contents from {@code banklogin.my_financial_app.com}, which contains an
 * {@code IFRAME} node whose {@code src} attribute is {@code login.some_bank.com}. When fulfilling
 * that request, the service could add an
 * {@link Dataset.Builder#setAuthentication(android.content.IntentSender) authenticated dataset}
 * whose presentation displays "Username for some_bank.com" and
 * "Password for some_bank.com". Then when the user taps one of these options, the service
 * shows the disclaimer dialog explaining that selecting that option would release the
 * {@code login.some_bank.com} credentials to the {@code my_financial_app}; if the user agrees,
 * then the service returns an unlocked dataset with the {@code some_bank.com} credentials.
 *
 * <p><b>Note:</b> The autofill service could also add well-known browser apps into an allowlist and
 * skip the verifications above, as long as the service can verify the authenticity of the browser
 * app by checking its signing certificate.
 *
 * <a name="MultipleStepsSave"></a>
 * <h3>Saving when data is split in multiple screens</h3>
 *
 * Apps often split the user data in multiple screens in the same activity, specially in
 * activities used to create a new user account. For example, the first screen asks for a username,
 * and if the username is available, it moves to a second screen, which asks for a password.
 *
 * <p>It's tricky to handle save for autofill in these situations, because the autofill service must
 * wait until the user enters both fields before the autofill save UI can be shown. But it can be
 * done by following the steps below:
 *
 * <ol>
 * <li>In the first
 * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback) fill request}, the service
 * adds a {@link FillResponse.Builder#setClientState(android.os.Bundle) client state bundle} in
 * the response, containing the autofill ids of the partial fields present in the screen.
 * <li>In the second
 * {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback) fill request}, the service
 * retrieves the {@link FillRequest#getClientState() client state bundle}, gets the autofill ids
 * set in the previous request from the client state, and adds these ids and the
 * {@link SaveInfo#FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE} to the {@link SaveInfo} used in the second
 * response.
 * <li>In the {@link #onSaveRequest(SaveRequest, SaveCallback) save request}, the service uses the
 * proper {@link FillContext fill contexts} to get the value of each field (there is one fill
 * context per fill request).
 * </ol>
 *
 * <p>For example, in an app that uses 2 steps for the username and password fields, the workflow
 * would be:
 * <pre class="prettyprint">
 *  // On first fill request
 *  AutofillId usernameId = // parse from AssistStructure;
 *  Bundle clientState = new Bundle();
 *  clientState.putParcelable("usernameId", usernameId);
 *  fillCallback.onSuccess(
 *    new FillResponse.Builder()
 *        .setClientState(clientState)
 *        .setSaveInfo(new SaveInfo
 *             .Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, new AutofillId[] {usernameId})
 *             .build())
 *        .build());
 *
 *  // On second fill request
 *  Bundle clientState = fillRequest.getClientState();
 *  AutofillId usernameId = clientState.getParcelable("usernameId");
 *  AutofillId passwordId = // parse from AssistStructure
 *  clientState.putParcelable("passwordId", passwordId);
 *  fillCallback.onSuccess(
 *    new FillResponse.Builder()
 *        .setClientState(clientState)
 *        .setSaveInfo(new SaveInfo
 *             .Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
 *                      new AutofillId[] {usernameId, passwordId})
 *             .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
 *             .build())
 *        .build());
 *
 *  // On save request
 *  Bundle clientState = saveRequest.getClientState();
 *  AutofillId usernameId = clientState.getParcelable("usernameId");
 *  AutofillId passwordId = clientState.getParcelable("passwordId");
 *  List<FillContext> fillContexts = saveRequest.getFillContexts();
 *
 *  FillContext usernameContext = fillContexts.get(0);
 *  ViewNode usernameNode = findNodeByAutofillId(usernameContext.getStructure(), usernameId);
 *  AutofillValue username = usernameNode.getAutofillValue().getTextValue().toString();
 *
 *  FillContext passwordContext = fillContexts.get(1);
 *  ViewNode passwordNode = findNodeByAutofillId(passwordContext.getStructure(), passwordId);
 *  AutofillValue password = passwordNode.getAutofillValue().getTextValue().toString();
 *
 *  save(username, password);
 *  </pre>
 *
 * <a name="Privacy"></a>
 * <h3>Privacy</h3>
 *
 * <p>The {@link #onFillRequest(FillRequest, CancellationSignal, FillCallback)} method is called
 * without the user content. The Android system strips some properties of the
 * {@link android.app.assist.AssistStructure.ViewNode view nodes} passed to this call, but not all
 * of them. For example, the data provided in the {@link android.view.ViewStructure.HtmlInfo}
 * objects set by {@link android.webkit.WebView} is never stripped out.
 *
 * <p>Because this data could contain PII (Personally Identifiable Information, such as username or
 * email address), the service should only use it locally (i.e., in the app's process) for
 * heuristics purposes, but it should not be sent to external servers.
 *
 * <a name="FieldClassification"></a>
 * <h3>Metrics and field classification</h3>
 *
 * <p>The service can call {@link #getFillEventHistory()} to get metrics representing the user
 * actions, and then use these metrics to improve its heuristics.
 *
 * <p>Prior to Android {@link android.os.Build.VERSION_CODES#P}, the metrics covered just the
 * scenarios where the service knew how to autofill an activity, but Android
 * {@link android.os.Build.VERSION_CODES#P} introduced a new mechanism called field classification,
 * which allows the service to dynamically classify the meaning of fields based on the existing user
 * data known by the service.
 *
 * <p>Typically, field classification can be used to detect fields that can be autofilled with
 * user data that is not associated with a specific app&mdash;such as email and physical
 * address. Once the service identifies that a such field was manually filled by the user, the
 * service could use this signal to improve its heuristics on subsequent requests (for example, by
 * infering which resource ids are associated with known fields).
 *
 * <p>The field classification workflow involves 4 steps:
 *
 * <ol>
 *   <li>Set the user data through {@link AutofillManager#setUserData(UserData)}. This data is
 *   cached until the system restarts (or the service is disabled), so it doesn't need to be set for
 *   all requests.
 *   <li>Identify which fields should be analysed by calling
 *   {@link FillResponse.Builder#setFieldClassificationIds(AutofillId...)}.
 *   <li>Verify the results through {@link FillEventHistory.Event#getFieldsClassification()}.
 *   <li>Use the results to dynamically create {@link Dataset} or {@link SaveInfo} objects in
 *   subsequent requests.
 * </ol>
 *
 * <p>The field classification is an expensive operation and should be used carefully, otherwise it
 * can reach its rate limit and get blocked by the Android System. Ideally, it should be used just
 * in cases where the service could not determine how an activity can be autofilled, but it has a
 * strong suspicious that it could. For example, if an activity has four or more fields and one of
 * them is a list, chances are that these are address fields (like address, city, state, and
 * zip code).
 *
 * <a name="CompatibilityMode"></a>
 * <h3>Compatibility mode</h3>
 *
 * <p>Apps that use standard Android widgets support autofill out-of-the-box and need to do
 * very little to improve their user experience (annotating autofillable views and providing
 * autofill hints). However, some apps (typically browsers) do their own rendering and the rendered
 * content may contain semantic structure that needs to be surfaced to the autofill framework. The
 * platform exposes APIs to achieve this, however it could take some time until these apps implement
 * autofill support.
 *
 * <p>To enable autofill for such apps the platform provides a compatibility mode in which the
 * platform would fall back to the accessibility APIs to generate the state reported to autofill
 * services and fill data. This mode needs to be explicitly requested for a given package up
 * to a specified max version code allowing clean migration path when the target app begins to
 * support autofill natively. Note that enabling compatibility may degrade performance for the
 * target package and should be used with caution. The platform supports creating an allowlist for
 * including which packages can be targeted in compatibility mode to ensure this mode is used only
 * when needed and as long as needed.
 *
 * <p>You can request compatibility mode for packages of interest in the meta-data resource
 * associated with your service. Below is a sample service declaration:
 *
 * <pre> &lt;service android:name=".MyAutofillService"
 *              android:permission="android.permission.BIND_AUTOFILL_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.service.autofill.AutofillService" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.autofill" android:resource="@xml/autofillservice" /&gt;
 * &lt;/service&gt;</pre>
 *
 * <p>In the XML file you can specify one or more packages for which to enable compatibility
 * mode. Below is a sample meta-data declaration:
 *
 * <pre> &lt;autofill-service xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *     &lt;compatibility-package android:name="foo.bar.baz" android:maxLongVersionCode="1000000000"/&gt;
 * &lt;/autofill-service&gt;</pre>
 *
 * <p>Notice that compatibility mode has limitations such as:
 * <ul>
 * <li>No manual autofill requests. Hence, the {@link FillRequest}
 * {@link FillRequest#getFlags() flags} never have the {@link FillRequest#FLAG_MANUAL_REQUEST} flag.
 * <li>The value of password fields are most likely masked&mdash;for example, {@code ****} instead
 * of {@code 1234}. Hence, you must be careful when using these values to avoid updating the user
 * data with invalid input. For example, when you parse the {@link FillRequest} and detect a
 * password field, you could check if its
 * {@link android.app.assist.AssistStructure.ViewNode#getInputType()
 * input type} has password flags and if so, don't add it to the {@link SaveInfo} object.
 * <li>The autofill context is not always {@link AutofillManager#commit() committed} when an HTML
 * form is submitted. Hence, you must use other mechanisms to trigger save, such as setting the
 * {@link SaveInfo#FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE} flag on {@link SaveInfo.Builder#setFlags(int)}
 * or using {@link SaveInfo.Builder#setTriggerId(AutofillId)}.
 * <li>Browsers often provide their own autofill management system. When both the browser and
 * the platform render an autofill dialog at the same time, the result can be confusing to the user.
 * Such browsers typically offer an option for users to disable autofill, so your service should
 * also allow users to disable compatiblity mode for specific apps. That way, it is up to the user
 * to decide which autofill mechanism&mdash;the browser's or the platform's&mdash;should be used.
 * </ul>
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

    /**
     * Name of the {@link FillResponse} extra used to return a delayed fill response.
     *
     * <p>Please see {@link FillRequest#getDelayedFillIntentSender()} on how to send a delayed
     * fill response to framework.</p>
     */
    public static final String EXTRA_FILL_RESPONSE = "android.service.autofill.extra.FILL_RESPONSE";

    /**
     * Name of the {@link IResultReceiver} extra used to return the primary result of a request.
     *
     * @hide
     */
    public static final String EXTRA_RESULT = "result";

    /**
     * Name of the {@link IResultReceiver} extra used to return the error reason of a request.
     *
     * @hide
     */
    public static final String EXTRA_ERROR = "error";

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void onConnectedStateChanged(boolean connected) {
            mHandler.sendMessage(obtainMessage(
                    connected ? AutofillService::onConnected : AutofillService::onDisconnected,
                    AutofillService.this));
        }

        @Override
        public void onFillRequest(FillRequest request, IFillCallback callback) {
            ICancellationSignal transport = CancellationSignal.createTransport();
            try {
                callback.onCancellable(transport);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mHandler.sendMessage(obtainMessage(
                    AutofillService::onFillRequest,
                    AutofillService.this, request, CancellationSignal.fromTransport(transport),
                    new FillCallback(callback, request.getId())));
        }

        @Override
        public void onConvertCredentialRequest(
                @NonNull ConvertCredentialRequest convertCredentialRequest,
                @NonNull IConvertCredentialCallback convertCredentialCallback) {
            mHandler.sendMessage(obtainMessage(
                    AutofillService::onConvertCredentialRequest,
                    AutofillService.this, convertCredentialRequest,
                    new ConvertCredentialCallback(convertCredentialCallback)));
        }

        @Override
        public void onFillCredentialRequest(FillRequest request, IFillCallback callback,
                IAutoFillManagerClient autofillClientCallback) {
            ICancellationSignal transport = CancellationSignal.createTransport();
            try {
                callback.onCancellable(transport);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mHandler.sendMessage(obtainMessage(
                    AutofillService::onFillCredentialRequest,
                    AutofillService.this, request, CancellationSignal.fromTransport(transport),
                    new FillCallback(callback, request.getId()),
                    autofillClientCallback));
        }

        @Override
        public void onSaveRequest(SaveRequest request, ISaveCallback callback) {
            mHandler.sendMessage(obtainMessage(
                    AutofillService::onSaveRequest,
                    AutofillService.this, request, new SaveCallback(callback)));
        }

        @Override
        public void onSavedPasswordCountRequest(IResultReceiver receiver) {
            mHandler.sendMessage(obtainMessage(
                    AutofillService::onSavedDatasetsInfoRequest,
                    AutofillService.this,
                    new SavedDatasetsInfoCallbackImpl(receiver, SavedDatasetsInfo.TYPE_PASSWORDS)));
        }
    };

    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        BaseBundle.setShouldDefuse(true);
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
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
     * Variant of onFillRequest for internal credential manager proxy autofill service only.
     *
     * @hide
     */
    public void onFillCredentialRequest(@NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback,
            @NonNull IAutoFillManagerClient autofillClientCallback) {}

    /**
     * Called by the Android system to convert a credential manager response to a dataset
     *
     * @param convertCredentialRequest the request that has the original credential manager response
     * @param convertCredentialCallback callback used to notify the result of the request.
     *
     * @hide
     */
    public void onConvertCredentialRequest(
            @NonNull ConvertCredentialRequest convertCredentialRequest,
            @NonNull ConvertCredentialCallback convertCredentialCallback){}

    /**
     * Called when the user requests the service to save the contents of a screen.
     *
     * <p>If the service could not handle the request right away&mdash;for example, because it must
     * launch an activity asking the user to authenticate first or because the network is
     * down&mdash;the service could keep the {@link SaveRequest request} and reuse it later,
     * but the service <b>must always</b> call {@link SaveCallback#onSuccess()} or
     * {@link SaveCallback#onSuccess(android.content.IntentSender)} right away.
     *
     * <p><b>Note:</b> To retrieve the actual value of fields input by the user, the service
     * should call
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
     * Called from system settings to display information about the datasets the user saved to this
     * service.
     *
     * <p>There is no timeout for the request, but it's recommended to return the result within a
     * few seconds, or the user may navigate away from the activity that would display the result.
     *
     * @param callback callback for responding to the request
     */
    public void onSavedDatasetsInfoRequest(@NonNull SavedDatasetsInfoCallback callback) {
        callback.onError(SavedDatasetsInfoCallback.ERROR_UNSUPPORTED);
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutofillService}.
     * It should not make calls on {@link AutofillManager} that requires the caller to be
     * the current service.
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
     *
     * @throws RuntimeException if the event history could not be retrieved.
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
