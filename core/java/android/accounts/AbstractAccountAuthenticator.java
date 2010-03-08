/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.Binder;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

/**
 * Abstract base class for creating AccountAuthenticators.
 * In order to be an authenticator one must extend this class, provider implementations for the
 * abstract methods and write a service that returns the result of {@link #getIBinder()}
 * in the service's {@link android.app.Service#onBind(android.content.Intent)} when invoked
 * with an intent with action {@link AccountManager#ACTION_AUTHENTICATOR_INTENT}. This service
 * must specify the following intent filter and metadata tags in its AndroidManifest.xml file
 * <pre>
 *   &lt;intent-filter&gt;
 *     &lt;action android:name="android.accounts.AccountAuthenticator" /&gt;
 *   &lt;/intent-filter&gt;
 *   &lt;meta-data android:name="android.accounts.AccountAuthenticator"
 *             android:resource="@xml/authenticator" /&gt;
 * </pre>
 * The <code>android:resource</code> attribute must point to a resource that looks like:
 * <pre>
 * &lt;account-authenticator xmlns:android="http://schemas.android.com/apk/res/android"
 *    android:accountType="typeOfAuthenticator"
 *    android:icon="@drawable/icon"
 *    android:smallIcon="@drawable/miniIcon"
 *    android:label="@string/label"
 *    android:accountPreferences="@xml/account_preferences"
 * /&gt;
 * </pre>
 * Replace the icons and labels with your own resources. The <code>android:accountType</code>
 * attribute must be a string that uniquely identifies your authenticator and will be the same
 * string that user will use when making calls on the {@link AccountManager} and it also
 * corresponds to {@link Account#type} for your accounts. One user of the android:icon is the
 * "Account & Sync" settings page and one user of the android:smallIcon is the Contact Application's
 * tab panels.
 * <p>
 * The preferences attribute points to an PreferenceScreen xml hierarchy that contains
 * a list of PreferenceScreens that can be invoked to manage the authenticator. An example is:
 * <pre>
 * &lt;PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *    &lt;PreferenceCategory android:title="@string/title_fmt" /&gt;
 *    &lt;PreferenceScreen
 *         android:key="key1"
 *         android:title="@string/key1_action"
 *         android:summary="@string/key1_summary"&gt;
 *         &lt;intent
 *             android:action="key1.ACTION"
 *             android:targetPackage="key1.package"
 *             android:targetClass="key1.class" /&gt;
 *     &lt;/PreferenceScreen&gt;
 * &lt;/PreferenceScreen&gt;
 * </pre>
 *
 * <p>
 * The standard pattern for implementing any of the abstract methods is the following:
 * <ul>
 * <li> If the supplied arguments are enough for the authenticator to fully satisfy the request
 * then it will do so and return a {@link Bundle} that contains the results.
 * <li> If the authenticator needs information from the user to satisfy the request then it
 * will create an {@link Intent} to an activity that will prompt the user for the information
 * and then carry out the request. This intent must be returned in a Bundle as key
 * {@link AccountManager#KEY_INTENT}.
 * <p>
 * The activity needs to return the final result when it is complete so the Intent should contain
 * the {@link AccountAuthenticatorResponse} as {@link AccountManager#KEY_ACCOUNT_MANAGER_RESPONSE}.
 * The activity must then call {@link AccountAuthenticatorResponse#onResult} or
 * {@link AccountAuthenticatorResponse#onError} when it is complete.
 * <li> If the authenticator cannot synchronously process the request and return a result then it
 * may choose to return null and then use the AccountManagerResponse to send the result
 * when it has completed the request.
 * </ul>
 * <p>
 * The following descriptions of each of the abstract authenticator methods will not describe the
 * possible asynchronous nature of the request handling and will instead just describe the input
 * parameters and the expected result.
 * <p>
 * When writing an activity to satisfy these requests one must pass in the AccountManagerResponse
 * and return the result via that response when the activity finishes (or whenever else  the
 * activity author deems it is the correct time to respond).
 * The {@link AccountAuthenticatorActivity} handles this, so one may wish to extend that when
 * writing activities to handle these requests.
 */
public abstract class AbstractAccountAuthenticator {
    private static final String TAG = "AccountAuthenticator";

    private final Context mContext;

    public AbstractAccountAuthenticator(Context context) {
        mContext = context;
    }

    private class Transport extends IAccountAuthenticator.Stub {
        public void addAccount(IAccountAuthenticatorResponse response, String accountType,
                String authTokenType, String[] features, Bundle options)
                throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "addAccount: accountType " + accountType
                        + ", authTokenType " + authTokenType
                        + ", features " + (features == null ? "[]" : Arrays.toString(features)));
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.addAccount(
                    new AccountAuthenticatorResponse(response),
                        accountType, authTokenType, features, options);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    result.keySet(); // force it to be unparcelled
                    Log.v(TAG, "addAccount: result " + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (NetworkErrorException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "addAccount", e);
                }
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            } catch (UnsupportedOperationException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "addAccount", e);
                }
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "addAccount not supported");
            }
        }

        public void confirmCredentials(IAccountAuthenticatorResponse response,
                Account account, Bundle options) throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "confirmCredentials: " + account);
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.confirmCredentials(
                    new AccountAuthenticatorResponse(response), account, options);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    result.keySet(); // force it to be unparcelled
                    Log.v(TAG, "confirmCredentials: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (NetworkErrorException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "confirmCredentials", e);
                }
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            } catch (UnsupportedOperationException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "confirmCredentials", e);
                }
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "confirmCredentials not supported");
            }
        }

        public void getAuthTokenLabel(IAccountAuthenticatorResponse response,
                String authTokenType)
                throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "getAuthTokenLabel: authTokenType " + authTokenType);
            }
            checkBinderPermission();
            try {
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTH_TOKEN_LABEL,
                        AbstractAccountAuthenticator.this.getAuthTokenLabel(authTokenType));
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    result.keySet(); // force it to be unparcelled
                    Log.v(TAG, "getAuthTokenLabel: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (IllegalArgumentException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "getAuthTokenLabel", e);
                }
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                        "unknown authTokenType");
            } catch (UnsupportedOperationException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "getAuthTokenLabel", e);
                }
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAuthTokenTypeLabel not supported");
            }
        }

        public void getAuthToken(IAccountAuthenticatorResponse response,
                Account account, String authTokenType, Bundle loginOptions)
                throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "getAuthToken: " + account
                        + ", authTokenType " + authTokenType);
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.getAuthToken(
                        new AccountAuthenticatorResponse(response), account,
                        authTokenType, loginOptions);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    result.keySet(); // force it to be unparcelled
                    Log.v(TAG, "getAuthToken: result " + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (UnsupportedOperationException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "getAuthToken", e);
                }
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAuthToken not supported");
            } catch (NetworkErrorException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "getAuthToken", e);
                }
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            }
        }

        public void updateCredentials(IAccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle loginOptions) throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "updateCredentials: " + account
                        + ", authTokenType " + authTokenType);
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.updateCredentials(
                    new AccountAuthenticatorResponse(response), account,
                        authTokenType, loginOptions);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    result.keySet(); // force it to be unparcelled
                    Log.v(TAG, "updateCredentials: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (NetworkErrorException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "updateCredentials", e);
                }
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            } catch (UnsupportedOperationException e) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "updateCredentials", e);
                }
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "updateCredentials not supported");
            }
        }

        public void editProperties(IAccountAuthenticatorResponse response,
                String accountType) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.editProperties(
                    new AccountAuthenticatorResponse(response), accountType);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "editProperties not supported");
            }
        }

        public void hasFeatures(IAccountAuthenticatorResponse response,
                Account account, String[] features) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.hasFeatures(
                    new AccountAuthenticatorResponse(response), account, features);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "hasFeatures not supported");
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            }
        }

        public void getAccountRemovalAllowed(IAccountAuthenticatorResponse response,
                Account account) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.getAccountRemovalAllowed(
                    new AccountAuthenticatorResponse(response), account);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAccountRemovalAllowed not supported");
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            }
        }
    }

    private void checkBinderPermission() {
        final int uid = Binder.getCallingUid();
        final String perm = Manifest.permission.ACCOUNT_MANAGER;
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("caller uid " + uid + " lacks " + perm);
        }
    }

    private Transport mTransport = new Transport();

    /**
     * @return the IBinder for the AccountAuthenticator
     */
    public final IBinder getIBinder() {
        return mTransport.asBinder();
    }

    /**
     * Returns a Bundle that contains the Intent of the activity that can be used to edit the
     * properties. In order to indicate success the activity should call response.setResult()
     * with a non-null Bundle.
     * @param response used to set the result for the request. If the Constants.INTENT_KEY
     *   is set in the bundle then this response field is to be used for sending future
     *   results if and when the Intent is started.
     * @param accountType the AccountType whose properties are to be edited.
     * @return a Bundle containing the result or the Intent to start to continue the request.
     *   If this is null then the request is considered to still be active and the result should
     *   sent later using response.
     */
    public abstract Bundle editProperties(AccountAuthenticatorResponse response,
            String accountType);

    /**
     * Adds an account of the specified accountType.
     * @param response to send the result back to the AccountManager, will never be null
     * @param accountType the type of account to add, will never be null
     * @param authTokenType the type of auth token to retrieve after adding the account, may be null
     * @param requiredFeatures a String array of authenticator-specific features that the added
     * account must support, may be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_ACCOUNT_NAME} and {@link AccountManager#KEY_ACCOUNT_TYPE} of
     * the account that was added, or
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public abstract Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
            String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException;

    /**
     * Checks that the user knows the credentials of an account.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account whose credentials are to be checked, will never be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_BOOLEAN_RESULT}, true if the check succeeded, false otherwise
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public abstract Bundle confirmCredentials(AccountAuthenticatorResponse response,
            Account account, Bundle options)
            throws NetworkErrorException;
    /**
     * Gets the authtoken for an account.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account whose credentials are to be retrieved, will never be null
     * @param authTokenType the type of auth token to retrieve, will never be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_ACCOUNT_NAME}, {@link AccountManager#KEY_ACCOUNT_TYPE},
     * and {@link AccountManager#KEY_AUTHTOKEN}, or
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public abstract Bundle getAuthToken(AccountAuthenticatorResponse response,
            Account account, String authTokenType, Bundle options)
            throws NetworkErrorException;

    /**
     * Ask the authenticator for a localized label for the given authTokenType.
     * @param authTokenType the authTokenType whose label is to be returned, will never be null
     * @return the localized label of the auth token type, may be null if the type isn't known
     */
    public abstract String getAuthTokenLabel(String authTokenType);

    /**
     * Update the locally stored credentials for an account.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account whose credentials are to be updated, will never be null
     * @param authTokenType the type of auth token to retrieve after updating the credentials,
     * may be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_ACCOUNT_NAME} and {@link AccountManager#KEY_ACCOUNT_TYPE} of
     * the account that was added, or
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public abstract Bundle updateCredentials(AccountAuthenticatorResponse response,
            Account account, String authTokenType, Bundle options) throws NetworkErrorException;

    /**
     * Checks if the account supports all the specified authenticator specific features.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account to check, will never be null
     * @param features an array of features to check, will never be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_BOOLEAN_RESULT}, true if the account has all the features,
     * false otherwise
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public abstract Bundle hasFeatures(AccountAuthenticatorResponse response,
            Account account, String[] features) throws NetworkErrorException;

    /**
     * Checks if the removal of this account is allowed.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account to check, will never be null
     * @return a Bundle result or null if the result is to be returned via the response. The result
     * will contain either:
     * <ul>
     * <li> {@link AccountManager#KEY_INTENT}, or
     * <li> {@link AccountManager#KEY_BOOLEAN_RESULT}, true if the removal of the account is
     * allowed, false otherwise
     * <li> {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} to
     * indicate an error
     * </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     * network error
     */
    public Bundle getAccountRemovalAllowed(AccountAuthenticatorResponse response,
            Account account) throws NetworkErrorException {
        final Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        return result;
    }
}
