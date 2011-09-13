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

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.SQLException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Parcelable;
import android.os.Build;
import android.util.Log;
import android.text.TextUtils;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;

import com.google.android.collect.Maps;

/**
 * This class provides access to a centralized registry of the user's
 * online accounts.  The user enters credentials (username and password) once
 * per account, granting applications access to online resources with
 * "one-click" approval.
 *
 * <p>Different online services have different ways of handling accounts and
 * authentication, so the account manager uses pluggable <em>authenticator</em>
 * modules for different <em>account types</em>.  Authenticators (which may be
 * written by third parties) handle the actual details of validating account
 * credentials and storing account information.  For example, Google, Facebook,
 * and Microsoft Exchange each have their own authenticator.
 *
 * <p>Many servers support some notion of an <em>authentication token</em>,
 * which can be used to authenticate a request to the server without sending
 * the user's actual password.  (Auth tokens are normally created with a
 * separate request which does include the user's credentials.)  AccountManager
 * can generate auth tokens for applications, so the application doesn't need to
 * handle passwords directly.  Auth tokens are normally reusable and cached by
 * AccountManager, but must be refreshed periodically.  It's the responsibility
 * of applications to <em>invalidate</em> auth tokens when they stop working so
 * the AccountManager knows it needs to regenerate them.
 *
 * <p>Applications accessing a server normally go through these steps:
 *
 * <ul>
 * <li>Get an instance of AccountManager using {@link #get(Context)}.
 *
 * <li>List the available accounts using {@link #getAccountsByType} or
 * {@link #getAccountsByTypeAndFeatures}.  Normally applications will only
 * be interested in accounts with one particular <em>type</em>, which
 * identifies the authenticator.  Account <em>features</em> are used to
 * identify particular account subtypes and capabilities.  Both the account
 * type and features are authenticator-specific strings, and must be known by
 * the application in coordination with its preferred authenticators.
 *
 * <li>Select one or more of the available accounts, possibly by asking the
 * user for their preference.  If no suitable accounts are available,
 * {@link #addAccount} may be called to prompt the user to create an
 * account of the appropriate type.
 *
 * <li><b>Important:</b> If the application is using a previously remembered
 * account selection, it must make sure the account is still in the list
 * of accounts returned by {@link #getAccountsByType}.  Requesting an auth token
 * for an account no longer on the device results in an undefined failure.
 *
 * <li>Request an auth token for the selected account(s) using one of the
 * {@link #getAuthToken} methods or related helpers.  Refer to the description
 * of each method for exact usage and error handling details.
 *
 * <li>Make the request using the auth token.  The form of the auth token,
 * the format of the request, and the protocol used are all specific to the
 * service you are accessing.  The application may use whatever network and
 * protocol libraries are useful.
 *
 * <li><b>Important:</b> If the request fails with an authentication error,
 * it could be that a cached auth token is stale and no longer honored by
 * the server.  The application must call {@link #invalidateAuthToken} to remove
 * the token from the cache, otherwise requests will continue failing!  After
 * invalidating the auth token, immediately go back to the "Request an auth
 * token" step above.  If the process fails the second time, then it can be
 * treated as a "genuine" authentication failure and the user notified or other
 * appropriate actions taken.
 * </ul>
 *
 * <p>Some AccountManager methods may need to interact with the user to
 * prompt for credentials, present options, or ask the user to add an account.
 * The caller may choose whether to allow AccountManager to directly launch the
 * necessary user interface and wait for the user, or to return an Intent which
 * the caller may use to launch the interface, or (in some cases) to install a
 * notification which the user can select at any time to launch the interface.
 * To have AccountManager launch the interface directly, the caller must supply
 * the current foreground {@link Activity} context.
 *
 * <p>Many AccountManager methods take {@link AccountManagerCallback} and
 * {@link Handler} as parameters.  These methods return immediately and
 * run asynchronously. If a callback is provided then
 * {@link AccountManagerCallback#run} will be invoked on the Handler's
 * thread when the request completes, successfully or not.
 * The result is retrieved by calling {@link AccountManagerFuture#getResult()}
 * on the {@link AccountManagerFuture} returned by the method (and also passed
 * to the callback).  This method waits for the operation to complete (if
 * necessary) and either returns the result or throws an exception if an error
 * occurred during the operation.  To make the request synchronously, call
 * {@link AccountManagerFuture#getResult()} immediately on receiving the
 * future from the method; no callback need be supplied.
 *
 * <p>Requests which may block, including
 * {@link AccountManagerFuture#getResult()}, must never be called on
 * the application's main event thread.  These operations throw
 * {@link IllegalStateException} if they are used on the main thread.
 */
public class AccountManager {
    private static final String TAG = "AccountManager";

    public static final int ERROR_CODE_REMOTE_EXCEPTION = 1;
    public static final int ERROR_CODE_NETWORK_ERROR = 3;
    public static final int ERROR_CODE_CANCELED = 4;
    public static final int ERROR_CODE_INVALID_RESPONSE = 5;
    public static final int ERROR_CODE_UNSUPPORTED_OPERATION = 6;
    public static final int ERROR_CODE_BAD_ARGUMENTS = 7;
    public static final int ERROR_CODE_BAD_REQUEST = 8;

    /**
     * Bundle key used for the {@link String} account name in results
     * from methods which return information about a particular account.
     */
    public static final String KEY_ACCOUNT_NAME = "authAccount";

    /**
     * Bundle key used for the {@link String} account type in results
     * from methods which return information about a particular account.
     */
    public static final String KEY_ACCOUNT_TYPE = "accountType";

    /**
     * Bundle key used for the auth token value in results
     * from {@link #getAuthToken} and friends.
     */
    public static final String KEY_AUTHTOKEN = "authtoken";

    /**
     * Bundle key used for an {@link Intent} in results from methods that
     * may require the caller to interact with the user.  The Intent can
     * be used to start the corresponding user interface activity.
     */
    public static final String KEY_INTENT = "intent";

    /**
     * Bundle key used to supply the password directly in options to
     * {@link #confirmCredentials}, rather than prompting the user with
     * the standard password prompt.
     */
    public static final String KEY_PASSWORD = "password";

    public static final String KEY_ACCOUNTS = "accounts";

    public static final String KEY_ACCOUNT_AUTHENTICATOR_RESPONSE = "accountAuthenticatorResponse";
    public static final String KEY_ACCOUNT_MANAGER_RESPONSE = "accountManagerResponse";
    public static final String KEY_AUTHENTICATOR_TYPES = "authenticator_types";
    public static final String KEY_AUTH_FAILED_MESSAGE = "authFailedMessage";
    public static final String KEY_AUTH_TOKEN_LABEL = "authTokenLabelKey";
    public static final String KEY_BOOLEAN_RESULT = "booleanResult";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_ERROR_MESSAGE = "errorMessage";
    public static final String KEY_USERDATA = "userdata";

    /**
     * Authenticators using 'customTokens' option will also get the UID of the
     * caller
     */
    public static final String KEY_CALLER_UID = "callerUid";
    public static final String KEY_CALLER_PID = "callerPid";

    /**
     * The Android package of the caller will be set in the options bundle by the
     * {@link AccountManager} and will be passed to the AccountManagerService and
     * to the AccountAuthenticators. The uid of the caller will be known by the
     * AccountManagerService as well as the AccountAuthenticators so they will be able to
     * verify that the package is consistent with the uid (a uid might be shared by many
     * packages).
     */
    public static final String KEY_ANDROID_PACKAGE_NAME = "androidPackageName";

    /**
     * Boolean, if set and 'customTokens' the authenticator is responsible for
     * notifications.
     * @hide
     */
    public static final String KEY_NOTIFY_ON_FAILURE = "notifyOnAuthFailure";

    public static final String ACTION_AUTHENTICATOR_INTENT =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_META_DATA_NAME =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_ATTRIBUTES_NAME = "account-authenticator";

    private final Context mContext;
    private final IAccountManager mService;
    private final Handler mMainHandler;

    /**
     * Action sent as a broadcast Intent by the AccountsService
     * when accounts are added, accounts are removed, or an
     * account's credentials (saved password, etc) are changed.
     *
     * @see #addOnAccountsUpdatedListener
     */
    public static final String LOGIN_ACCOUNTS_CHANGED_ACTION =
        "android.accounts.LOGIN_ACCOUNTS_CHANGED";

    /**
     * @hide
     */
    public AccountManager(Context context, IAccountManager service) {
        mContext = context;
        mService = service;
        mMainHandler = new Handler(mContext.getMainLooper());
    }

    /**
     * @hide used for testing only
     */
    public AccountManager(Context context, IAccountManager service, Handler handler) {
        mContext = context;
        mService = service;
        mMainHandler = handler;
    }

    /**
     * @hide for internal use only
     */
    public static Bundle sanitizeResult(Bundle result) {
        if (result != null) {
            if (result.containsKey(KEY_AUTHTOKEN)
                    && !TextUtils.isEmpty(result.getString(KEY_AUTHTOKEN))) {
                final Bundle newResult = new Bundle(result);
                newResult.putString(KEY_AUTHTOKEN, "<omitted for logging purposes>");
                return newResult;
            }
        }
        return result;
    }

    /**
     * Gets an AccountManager instance associated with a Context.
     * The {@link Context} will be used as long as the AccountManager is
     * active, so make sure to use a {@link Context} whose lifetime is
     * commensurate with any listeners registered to
     * {@link #addOnAccountsUpdatedListener} or similar methods.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>No permission is required to call this method.
     *
     * @param context The {@link Context} to use when necessary
     * @return An {@link AccountManager} instance
     */
    public static AccountManager get(Context context) {
        if (context == null) throw new IllegalArgumentException("context is null");
        return (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
    }

    /**
     * Gets the saved password associated with the account.
     * This is intended for authenticators and related code; applications
     * should get an auth token instead.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the account's authenticator.
     *
     * @param account The account to query for a password
     * @return The account's password, null if none or if the account doesn't exist
     */
    public String getPassword(final Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the user data named by "key" associated with the account.
     * This is intended for authenticators and related code to store
     * arbitrary metadata along with accounts.  The meaning of the keys
     * and values is up to the authenticator for the account.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the account's authenticator.
     *
     * @param account The account to query for user data
     * @return The user data, null if the account or key doesn't exist
     */
    public String getUserData(final Account account, final String key) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        try {
            return mService.getUserData(account, key);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Lists the currently registered authenticators.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>No permission is required to call this method.
     *
     * @return An array of {@link AuthenticatorDescription} for every
     *     authenticator known to the AccountManager service.  Empty (never
     *     null) if no authenticators are known.
     */
    public AuthenticatorDescription[] getAuthenticatorTypes() {
        try {
            return mService.getAuthenticatorTypes();
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Lists all accounts of any type registered on the device.
     * Equivalent to getAccountsByType(null).
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#GET_ACCOUNTS}.
     *
     * @return An array of {@link Account}, one for each account.  Empty
     *     (never null) if no accounts have been added.
     */
    public Account[] getAccounts() {
        try {
            return mService.getAccounts(null);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Lists all accounts of a particular type.  The account type is a
     * string token corresponding to the authenticator and useful domain
     * of the account.  For example, there are types corresponding to Google
     * and Facebook.  The exact string token to use will be published somewhere
     * associated with the authenticator in question.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#GET_ACCOUNTS}.
     *
     * @param type The type of accounts to return, null to retrieve all accounts
     * @return An array of {@link Account}, one per matching account.  Empty
     *     (never null) if no accounts of the specified type have been added.
     */
    public Account[] getAccountsByType(String type) {
        try {
            return mService.getAccounts(type);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Finds out whether a particular account has all the specified features.
     * Account features are authenticator-specific string tokens identifying
     * boolean account properties.  For example, features are used to tell
     * whether Google accounts have a particular service (such as Google
     * Calendar or Google Talk) enabled.  The feature names and their meanings
     * are published somewhere associated with the authenticator in question.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#GET_ACCOUNTS}.
     *
     * @param account The {@link Account} to test
     * @param features An array of the account features to check
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean,
     * true if the account exists and has all of the specified features.
     */
    public AccountManagerFuture<Boolean> hasFeatures(final Account account,
            final String[] features,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (features == null) throw new IllegalArgumentException("features is null");
        return new Future2Task<Boolean>(handler, callback) {
            public void doWork() throws RemoteException {
                mService.hasFeatures(mResponse, account, features);
            }
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * Lists all accounts of a type which have certain features.  The account
     * type identifies the authenticator (see {@link #getAccountsByType}).
     * Account features are authenticator-specific string tokens identifying
     * boolean account properties (see {@link #hasFeatures}).
     *
     * <p>Unlike {@link #getAccountsByType}, this method calls the authenticator,
     * which may contact the server or do other work to check account features,
     * so the method returns an {@link AccountManagerFuture}.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#GET_ACCOUNTS}.
     *
     * @param type The type of accounts to return, must not be null
     * @param features An array of the account features to require,
     *     may be null or empty
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to an array of
     *     {@link Account}, one per account of the specified type which
     *     matches the requested features.
     */
    public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(
            final String type, final String[] features,
            AccountManagerCallback<Account[]> callback, Handler handler) {
        if (type == null) throw new IllegalArgumentException("type is null");
        return new Future2Task<Account[]>(handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAccountsByFeatures(mResponse, type, features);
            }
            public Account[] bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_ACCOUNTS)) {
                    throw new AuthenticatorException("no result in response");
                }
                final Parcelable[] parcelables = bundle.getParcelableArray(KEY_ACCOUNTS);
                Account[] descs = new Account[parcelables.length];
                for (int i = 0; i < parcelables.length; i++) {
                    descs[i] = (Account) parcelables[i];
                }
                return descs;
            }
        }.start();
    }

    /**
     * Adds an account directly to the AccountManager.  Normally used by sign-up
     * wizards associated with authenticators, not directly by applications.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the added account's authenticator.
     *
     * @param account The {@link Account} to add
     * @param password The password to associate with the account, null for none
     * @param userdata String values to use for the account's userdata, null for none
     * @return True if the account was successfully added, false if the account
     *     already exists, the account is null, or another error occurs.
     */
    public boolean addAccountExplicitly(Account account, String password, Bundle userdata) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.addAccount(account, password, userdata);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes an account from the AccountManager.  Does nothing if the account
     * does not exist.  Does not delete the account from the server.
     * The authenticator may have its own policies preventing account
     * deletion, in which case the account will not be deleted.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The {@link Account} to remove
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean,
     *     true if the account has been successfully removed,
     *     false if the authenticator forbids deleting this account.
     */
    public AccountManagerFuture<Boolean> removeAccount(final Account account,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        return new Future2Task<Boolean>(handler, callback) {
            public void doWork() throws RemoteException {
                mService.removeAccount(mResponse, account);
            }
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * Removes an auth token from the AccountManager's cache.  Does nothing if
     * the auth token is not currently in the cache.  Applications must call this
     * method when the auth token is found to have expired or otherwise become
     * invalid for authenticating requests.  The AccountManager does not validate
     * or expire cached auth tokens otherwise.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS} or
     * {@link android.Manifest.permission#USE_CREDENTIALS}
     *
     * @param accountType The account type of the auth token to invalidate, must not be null
     * @param authToken The auth token to invalidate, may be null
     */
    public void invalidateAuthToken(final String accountType, final String authToken) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        try {
            if (authToken != null) {
                mService.invalidateAuthToken(accountType, authToken);
            }
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets an auth token from the AccountManager's cache.  If no auth
     * token is cached for this account, null will be returned -- a new
     * auth token will not be generated, and the server will not be contacted.
     * Intended for use by the authenticator, not directly by applications.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the account's authenticator.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The type of auth token to fetch, see {#getAuthToken}
     * @return The cached auth token for this account and type, or null if
     *     no auth token is cached or the account does not exist.
     */
    public String peekAuthToken(final Account account, final String authTokenType) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets or forgets a saved password.  This modifies the local copy of the
     * password used to automatically authenticate the user; it does
     * not change the user's account password on the server.  Intended for use
     * by the authenticator, not directly by applications.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and have the same UID as the account's authenticator.
     *
     * @param account The account to set a password for
     * @param password The password to set, null to clear the password
     */
    public void setPassword(final Account account, final String password) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Forgets a saved password.  This erases the local copy of the password;
     * it does not change the user's account password on the server.
     * Has the same effect as setPassword(account, null) but requires fewer
     * permissions, and may be used by applications or management interfaces
     * to "sign out" from an account.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}
     *
     * @param account The account whose password to clear
     */
    public void clearPassword(final Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets one userdata key for an account.  Intended by use for the
     * authenticator to stash state for itself, not directly by applications.
     * The meaning of the keys and values is up to the authenticator.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the account's authenticator.
     *
     * @param account The account to set the userdata for
     * @param key The userdata key to set.  Must not be null
     * @param value The value to set, null to clear this userdata key
     */
    public void setUserData(final Account account, final String key, final String value) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds an auth token to the AccountManager cache for an account.
     * If the account does not exist then this call has no effect.
     * Replaces any previous auth token for this account and auth token type.
     * Intended for use by the authenticator, not directly by applications.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS}
     * and to have the same UID as the account's authenticator.
     *
     * @param account The account to set an auth token for
     * @param authTokenType The type of the auth token, see {#getAuthToken}
     * @param authToken The auth token to add to the cache
     */
    public void setAuthToken(Account account, final String authTokenType, final String authToken) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        try {
            mService.setAuthToken(account, authTokenType, authToken);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * This convenience helper synchronously gets an auth token with
     * {@link #getAuthToken(Account, String, boolean, AccountManagerCallback, Handler)}.
     *
     * <p>This method may block while a network request completes, and must
     * never be made from the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The auth token type, see {#link getAuthToken}
     * @param notifyAuthFailure If true, display a notification and return null
     *     if authentication fails; if false, prompt and wait for the user to
     *     re-enter correct credentials before returning
     * @return An auth token of the specified type for this account, or null
     *     if authentication fails or none can be fetched.
     * @throws AuthenticatorException if the authenticator failed to respond
     * @throws OperationCanceledException if the request was canceled for any
     *     reason, including the user canceling a credential request
     * @throws java.io.IOException if the authenticator experienced an I/O problem
     *     creating a new auth token, usually because of network trouble
     */
    public String blockingGetAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        Bundle bundle = getAuthToken(account, authTokenType, notifyAuthFailure, null /* callback */,
                null /* handler */).getResult();
        if (bundle == null) {
            // This should never happen, but it does, occasionally. If it does return null to
            // signify that we were not able to get the authtoken.
            // TODO: remove this when the bug is found that sometimes causes a null bundle to be
            // returned
            Log.e(TAG, "blockingGetAuthToken: null was returned from getResult() for "
                    + account + ", authTokenType " + authTokenType);
            return null;
        }
        return bundle.getString(KEY_AUTHTOKEN);
    }

    /**
     * Gets an auth token of the specified type for a particular account,
     * prompting the user for credentials if necessary.  This method is
     * intended for applications running in the foreground where it makes
     * sense to ask the user directly for a password.
     *
     * <p>If a previously generated auth token is cached for this account and
     * type, then it is returned.  Otherwise, if a saved password is
     * available, it is sent to the server to generate a new auth token.
     * Otherwise, the user is prompted to enter a password.
     *
     * <p>Some authenticators have auth token <em>types</em>, whose value
     * is authenticator-dependent.  Some services use different token types to
     * access different functionality -- for example, Google uses different auth
     * tokens to access Gmail and Google Calendar for the same account.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The auth token type, an authenticator-dependent
     *     string token, must not be null
     * @param options Authenticator-specific options for the request,
     *     may be null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to prompt the user for a password
     *     if necessary; used only to call startActivity(); must not be null.
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     at least the following fields:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account you supplied
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_AUTHTOKEN} - the auth token you wanted
     * </ul>
     *
     * (Other authenticator-specific values may be returned.)  If an auth token
     * could not be fetched, {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation is canceled for
     *      any reason, incluidng the user canceling a credential request
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      creating a new auth token, usually because of network trouble
     * </ul>
     * If the account is no longer present on the device, the return value is
     * authenticator-dependent.  The caller should verify the validity of the
     * account before requesting an auth token.
     */
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, final Bundle options,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final Bundle optionsIn = options == null ? new Bundle() : options;
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        false /* notifyOnAuthFailure */, true /* expectActivityLaunch */,
                        optionsIn);
            }
        }.start();
    }

    /**
     * Gets an auth token of the specified type for a particular account,
     * optionally raising a notification if the user must enter credentials.
     * This method is intended for background tasks and services where the
     * user should not be immediately interrupted with a password prompt.
     *
     * <p>If a previously generated auth token is cached for this account and
     * type, then it is returned.  Otherwise, if a saved password is
     * available, it is sent to the server to generate a new auth token.
     * Otherwise, an {@link Intent} is returned which, when started, will
     * prompt the user for a password.  If the notifyAuthFailure parameter is
     * set, a status bar notification is also created with the same Intent,
     * alerting the user that they need to enter a password at some point.
     *
     * <p>In that case, you may need to wait until the user responds, which
     * could take hours or days or forever.  When the user does respond and
     * supply a new password, the account manager will broadcast the
     * {@link #LOGIN_ACCOUNTS_CHANGED_ACTION} Intent, which applications can
     * use to try again.
     *
     * <p>If notifyAuthFailure is not set, it is the application's
     * responsibility to launch the returned Intent at some point.
     * Either way, the result from this call will not wait for user action.
     *
     * <p>Some authenticators have auth token <em>types</em>, whose value
     * is authenticator-dependent.  Some services use different token types to
     * access different functionality -- for example, Google uses different auth
     * tokens to access Gmail and Google Calendar for the same account.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The auth token type, an authenticator-dependent
     *     string token, must not be null
     * @param notifyAuthFailure True to add a notification to prompt the
     *     user for a password if necessary, false to leave that to the caller
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     at least the following fields on success:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account you supplied
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_AUTHTOKEN} - the auth token you wanted
     * </ul>
     *
     * (Other authenticator-specific values may be returned.)  If the user
     * must enter credentials, the returned Bundle contains only
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch a prompt.
     *
     * If an error occurred, {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation is canceled for
     *      any reason, incluidng the user canceling a credential request
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      creating a new auth token, usually because of network trouble
     * </ul>
     * If the account is no longer present on the device, the return value is
     * authenticator-dependent.  The caller should verify the validity of the
     * account before requesting an auth token.
     * @deprecated use {@link #getAuthToken(Account, String, android.os.Bundle,
     * boolean, AccountManagerCallback, android.os.Handler)} instead
     */
    @Deprecated
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, 
            final boolean notifyAuthFailure,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        return getAuthToken(account, authTokenType, null, notifyAuthFailure, callback, 
                handler);
    }

    /**
     * Gets an auth token of the specified type for a particular account,
     * optionally raising a notification if the user must enter credentials.
     * This method is intended for background tasks and services where the
     * user should not be immediately interrupted with a password prompt.
     *
     * <p>If a previously generated auth token is cached for this account and
     * type, then it is returned.  Otherwise, if a saved password is
     * available, it is sent to the server to generate a new auth token.
     * Otherwise, an {@link Intent} is returned which, when started, will
     * prompt the user for a password.  If the notifyAuthFailure parameter is
     * set, a status bar notification is also created with the same Intent,
     * alerting the user that they need to enter a password at some point.
     *
     * <p>In that case, you may need to wait until the user responds, which
     * could take hours or days or forever.  When the user does respond and
     * supply a new password, the account manager will broadcast the
     * {@link #LOGIN_ACCOUNTS_CHANGED_ACTION} Intent, which applications can
     * use to try again.
     *
     * <p>If notifyAuthFailure is not set, it is the application's
     * responsibility to launch the returned Intent at some point.
     * Either way, the result from this call will not wait for user action.
     *
     * <p>Some authenticators have auth token <em>types</em>, whose value
     * is authenticator-dependent.  Some services use different token types to
     * access different functionality -- for example, Google uses different auth
     * tokens to access Gmail and Google Calendar for the same account.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The auth token type, an authenticator-dependent
     *     string token, must not be null
     * @param options Authenticator-specific options for the request,
     *     may be null or empty
     * @param notifyAuthFailure True to add a notification to prompt the
     *     user for a password if necessary, false to leave that to the caller
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     at least the following fields on success:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account you supplied
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_AUTHTOKEN} - the auth token you wanted
     * </ul>
     *
     * (Other authenticator-specific values may be returned.)  If the user
     * must enter credentials, the returned Bundle contains only
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch a prompt.
     *
     * If an error occurred, {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation is canceled for
     *      any reason, incluidng the user canceling a credential request
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      creating a new auth token, usually because of network trouble
     * </ul>
     * If the account is no longer present on the device, the return value is
     * authenticator-dependent.  The caller should verify the validity of the
     * account before requesting an auth token.
     */
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, final Bundle options,
            final boolean notifyAuthFailure,
            AccountManagerCallback<Bundle> callback, Handler handler) {

        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final Bundle optionsIn = options == null ? new Bundle() : options;
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());
        return new AmsTask(null, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        notifyAuthFailure, false /* expectActivityLaunch */, optionsIn);
            }
        }.start();
    }

    /**
     * Asks the user to add an account of a specified type.  The authenticator
     * for this account type processes this request with the appropriate user
     * interface.  If the user does elect to create a new account, the account
     * name is returned.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType The type of account to add; must not be null
     * @param authTokenType The type of auth token (see {@link #getAuthToken})
     *     this account will need to be able to generate, null for none
     * @param requiredFeatures The features (see {@link #hasFeatures}) this
     *     account must have, null for none
     * @param addAccountOptions Authenticator-specific options for the request,
     *     may be null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to prompt the user to create an
     *     account; used only to call startActivity(); if null, the prompt
     *     will not be launched directly, but the necessary {@link Intent}
     *     will be returned to the caller instead
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     these fields if activity was specified and an account was created:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account created
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * </ul>
     *
     * If no activity was specified, the returned Bundle contains only
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     * actual account creation process.  If an error occurred,
     * {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if no authenticator was registered for
     *      this account type or the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling the creation process
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      creating a new account, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> addAccount(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        final Bundle options = (addAccountOptions == null) ? new Bundle() :
            addAccountOptions;
        options.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.addAcount(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, options);
            }
        }.start();
    }

    /**
     * Confirms that the user knows the password for an account to make extra
     * sure they are the owner of the account.  The user-entered password can
     * be supplied directly, otherwise the authenticator for this account type
     * prompts the user with the appropriate interface.  This method is
     * intended for applications which want extra assurance; for example, the
     * phone lock screen uses this to let the user unlock the phone with an
     * account password if they forget the lock pattern.
     *
     * <p>If the user-entered password matches a saved password for this
     * account, the request is considered valid; otherwise the authenticator
     * verifies the password (usually by contacting the server).
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The account to confirm password knowledge for
     * @param options Authenticator-specific options for the request;
     *     if the {@link #KEY_PASSWORD} string field is present, the
     *     authenticator may use it directly rather than prompting the user;
     *     may be null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to prompt the user to enter a
     *     password; used only to call startActivity(); if null, the prompt
     *     will not be launched directly, but the necessary {@link Intent}
     *     will be returned to the caller instead
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle
     *     with these fields if activity or password was supplied and
     *     the account was successfully verified:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account created
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_BOOLEAN_RESULT} - true to indicate success
     * </ul>
     *
     * If no activity or password was specified, the returned Bundle contains
     * only {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     * password prompt.  If an error occurred,
     * {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling the password prompt
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      verifying the password, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> confirmCredentials(final Account account,
            final Bundle options,
            final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.confirmCredentials(mResponse, account, options, activity != null);
            }
        }.start();
    }

    /**
     * Asks the user to enter a new password for an account, updating the
     * saved credentials for the account.  Normally this happens automatically
     * when the server rejects credentials during an auth token fetch, but this
     * can be invoked directly to ensure we have the correct credentials stored.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The account to update credentials for
     * @param authTokenType The credentials entered must allow an auth token
     *     of this type to be created (but no actual auth token is returned);
     *     may be null
     * @param options Authenticator-specific options for the request;
     *     may be null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to prompt the user to enter a
     *     password; used only to call startActivity(); if null, the prompt
     *     will not be launched directly, but the necessary {@link Intent}
     *     will be returned to the caller instead
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle
     *     with these fields if an activity was supplied and the account
     *     credentials were successfully updated:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account created
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * </ul>
     *
     * If no activity was specified, the returned Bundle contains only
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     * password prompt.  If an error occurred,
     * {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling the password prompt
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      verifying the password, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> updateCredentials(final Account account,
            final String authTokenType,
            final Bundle options, final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.updateCredentials(mResponse, account, authTokenType, activity != null,
                        options);
            }
        }.start();
    }

    /**
     * Offers the user an opportunity to change an authenticator's settings.
     * These properties are for the authenticator in general, not a particular
     * account.  Not all authenticators support this method.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType The account type associated with the authenticator
     *     to adjust
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to adjust authenticator settings;
     *     used only to call startActivity(); if null, the settings dialog will
     *     not be launched directly, but the necessary {@link Intent} will be
     *     returned to the caller instead
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle
     *     which is empty if properties were edited successfully, or
     *     if no activity was specified, contains only {@link #KEY_INTENT}
     *     needed to launch the authenticator's settings dialog.
     *     If an error occurred, {@link AccountManagerFuture#getResult()}
     *     throws:
     * <ul>
     * <li> {@link AuthenticatorException} if no authenticator was registered for
     *      this account type or the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling the settings dialog
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      updating settings, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> editProperties(final String accountType,
            final Activity activity, final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.editProperties(mResponse, accountType, activity != null);
            }
        }.start();
    }

    private void ensureNotOnMainThread() {
        final Looper looper = Looper.myLooper();
        if (looper != null && looper == mContext.getMainLooper()) {
            final IllegalStateException exception = new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
            Log.e(TAG, "calling this from your main thread can lead to deadlock and/or ANRs",
                    exception);
            if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                throw exception;
            }
        }
    }

    private void postToHandler(Handler handler, final AccountManagerCallback<Bundle> callback,
            final AccountManagerFuture<Bundle> future) {
        handler = handler == null ? mMainHandler : handler;
        handler.post(new Runnable() {
            public void run() {
                callback.run(future);
            }
        });
    }

    private void postToHandler(Handler handler, final OnAccountsUpdateListener listener,
            final Account[] accounts) {
        final Account[] accountsCopy = new Account[accounts.length];
        // send a copy to make sure that one doesn't
        // change what another sees
        System.arraycopy(accounts, 0, accountsCopy, 0, accountsCopy.length);
        handler = (handler == null) ? mMainHandler : handler;
        handler.post(new Runnable() {
            public void run() {
                try {
                    listener.onAccountsUpdated(accountsCopy);
                } catch (SQLException e) {
                    // Better luck next time.  If the problem was disk-full,
                    // the STORAGE_OK intent will re-trigger the update.
                    Log.e(TAG, "Can't update accounts", e);
                }
            }
        });
    }

    private abstract class AmsTask extends FutureTask<Bundle> implements AccountManagerFuture<Bundle> {
        final IAccountManagerResponse mResponse;
        final Handler mHandler;
        final AccountManagerCallback<Bundle> mCallback;
        final Activity mActivity;
        public AmsTask(Activity activity, Handler handler, AccountManagerCallback<Bundle> callback) {
            super(new Callable<Bundle>() {
                public Bundle call() throws Exception {
                    throw new IllegalStateException("this should never be called");
                }
            });

            mHandler = handler;
            mCallback = callback;
            mActivity = activity;
            mResponse = new Response();
        }

        public final AccountManagerFuture<Bundle> start() {
            try {
                doWork();
            } catch (RemoteException e) {
                setException(e);
            }
            return this;
        }

        protected void set(Bundle bundle) {
            // TODO: somehow a null is being set as the result of the Future. Log this
            // case to help debug where this is occurring. When this bug is fixed this
            // condition statement should be removed.
            if (bundle == null) {
                Log.e(TAG, "the bundle must not be null", new Exception());
            }
            super.set(bundle);
        }

        public abstract void doWork() throws RemoteException;

        private Bundle internalGetResult(Long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            if (!isDone()) {
                ensureNotOnMainThread();
            }
            try {
                if (timeout == null) {
                    return get();
                } else {
                    return get(timeout, unit);
                }
            } catch (CancellationException e) {
                throw new OperationCanceledException();
            } catch (TimeoutException e) {
                // fall through and cancel
            } catch (InterruptedException e) {
                // fall through and cancel
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else if (cause instanceof UnsupportedOperationException) {
                    throw new AuthenticatorException(cause);
                } else if (cause instanceof AuthenticatorException) {
                    throw (AuthenticatorException) cause;
                } else if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                } else {
                    throw new IllegalStateException(cause);
                }
            } finally {
                cancel(true /* interrupt if running */);
            }
            throw new OperationCanceledException();
        }

        public Bundle getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(null, null);
        }

        public Bundle getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(timeout, unit);
        }

        protected void done() {
            if (mCallback != null) {
                postToHandler(mHandler, mCallback, this);
            }
        }

        /** Handles the responses from the AccountManager */
        private class Response extends IAccountManagerResponse.Stub {
            public void onResult(Bundle bundle) {
                Intent intent = bundle.getParcelable(KEY_INTENT);
                if (intent != null && mActivity != null) {
                    // since the user provided an Activity we will silently start intents
                    // that we see
                    mActivity.startActivity(intent);
                    // leave the Future running to wait for the real response to this request
                } else if (bundle.getBoolean("retry")) {
                    try {
                        doWork();
                    } catch (RemoteException e) {
                        // this will only happen if the system process is dead, which means
                        // we will be dying ourselves
                    }
                } else {
                    set(bundle);
                }
            }

            public void onError(int code, String message) {
                if (code == ERROR_CODE_CANCELED) {
                    // the authenticator indicated that this request was canceled, do so now
                    cancel(true /* mayInterruptIfRunning */);
                    return;
                }
                setException(convertErrorToException(code, message));
            }
        }

    }

    private abstract class BaseFutureTask<T> extends FutureTask<T> {
        final public IAccountManagerResponse mResponse;
        final Handler mHandler;

        public BaseFutureTask(Handler handler) {
            super(new Callable<T>() {
                public T call() throws Exception {
                    throw new IllegalStateException("this should never be called");
                }
            });
            mHandler = handler;
            mResponse = new Response();
        }

        public abstract void doWork() throws RemoteException;

        public abstract T bundleToResult(Bundle bundle) throws AuthenticatorException;

        protected void postRunnableToHandler(Runnable runnable) {
            Handler handler = (mHandler == null) ? mMainHandler : mHandler;
            handler.post(runnable);
        }

        protected void startTask() {
            try {
                doWork();
            } catch (RemoteException e) {
                setException(e);
            }
        }

        protected class Response extends IAccountManagerResponse.Stub {
            public void onResult(Bundle bundle) {
                try {
                    T result = bundleToResult(bundle);
                    if (result == null) {
                        return;
                    }
                    set(result);
                    return;
                } catch (ClassCastException e) {
                    // we will set the exception below
                } catch (AuthenticatorException e) {
                    // we will set the exception below
                }
                onError(ERROR_CODE_INVALID_RESPONSE, "no result in response");
            }

            public void onError(int code, String message) {
                if (code == ERROR_CODE_CANCELED) {
                    cancel(true /* mayInterruptIfRunning */);
                    return;
                }
                setException(convertErrorToException(code, message));
            }
        }
    }

    private abstract class Future2Task<T>
            extends BaseFutureTask<T> implements AccountManagerFuture<T> {
        final AccountManagerCallback<T> mCallback;
        public Future2Task(Handler handler, AccountManagerCallback<T> callback) {
            super(handler);
            mCallback = callback;
        }

        protected void done() {
            if (mCallback != null) {
                postRunnableToHandler(new Runnable() {
                    public void run() {
                        mCallback.run(Future2Task.this);
                    }
                });
            }
        }

        public Future2Task<T> start() {
            startTask();
            return this;
        }

        private T internalGetResult(Long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            if (!isDone()) {
                ensureNotOnMainThread();
            }
            try {
                if (timeout == null) {
                    return get();
                } else {
                    return get(timeout, unit);
                }
            } catch (InterruptedException e) {
                // fall through and cancel
            } catch (TimeoutException e) {
                // fall through and cancel
            } catch (CancellationException e) {
                // fall through and cancel
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else if (cause instanceof UnsupportedOperationException) {
                    throw new AuthenticatorException(cause);
                } else if (cause instanceof AuthenticatorException) {
                    throw (AuthenticatorException) cause;
                } else if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                } else {
                    throw new IllegalStateException(cause);
                }
            } finally {
                cancel(true /* interrupt if running */);
            }
            throw new OperationCanceledException();
        }

        public T getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(null, null);
        }

        public T getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(timeout, unit);
        }

    }

    private Exception convertErrorToException(int code, String message) {
        if (code == ERROR_CODE_NETWORK_ERROR) {
            return new IOException(message);
        }

        if (code == ERROR_CODE_UNSUPPORTED_OPERATION) {
            return new UnsupportedOperationException(message);
        }

        if (code == ERROR_CODE_INVALID_RESPONSE) {
            return new AuthenticatorException(message);
        }

        if (code == ERROR_CODE_BAD_ARGUMENTS) {
            return new IllegalArgumentException(message);
        }

        return new AuthenticatorException(message);
    }

    private class GetAuthTokenByTypeAndFeaturesTask
            extends AmsTask implements AccountManagerCallback<Bundle> {
        GetAuthTokenByTypeAndFeaturesTask(final String accountType, final String authTokenType,
                final String[] features, Activity activityForPrompting,
                final Bundle addAccountOptions, final Bundle loginOptions,
                AccountManagerCallback<Bundle> callback, Handler handler) {
            super(activityForPrompting, handler, callback);
            if (accountType == null) throw new IllegalArgumentException("account type is null");
            mAccountType = accountType;
            mAuthTokenType = authTokenType;
            mFeatures = features;
            mAddAccountOptions = addAccountOptions;
            mLoginOptions = loginOptions;
            mMyCallback = this;
        }
        volatile AccountManagerFuture<Bundle> mFuture = null;
        final String mAccountType;
        final String mAuthTokenType;
        final String[] mFeatures;
        final Bundle mAddAccountOptions;
        final Bundle mLoginOptions;
        final AccountManagerCallback<Bundle> mMyCallback;
        private volatile int mNumAccounts = 0;

        public void doWork() throws RemoteException {
            getAccountsByTypeAndFeatures(mAccountType, mFeatures,
                    new AccountManagerCallback<Account[]>() {
                        public void run(AccountManagerFuture<Account[]> future) {
                            Account[] accounts;
                            try {
                                accounts = future.getResult();
                            } catch (OperationCanceledException e) {
                                setException(e);
                                return;
                            } catch (IOException e) {
                                setException(e);
                                return;
                            } catch (AuthenticatorException e) {
                                setException(e);
                                return;
                            }

                            mNumAccounts = accounts.length;

                            if (accounts.length == 0) {
                                if (mActivity != null) {
                                    // no accounts, add one now. pretend that the user directly
                                    // made this request
                                    mFuture = addAccount(mAccountType, mAuthTokenType, mFeatures,
                                            mAddAccountOptions, mActivity, mMyCallback, mHandler);
                                } else {
                                    // send result since we can't prompt to add an account
                                    Bundle result = new Bundle();
                                    result.putString(KEY_ACCOUNT_NAME, null);
                                    result.putString(KEY_ACCOUNT_TYPE, null);
                                    result.putString(KEY_AUTHTOKEN, null);
                                    try {
                                        mResponse.onResult(result);
                                    } catch (RemoteException e) {
                                        // this will never happen
                                    }
                                    // we are done
                                }
                            } else if (accounts.length == 1) {
                                // have a single account, return an authtoken for it
                                if (mActivity == null) {
                                    mFuture = getAuthToken(accounts[0], mAuthTokenType,
                                            false /* notifyAuthFailure */, mMyCallback, mHandler);
                                } else {
                                    mFuture = getAuthToken(accounts[0],
                                            mAuthTokenType, mLoginOptions,
                                            mActivity, mMyCallback, mHandler);
                                }
                            } else {
                                if (mActivity != null) {
                                    IAccountManagerResponse chooseResponse =
                                            new IAccountManagerResponse.Stub() {
                                        public void onResult(Bundle value) throws RemoteException {
                                            Account account = new Account(
                                                    value.getString(KEY_ACCOUNT_NAME),
                                                    value.getString(KEY_ACCOUNT_TYPE));
                                            mFuture = getAuthToken(account, mAuthTokenType, mLoginOptions,
                                                    mActivity, mMyCallback, mHandler);
                                        }

                                        public void onError(int errorCode, String errorMessage)
                                                throws RemoteException {
                                            mResponse.onError(errorCode, errorMessage);
                                        }
                                    };
                                    // have many accounts, launch the chooser
                                    Intent intent = new Intent();
                                    intent.setClassName("android",
                                            "android.accounts.ChooseAccountActivity");
                                    intent.putExtra(KEY_ACCOUNTS, accounts);
                                    intent.putExtra(KEY_ACCOUNT_MANAGER_RESPONSE,
                                            new AccountManagerResponse(chooseResponse));
                                    mActivity.startActivity(intent);
                                    // the result will arrive via the IAccountManagerResponse
                                } else {
                                    // send result since we can't prompt to select an account
                                    Bundle result = new Bundle();
                                    result.putString(KEY_ACCOUNTS, null);
                                    try {
                                        mResponse.onResult(result);
                                    } catch (RemoteException e) {
                                        // this will never happen
                                    }
                                    // we are done
                                }
                            }
                        }}, mHandler);
        }

        public void run(AccountManagerFuture<Bundle> future) {
            try {
                final Bundle result = future.getResult();
                if (mNumAccounts == 0) {
                    final String accountName = result.getString(KEY_ACCOUNT_NAME);
                    final String accountType = result.getString(KEY_ACCOUNT_TYPE);
                    if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) {
                        setException(new AuthenticatorException("account not in result"));
                        return;
                    }
                    final Account account = new Account(accountName, accountType);
                    mNumAccounts = 1;
                    getAuthToken(account, mAuthTokenType, null /* options */, mActivity,
                            mMyCallback, mHandler);
                    return;
                }
                set(result);
            } catch (OperationCanceledException e) {
                cancel(true /* mayInterruptIfRUnning */);
            } catch (IOException e) {
                setException(e);
            } catch (AuthenticatorException e) {
                setException(e);
            }
        }
    }

    /**
     * This convenience helper combines the functionality of
     * {@link #getAccountsByTypeAndFeatures}, {@link #getAuthToken}, and
     * {@link #addAccount}.
     *
     * <p>This method gets a list of the accounts matching the
     * specified type and feature set; if there is exactly one, it is
     * used; if there are more than one, the user is prompted to pick one;
     * if there are none, the user is prompted to add one.  Finally,
     * an auth token is acquired for the chosen account.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType The account type required
     *     (see {@link #getAccountsByType}), must not be null
     * @param authTokenType The desired auth token type
     *     (see {@link #getAuthToken}), must not be null
     * @param features Required features for the account
     *     (see {@link #getAccountsByTypeAndFeatures}), may be null or empty
     * @param activity The {@link Activity} context to use for launching new
     *     sub-Activities to prompt to add an account, select an account,
     *     and/or enter a password, as necessary; used only to call
     *     startActivity(); should not be null
     * @param addAccountOptions Authenticator-specific options to use for
     *     adding new accounts; may be null or empty
     * @param getAuthTokenOptions Authenticator-specific options to use for
     *     getting auth tokens; may be null or empty
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     at least the following fields:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_AUTHTOKEN} - the auth token you wanted
     * </ul>
     *
     * If an error occurred, {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if no authenticator was registered for
     *      this account type or the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling any operation
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      updating settings, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(
            final String accountType, final String authTokenType, final String[] features,
            final Activity activity, final Bundle addAccountOptions,
            final Bundle getAuthTokenOptions,
            final AccountManagerCallback<Bundle> callback, final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("account type is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final GetAuthTokenByTypeAndFeaturesTask task =
                new GetAuthTokenByTypeAndFeaturesTask(accountType, authTokenType, features,
                activity, addAccountOptions, getAuthTokenOptions, callback, handler);
        task.start();
        return task;
    }

    private final HashMap<OnAccountsUpdateListener, Handler> mAccountsUpdatedListeners =
            Maps.newHashMap();

    /**
     * BroadcastReceiver that listens for the LOGIN_ACCOUNTS_CHANGED_ACTION intent
     * so that it can read the updated list of accounts and send them to the listener
     * in mAccountsUpdatedListeners.
     */
    private final BroadcastReceiver mAccountsChangedBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(final Context context, final Intent intent) {
            final Account[] accounts = getAccounts();
            // send the result to the listeners
            synchronized (mAccountsUpdatedListeners) {
                for (Map.Entry<OnAccountsUpdateListener, Handler> entry :
                        mAccountsUpdatedListeners.entrySet()) {
                    postToHandler(entry.getValue(), entry.getKey(), accounts);
                }
            }
        }
    };

    /**
     * Adds an {@link OnAccountsUpdateListener} to this instance of the
     * {@link AccountManager}.  This listener will be notified whenever the
     * list of accounts on the device changes.
     *
     * <p>As long as this listener is present, the AccountManager instance
     * will not be garbage-collected, and neither will the {@link Context}
     * used to retrieve it, which may be a large Activity instance.  To avoid
     * memory leaks, you must remove this listener before then.  Normally
     * listeners are added in an Activity or Service's {@link Activity#onCreate}
     * and removed in {@link Activity#onDestroy}.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>No permission is required to call this method.
     *
     * @param listener The listener to send notifications to
     * @param handler {@link Handler} identifying the thread to use
     *     for notifications, null for the main thread
     * @param updateImmediately If true, the listener will be invoked
     *     (on the handler thread) right away with the current account list
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was already added
     */
    public void addOnAccountsUpdatedListener(final OnAccountsUpdateListener listener,
            Handler handler, boolean updateImmediately) {
        if (listener == null) {
            throw new IllegalArgumentException("the listener is null");
        }
        synchronized (mAccountsUpdatedListeners) {
            if (mAccountsUpdatedListeners.containsKey(listener)) {
                throw new IllegalStateException("this listener is already added");
            }
            final boolean wasEmpty = mAccountsUpdatedListeners.isEmpty();

            mAccountsUpdatedListeners.put(listener, handler);

            if (wasEmpty) {
                // Register a broadcast receiver to monitor account changes
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(LOGIN_ACCOUNTS_CHANGED_ACTION);
                // To recover from disk-full.
                intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
                mContext.registerReceiver(mAccountsChangedBroadcastReceiver, intentFilter);
            }
        }

        if (updateImmediately) {
            postToHandler(handler, listener, getAccounts());
        }
    }

    /**
     * Removes an {@link OnAccountsUpdateListener} previously registered with
     * {@link #addOnAccountsUpdatedListener}.  The listener will no longer
     * receive notifications of account changes.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>No permission is required to call this method.
     *
     * @param listener The previously added listener to remove
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was not already added
     */
    public void removeOnAccountsUpdatedListener(OnAccountsUpdateListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        synchronized (mAccountsUpdatedListeners) {
            if (!mAccountsUpdatedListeners.containsKey(listener)) {
                Log.e(TAG, "Listener was not previously added");
                return;
            }
            mAccountsUpdatedListeners.remove(listener);
            if (mAccountsUpdatedListeners.isEmpty()) {
                mContext.unregisterReceiver(mAccountsChangedBroadcastReceiver);
            }
        }
    }
}
