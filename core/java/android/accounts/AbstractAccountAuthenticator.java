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

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

/**
 * Abstract base class for creating AccountAuthenticators.
 * In order to be an authenticator one must extend this class, provide implementations for the
 * abstract methods, and write a service that returns the result of {@link #getIBinder()}
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
 * The preferences attribute points to a PreferenceScreen xml hierarchy that contains
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
 * the {@link AccountAuthenticatorResponse} as
 * {@link AccountManager#KEY_ACCOUNT_AUTHENTICATOR_RESPONSE}.
 * The activity must then call {@link AccountAuthenticatorResponse#onResult} or
 * {@link AccountAuthenticatorResponse#onError} when it is complete.
 * <li> If the authenticator cannot synchronously process the request and return a result then it
 * may choose to return null and then use the AccountManagerResponse to send the result
 * when it has completed the request. This asynchronous option is not available for the
 * {@link #addAccount} method, which must complete synchronously.
 * </ul>
 * <p>
 * The following descriptions of each of the abstract authenticator methods will not describe the
 * possible asynchronous nature of the request handling and will instead just describe the input
 * parameters and the expected result.
 * <p>
 * When writing an activity to satisfy these requests one must pass in the AccountManagerResponse
 * and return the result via that response when the activity finishes (or whenever else the
 * activity author deems it is the correct time to respond).
 */
public abstract class AbstractAccountAuthenticator {
    private static final String TAG = "AccountAuthenticator";

    /**
     * Bundle key used for the {@code long} expiration time (in millis from the unix epoch) of the
     * associated auth token.
     *
     * @see #getAuthToken
     */
    public static final String KEY_CUSTOM_TOKEN_EXPIRY = "android.accounts.expiry";

    /**
     * Bundle key used for the {@link String} account type in session bundle.
     * This is used in the default implementation of
     * {@link #startAddAccountSession} and {@link startUpdateCredentialsSession}.
     */
    private static final String KEY_AUTH_TOKEN_TYPE =
            "android.accounts.AbstractAccountAuthenticato.KEY_AUTH_TOKEN_TYPE";
    /**
     * Bundle key used for the {@link String} array of required features in
     * session bundle. This is used in the default implementation of
     * {@link #startAddAccountSession} and {@link startUpdateCredentialsSession}.
     */
    private static final String KEY_REQUIRED_FEATURES =
            "android.accounts.AbstractAccountAuthenticator.KEY_REQUIRED_FEATURES";
    /**
     * Bundle key used for the {@link Bundle} options in session bundle. This is
     * used in default implementation of {@link #startAddAccountSession} and
     * {@link startUpdateCredentialsSession}.
     */
    private static final String KEY_OPTIONS =
            "android.accounts.AbstractAccountAuthenticator.KEY_OPTIONS";
    /**
     * Bundle key used for the {@link Account} account in session bundle. This is used
     * used in default implementation of {@link startUpdateCredentialsSession}.
     */
    private static final String KEY_ACCOUNT =
            "android.accounts.AbstractAccountAuthenticator.KEY_ACCOUNT";

    private final Context mContext;

    public AbstractAccountAuthenticator(Context context) {
        mContext = context;
    }

    private class Transport extends IAccountAuthenticator.Stub {
        @Override
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
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "addAccount: result " + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                } else {
                    response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "null bundle returned");
                }
            } catch (Exception e) {
                handleException(response, "addAccount", accountType, e);
            }
        }

        @Override
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
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "confirmCredentials: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "confirmCredentials", account.toString(), e);
            }
        }

        @Override
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
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "getAuthTokenLabel: result "
                            + AccountManager.sanitizeResult(result));
                }
                response.onResult(result);
            } catch (Exception e) {
                handleException(response, "getAuthTokenLabel", authTokenType, e);
            }
        }

        @Override
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
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "getAuthToken: result " + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "getAuthToken",
                        account.toString() + "," + authTokenType, e);
            }
        }

        @Override
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
                    // Result may be null.
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "updateCredentials: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "updateCredentials",
                        account.toString() + "," + authTokenType, e);
            }
        }

        @Override
        public void editProperties(IAccountAuthenticatorResponse response,
                String accountType) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.editProperties(
                    new AccountAuthenticatorResponse(response), accountType);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "editProperties", accountType, e);
            }
        }

        @Override
        public void hasFeatures(IAccountAuthenticatorResponse response,
                Account account, String[] features) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.hasFeatures(
                    new AccountAuthenticatorResponse(response), account, features);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "hasFeatures", account.toString(), e);
            }
        }

        @Override
        public void getAccountRemovalAllowed(IAccountAuthenticatorResponse response,
                Account account) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.getAccountRemovalAllowed(
                    new AccountAuthenticatorResponse(response), account);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "getAccountRemovalAllowed", account.toString(), e);
            }
        }

        @Override
        public void getAccountCredentialsForCloning(IAccountAuthenticatorResponse response,
                Account account) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result =
                        AbstractAccountAuthenticator.this.getAccountCredentialsForCloning(
                                new AccountAuthenticatorResponse(response), account);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "getAccountCredentialsForCloning", account.toString(), e);
            }
        }

        @Override
        public void addAccountFromCredentials(IAccountAuthenticatorResponse response,
                Account account,
                Bundle accountCredentials) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result =
                        AbstractAccountAuthenticator.this.addAccountFromCredentials(
                                new AccountAuthenticatorResponse(response), account,
                                accountCredentials);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "addAccountFromCredentials", account.toString(), e);
            }
        }

        @Override
        public void startAddAccountSession(IAccountAuthenticatorResponse response,
                String accountType, String authTokenType, String[] features, Bundle options)
                throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG,
                        "startAddAccountSession: accountType " + accountType
                        + ", authTokenType " + authTokenType
                        + ", features " + (features == null ? "[]" : Arrays.toString(features)));
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.startAddAccountSession(
                        new AccountAuthenticatorResponse(response), accountType, authTokenType,
                        features, options);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "startAddAccountSession: result "
                            + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "startAddAccountSession", accountType, e);
            }
        }

        @Override
        public void startUpdateCredentialsSession(
                IAccountAuthenticatorResponse response,
                Account account,
                String authTokenType,
                Bundle loginOptions) throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "startUpdateCredentialsSession: "
                        + account
                        + ", authTokenType "
                        + authTokenType);
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this
                        .startUpdateCredentialsSession(
                                new AccountAuthenticatorResponse(response),
                                account,
                                authTokenType,
                                loginOptions);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    // Result may be null.
                    if (result != null) {
                        result.keySet(); // force it to be unparcelled
                    }
                    Log.v(TAG, "startUpdateCredentialsSession: result "
                            + AccountManager.sanitizeResult(result));

                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "startUpdateCredentialsSession",
                        account.toString() + "," + authTokenType, e);

            }
        }

        @Override
        public void finishSession(
                IAccountAuthenticatorResponse response,
                String accountType,
                Bundle sessionBundle) throws RemoteException {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "finishSession: accountType " + accountType);
            }
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.finishSession(
                        new AccountAuthenticatorResponse(response), accountType, sessionBundle);
                if (result != null) {
                    result.keySet(); // force it to be unparcelled
                }
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "finishSession: result " + AccountManager.sanitizeResult(result));
                }
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "finishSession", accountType, e);

            }
        }

        @Override
        public void isCredentialsUpdateSuggested(
                IAccountAuthenticatorResponse response,
                Account account,
                String statusToken) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this
                        .isCredentialsUpdateSuggested(
                                new AccountAuthenticatorResponse(response), account, statusToken);
                if (result != null) {
                    response.onResult(result);
                }
            } catch (Exception e) {
                handleException(response, "isCredentialsUpdateSuggested", account.toString(), e);
            }
        }
    }

    private void handleException(IAccountAuthenticatorResponse response, String method,
            String data, Exception e) throws RemoteException {
        if (e instanceof NetworkErrorException) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, method + "(" + data + ")", e);
            }
            response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
        } else if (e instanceof UnsupportedOperationException) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, method + "(" + data + ")", e);
            }
            response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                    method + " not supported");
        } else if (e instanceof IllegalArgumentException) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, method + "(" + data + ")", e);
            }
            response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                    method + " not supported");
        } else {
            Log.w(TAG, method + "(" + data + ")", e);
            response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                    method + " failed");
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
     * @param options a Bundle of authenticator-specific options. It always contains
     * {@link AccountManager#KEY_CALLER_PID} and {@link AccountManager#KEY_CALLER_UID}
     * fields which will let authenticator know the identity of the caller.
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
     * Gets an authtoken for an account.
     *
     * If not {@code null}, the resultant {@link Bundle} will contain different sets of keys
     * depending on whether a token was successfully issued and, if not, whether one
     * could be issued via some {@link android.app.Activity}.
     * <p>
     * If a token cannot be provided without some additional activity, the Bundle should contain
     * {@link AccountManager#KEY_INTENT} with an associated {@link Intent}. On the other hand, if
     * there is no such activity, then a Bundle containing
     * {@link AccountManager#KEY_ERROR_CODE} and {@link AccountManager#KEY_ERROR_MESSAGE} should be
     * returned.
     * <p>
     * If a token can be successfully issued, the implementation should return the
     * {@link AccountManager#KEY_ACCOUNT_NAME} and {@link AccountManager#KEY_ACCOUNT_TYPE} of the
     * account associated with the token as well as the {@link AccountManager#KEY_AUTHTOKEN}. In
     * addition {@link AbstractAccountAuthenticator} implementations that declare themselves
     * {@code android:customTokens=true} may also provide a non-negative {@link
     * #KEY_CUSTOM_TOKEN_EXPIRY} long value containing the expiration timestamp of the expiration
     * time (in millis since the unix epoch), tokens will be cached in memory based on
     * application's packageName/signature for however long that was specified.
     * <p>
     * Implementers should assume that tokens will be cached on the basis of account and
     * authTokenType. The system may ignore the contents of the supplied options Bundle when
     * determining to re-use a cached token. Furthermore, implementers should assume a supplied
     * expiration time will be treated as non-binding advice.
     * <p>
     * Finally, note that for {@code android:customTokens=false} authenticators, tokens are cached
     * indefinitely until some client calls {@link
     * AccountManager#invalidateAuthToken(String,String)}.
     *
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account whose credentials are to be retrieved, will never be null
     * @param authTokenType the type of auth token to retrieve, will never be null
     * @param options a Bundle of authenticator-specific options. It always contains
     * {@link AccountManager#KEY_CALLER_PID} and {@link AccountManager#KEY_CALLER_UID}
     * fields which will let authenticator know the identity of the caller.
     * @return a Bundle result or null if the result is to be returned via the response.
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
     * the account whose credentials were updated, or
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

    /**
     * Returns a Bundle that contains whatever is required to clone the account on a different
     * user. The Bundle is passed to the authenticator instance in the target user via
     * {@link #addAccountFromCredentials(AccountAuthenticatorResponse, Account, Bundle)}.
     * The default implementation returns null, indicating that cloning is not supported.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account to clone, will never be null
     * @return a Bundle result or null if the result is to be returned via the response.
     * @throws NetworkErrorException
     * @see #addAccountFromCredentials(AccountAuthenticatorResponse, Account, Bundle)
     */
    public Bundle getAccountCredentialsForCloning(final AccountAuthenticatorResponse response,
            final Account account) throws NetworkErrorException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                response.onResult(result);
            }
        }).start();
        return null;
    }

    /**
     * Creates an account based on credentials provided by the authenticator instance of another
     * user on the device, who has chosen to share the account with this user.
     * @param response to send the result back to the AccountManager, will never be null
     * @param account the account to clone, will never be null
     * @param accountCredentials the Bundle containing the required credentials to create the
     * account. Contents of the Bundle are only meaningful to the authenticator. This Bundle is
     * provided by {@link #getAccountCredentialsForCloning(AccountAuthenticatorResponse, Account)}.
     * @return a Bundle result or null if the result is to be returned via the response.
     * @throws NetworkErrorException
     * @see #getAccountCredentialsForCloning(AccountAuthenticatorResponse, Account)
     */
    public Bundle addAccountFromCredentials(final AccountAuthenticatorResponse response,
            Account account,
            Bundle accountCredentials) throws NetworkErrorException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                response.onResult(result);
            }
        }).start();
        return null;
    }

    /**
     * Starts the add account session to authenticate user to an account of the
     * specified accountType. No file I/O should be performed in this call.
     * Account should be added to device only when {@link #finishSession} is
     * called after this.
     * <p>
     * Note: when overriding this method, {@link #finishSession} should be
     * overridden too.
     * </p>
     *
     * @param response to send the result back to the AccountManager, will never
     *            be null
     * @param accountType the type of account to authenticate with, will never
     *            be null
     * @param authTokenType the type of auth token to retrieve after
     *            authenticating with the account, may be null
     * @param requiredFeatures a String array of authenticator-specific features
     *            that the account authenticated with must support, may be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the
     *         response. The result will contain either:
     *         <ul>
     *         <li>{@link AccountManager#KEY_INTENT}, or
     *         <li>{@link AccountManager#KEY_ACCOUNT_SESSION_BUNDLE} for adding
     *         the account to device later, and if account is authenticated,
     *         optional {@link AccountManager#KEY_PASSWORD} and
     *         {@link AccountManager#KEY_ACCOUNT_STATUS_TOKEN} for checking the
     *         status of the account, or
     *         <li>{@link AccountManager#KEY_ERROR_CODE} and
     *         {@link AccountManager#KEY_ERROR_MESSAGE} to indicate an error
     *         </ul>
     * @throws NetworkErrorException if the authenticator could not honor the
     *             request due to a network error
     * @see #finishSession(AccountAuthenticatorResponse, String, Bundle)
     */
    public Bundle startAddAccountSession(
            final AccountAuthenticatorResponse response,
            final String accountType,
            final String authTokenType,
            final String[] requiredFeatures,
            final Bundle options)
            throws NetworkErrorException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle sessionBundle = new Bundle();
                sessionBundle.putString(KEY_AUTH_TOKEN_TYPE, authTokenType);
                sessionBundle.putStringArray(KEY_REQUIRED_FEATURES, requiredFeatures);
                sessionBundle.putBundle(KEY_OPTIONS, options);
                Bundle result = new Bundle();
                result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, sessionBundle);
                response.onResult(result);
            }

        }).start();
        return null;
    }

    /**
     * Asks user to re-authenticate for an account but defers updating the
     * locally stored credentials. No file I/O should be performed in this call.
     * Local credentials should be updated only when {@link #finishSession} is
     * called after this.
     * <p>
     * Note: when overriding this method, {@link #finishSession} should be
     * overridden too.
     * </p>
     *
     * @param response to send the result back to the AccountManager, will never
     *            be null
     * @param account the account whose credentials are to be updated, will
     *            never be null
     * @param authTokenType the type of auth token to retrieve after updating
     *            the credentials, may be null
     * @param options a Bundle of authenticator-specific options, may be null
     * @return a Bundle result or null if the result is to be returned via the
     *         response. The result will contain either:
     *         <ul>
     *         <li>{@link AccountManager#KEY_INTENT}, or
     *         <li>{@link AccountManager#KEY_ACCOUNT_SESSION_BUNDLE} for
     *         updating the locally stored credentials later, and if account is
     *         re-authenticated, optional {@link AccountManager#KEY_PASSWORD}
     *         and {@link AccountManager#KEY_ACCOUNT_STATUS_TOKEN} for checking
     *         the status of the account later, or
     *         <li>{@link AccountManager#KEY_ERROR_CODE} and
     *         {@link AccountManager#KEY_ERROR_MESSAGE} to indicate an error
     *         </ul>
     * @throws NetworkErrorException if the authenticator could not honor the
     *             request due to a network error
     * @see #finishSession(AccountAuthenticatorResponse, String, Bundle)
     */
    public Bundle startUpdateCredentialsSession(
            final AccountAuthenticatorResponse response,
            final Account account,
            final String authTokenType,
            final Bundle options) throws NetworkErrorException {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle sessionBundle = new Bundle();
                sessionBundle.putString(KEY_AUTH_TOKEN_TYPE, authTokenType);
                sessionBundle.putParcelable(KEY_ACCOUNT, account);
                sessionBundle.putBundle(KEY_OPTIONS, options);
                Bundle result = new Bundle();
                result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, sessionBundle);
                response.onResult(result);
            }

        }).start();
        return null;
    }

    /**
     * Finishes the session started by #startAddAccountSession or
     * #startUpdateCredentials by installing the account to device with
     * AccountManager, or updating the local credentials. File I/O may be
     * performed in this call.
     * <p>
     * Note: when overriding this method, {@link #startAddAccountSession} and
     * {@link #startUpdateCredentialsSession} should be overridden too.
     * </p>
     *
     * @param response to send the result back to the AccountManager, will never
     *            be null
     * @param accountType the type of account to authenticate with, will never
     *            be null
     * @param sessionBundle a bundle of session data created by
     *            {@link #startAddAccountSession} used for adding account to
     *            device, or by {@link #startUpdateCredentialsSession} used for
     *            updating local credentials.
     * @return a Bundle result or null if the result is to be returned via the
     *         response. The result will contain either:
     *         <ul>
     *         <li>{@link AccountManager#KEY_INTENT}, or
     *         <li>{@link AccountManager#KEY_ACCOUNT_NAME} and
     *         {@link AccountManager#KEY_ACCOUNT_TYPE} of the account that was
     *         added or local credentials were updated, and optional
     *         {@link AccountManager#KEY_ACCOUNT_STATUS_TOKEN} for checking
     *         the status of the account later, or
     *         <li>{@link AccountManager#KEY_ERROR_CODE} and
     *         {@link AccountManager#KEY_ERROR_MESSAGE} to indicate an error
     *         </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     *             network error
     * @see #startAddAccountSession and #startUpdateCredentialsSession
     */
    public Bundle finishSession(
            final AccountAuthenticatorResponse response,
            final String accountType,
            final Bundle sessionBundle) throws NetworkErrorException {
        if (TextUtils.isEmpty(accountType)) {
            Log.e(TAG, "Account type cannot be empty.");
            Bundle result = new Bundle();
            result.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_BAD_ARGUMENTS);
            result.putString(AccountManager.KEY_ERROR_MESSAGE,
                    "accountType cannot be empty.");
            return result;
        }

        if (sessionBundle == null) {
            Log.e(TAG, "Session bundle cannot be null.");
            Bundle result = new Bundle();
            result.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_BAD_ARGUMENTS);
            result.putString(AccountManager.KEY_ERROR_MESSAGE,
                    "sessionBundle cannot be null.");
            return result;
        }

        if (!sessionBundle.containsKey(KEY_AUTH_TOKEN_TYPE)) {
            // We cannot handle Session bundle not created by default startAddAccountSession(...)
            // nor startUpdateCredentialsSession(...) implementation. Return error.
            Bundle result = new Bundle();
            result.putInt(AccountManager.KEY_ERROR_CODE,
                    AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
            result.putString(AccountManager.KEY_ERROR_MESSAGE,
                    "Authenticator must override finishSession if startAddAccountSession"
                            + " or startUpdateCredentialsSession is overridden.");
            response.onResult(result);
            return result;
        }
        String authTokenType = sessionBundle.getString(KEY_AUTH_TOKEN_TYPE);
        Bundle options = sessionBundle.getBundle(KEY_OPTIONS);
        String[] requiredFeatures = sessionBundle.getStringArray(KEY_REQUIRED_FEATURES);
        Account account = sessionBundle.getParcelable(KEY_ACCOUNT);
        boolean containsKeyAccount = sessionBundle.containsKey(KEY_ACCOUNT);

        // Actual options passed to add account or update credentials flow.
        Bundle sessionOptions = new Bundle(sessionBundle);
        // Remove redundant extras in session bundle before passing it to addAccount(...) or
        // updateCredentials(...).
        sessionOptions.remove(KEY_AUTH_TOKEN_TYPE);
        sessionOptions.remove(KEY_REQUIRED_FEATURES);
        sessionOptions.remove(KEY_OPTIONS);
        sessionOptions.remove(KEY_ACCOUNT);

        if (options != null) {
            // options may contains old system info such as
            // AccountManager.KEY_ANDROID_PACKAGE_NAME required by the add account flow or update
            // credentials flow, we should replace with the new values of the current call added
            // to sessionBundle by AccountManager or AccountManagerService.
            options.putAll(sessionOptions);
            sessionOptions = options;
        }

        // Session bundle created by startUpdateCredentialsSession default implementation should
        // contain KEY_ACCOUNT.
        if (containsKeyAccount) {
            return updateCredentials(response, account, authTokenType, options);
        }
        // Otherwise, session bundle was created by startAddAccountSession default implementation.
        return addAccount(response, accountType, authTokenType, requiredFeatures, sessionOptions);
    }

    /**
     * Checks if update of the account credentials is suggested.
     *
     * @param response to send the result back to the AccountManager, will never be null.
     * @param account the account to check, will never be null
     * @param statusToken a String of token which can be used to check the status of locally
     *            stored credentials and if update of credentials is suggested
     * @return a Bundle result or null if the result is to be returned via the response. The result
     *         will contain either:
     *         <ul>
     *         <li>{@link AccountManager#KEY_BOOLEAN_RESULT}, true if update of account's
     *         credentials is suggested, false otherwise
     *         <li>{@link AccountManager#KEY_ERROR_CODE} and
     *         {@link AccountManager#KEY_ERROR_MESSAGE} to indicate an error
     *         </ul>
     * @throws NetworkErrorException if the authenticator could not honor the request due to a
     *             network error
     */
    public Bundle isCredentialsUpdateSuggested(
            final AccountAuthenticatorResponse response,
            Account account,
            String statusToken) throws NetworkErrorException {
        Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        return result;
    }
}
