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
import android.util.Log;

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
 * A class that helps with interactions with the AccountManager Service. It provides
 * methods to allow for account, password, and authtoken management for all accounts on the
 * device. One accesses the {@link AccountManager} by calling:
 * <pre>
 *    AccountManager accountManager = AccountManager.get(context);
 * </pre>
 *
 * <p>
 * The AccountManager Service provides storage for the accounts known to the system,
 * provides methods to manage them, and allows the registration of authenticators to
 * which operations such as addAccount and getAuthToken are delegated.
 * <p>
 * Many of the calls take an {@link AccountManagerCallback} and {@link Handler} as parameters.
 * These calls return immediately but run asynchronously. If a callback is provided then
 * {@link AccountManagerCallback#run} will be invoked wen the request completes, successfully
 * or not. An {@link AccountManagerFuture} is returned by these requests and also passed into the
 * callback. The result if retrieved by calling {@link AccountManagerFuture#getResult()} which
 * either returns the result or throws an exception as appropriate.
 * <p>
 * The asynchronous request can be made blocking by not providing a callback and instead
 * calling {@link AccountManagerFuture#getResult()} on the future that is returned. This will
 * cause the running thread to block until the result is returned. Keep in mind that one
 * should not block the main thread in this way. Instead one should either use a callback,
 * thus making the call asynchronous, or make the blocking call on a separate thread.
 * <p>
 * If one wants to ensure that the callback is invoked from a specific handler then they should
 * pass the handler to the request. This makes it easier to ensure thread-safety by running
 * all of one's logic from a single handler.
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

    public static final String KEY_ACCOUNTS = "accounts";
    public static final String KEY_AUTHENTICATOR_TYPES = "authenticator_types";
    public static final String KEY_USERDATA = "userdata";
    public static final String KEY_AUTHTOKEN = "authtoken";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_ACCOUNT_NAME = "authAccount";
    public static final String KEY_ACCOUNT_TYPE = "accountType";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_ERROR_MESSAGE = "errorMessage";
    public static final String KEY_INTENT = "intent";
    public static final String KEY_BOOLEAN_RESULT = "booleanResult";
    public static final String KEY_ACCOUNT_AUTHENTICATOR_RESPONSE = "accountAuthenticatorResponse";
    public static final String KEY_ACCOUNT_MANAGER_RESPONSE = "accountManagerResponse";
    public static final String KEY_AUTH_FAILED_MESSAGE = "authFailedMessage";
    public static final String KEY_AUTH_TOKEN_LABEL = "authTokenLabelKey";
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
     * when accounts are added to and/or removed from the device's
     * database.
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
     * Retrieve an AccountManager instance that is associated with the context that is passed in.
     * Certain calls such as {@link #addOnAccountsUpdatedListener} use this context internally,
     * so the caller must take care to use a {@link Context} whose lifetime is associated with
     * the listener registration.
     * @param context The {@link Context} to use when necessary
     * @return an {@link AccountManager} instance that is associated with context
     */
    public static AccountManager get(Context context) {
        return (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
    }

    /**
     * Get the password that is associated with the account. Returns null if the account does
     * not exist.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     */
    public String getPassword(final Account account) {
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the user data named by "key" that is associated with the account.
     * Returns null if the account does not exist or if it does not have a value for key.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     */
    public String getUserData(final Account account, final String key) {
        try {
            return mService.getUserData(account, key);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Query the AccountManager Service for an array that contains a
     * {@link AuthenticatorDescription} for each registered authenticator.
     * @return an array that contains all the authenticators known to the AccountManager service.
     * This array will be empty if there are no authenticators and will never return null.
     * <p>
     * No permission is required to make this call.
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
     * Query the AccountManager Service for all accounts.
     * @return an array that contains all the accounts known to the AccountManager service.
     * This array will be empty if there are no accounts and will never return null.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#GET_ACCOUNTS}
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
     * Query the AccountManager for the set of accounts that have a given type. If null
     * is passed as the type than all accounts are returned.
     * @param type the account type by which to filter, or null to get all accounts
     * @return an array that contains the accounts that match the specified type. This array
     * will be empty if no accounts match. It will never return null.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#GET_ACCOUNTS}
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
     * Add an account to the AccountManager's set of known accounts. 
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     * @param account The account to add
     * @param password The password to associate with the account. May be null.
     * @param userdata A bundle of key/value pairs to set as the account's userdata. May be null.
     * @return true if the account was sucessfully added, false otherwise, for example,
     * if the account already exists or if the account is null
     */
    public boolean addAccountExplicitly(Account account, String password, Bundle userdata) {
        try {
            return mService.addAccount(account, password, userdata);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes the given account. If this account does not exist then this call has no effect.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The {@link Account} to remove
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Boolean} that is true if the account is successfully removed
     * or false if the authenticator refuses to remove the account.
     */
    public AccountManagerFuture<Boolean> removeAccount(final Account account,
            AccountManagerCallback<Boolean> callback, Handler handler) {
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
     * Removes the given authtoken. If this authtoken does not exist for the given account type
     * then this call has no effect.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     * @param accountType the account type of the authtoken to invalidate
     * @param authToken the authtoken to invalidate
     */
    public void invalidateAuthToken(final String accountType, final String authToken) {
        try {
            mService.invalidateAuthToken(accountType, authToken);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the authtoken named by "authTokenType" for the specified account if it is cached
     * by the AccountManager. If no authtoken is cached then null is returned rather than
     * asking the authenticaticor to generate one. If the account or the
     * authtoken do not exist then null is returned.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     * @param account the account whose authtoken is to be retrieved, must not be null
     * @param authTokenType the type of authtoken to retrieve
     * @return an authtoken for the given account and authTokenType, if one is cached by the
     * AccountManager, null otherwise.
     */
    public String peekAuthToken(final Account account, final String authTokenType) {
        if (account == null) {
            Log.e(TAG, "peekAuthToken: the account must not be null");
            return null;
        }
        if (authTokenType == null) {
            return null;
        }
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the password for the account. The password may be null. If the account does not exist
     * then this call has no affect.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     * @param account the account whose password is to be set. Must not be null.
     * @param password the password to set for the account. May be null.
     */
    public void setPassword(final Account account, final String password) {
        if (account == null) {
            Log.e(TAG, "the account must not be null");
            return;
        }
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the password for account to null. If the account does not exist then this call
     * has no effect.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     * @param account the account whose password is to be cleared. Must not be null.
     */
    public void clearPassword(final Account account) {
        if (account == null) {
            Log.e(TAG, "the account must not be null");
            return;
        }
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets account's userdata named "key" to the specified value. If the account does not
     * exist then this call has no effect.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     * @param account the account whose userdata is to be set. Must not be null.
     * @param key the key of the userdata to set. Must not be null.
     * @param value the value to set. May be null.
     */
    public void setUserData(final Account account, final String key, final String value) {
        if (account == null) {
            Log.e(TAG, "the account must not be null");
            return;
        }
        if (key == null) {
            Log.e(TAG, "the key must not be null");
            return;
        }
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the authtoken named by "authTokenType" to the value specified by authToken.
     * If the account does not exist then this call has no effect.
     * <p>
     * Requires that the caller has permission
     * {@link android.Manifest.permission#AUTHENTICATE_ACCOUNTS} and is running
     * with the same UID as the Authenticator for the account.
     * @param account the account whose authtoken is to be set. Must not be null.
     * @param authTokenType the type of the authtoken to set. Must not be null.
     * @param authToken the authToken to set. May be null.
     */
    public void setAuthToken(Account account, final String authTokenType, final String authToken) {
        try {
            mService.setAuthToken(account, authTokenType, authToken);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenience method that makes a blocking call to
     * {@link #getAuthToken(Account, String, boolean, AccountManagerCallback, Handler)}
     * then extracts and returns the value of {@link #KEY_AUTHTOKEN} from its result.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#USE_CREDENTIALS}.
     * @param account the account whose authtoken is to be retrieved, must not be null
     * @param authTokenType the type of authtoken to retrieve
     * @param notifyAuthFailure if true, cause the AccountManager to put up a "sign-on" notification
     * for the account if no authtoken is cached by the AccountManager and the the authenticator
     * does not have valid credentials to get an authtoken.
     * @return an authtoken for the given account and authTokenType, if one is cached by the
     * AccountManager, null otherwise.
     * @throws AuthenticatorException if the authenticator is not present, unreachable or returns
     * an invalid response.
     * @throws OperationCanceledException if the request is canceled for any reason
     * @throws java.io.IOException if the authenticator experiences an IOException while attempting
     * to communicate with its backend server.
     */
    public String blockingGetAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {
        Bundle bundle = getAuthToken(account, authTokenType, notifyAuthFailure, null /* callback */,
                null /* handler */).getResult();
        return bundle.getString(KEY_AUTHTOKEN);
    }

    /**
     * Request that an authtoken of the specified type be returned for an account.
     * If the Account Manager has a cached authtoken of the requested type then it will
     * service the request itself. Otherwise it will pass the request on to the authenticator.
     * The authenticator can try to service this request with information it already has stored
     * in the AccountManager but may need to launch an activity to prompt the
     * user to enter credentials. If it is able to retrieve the authtoken it will be returned
     * in the result.
     * <p>
     * If the authenticator needs to prompt the user for credentials it will return an intent to
     * the activity that will do the prompting. If an activity is supplied then that activity
     * will be used to launch the intent and the result will come from it. Otherwise a result will
     * be returned that contains the intent.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account whose credentials are to be updated.
     * @param authTokenType the auth token to retrieve as part of updating the credentials.
     * May be null.
     * @param options authenticator specific options for the request
     * @param activity If the authenticator returns a {@link #KEY_INTENT} in the result then
     * the intent will be started with this activity. If activity is null then the result will
     * be returned as-is.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME}, {@link #KEY_ACCOUNT_TYPE} and {@link #KEY_AUTHTOKEN}
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, final Bundle options,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (activity == null) throw new IllegalArgumentException("activity is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        false /* notifyOnAuthFailure */, true /* expectActivityLaunch */,
                        options);
            }
        }.start();
    }

    /**
     * Request that an authtoken of the specified type be returned for an account.
     * If the Account Manager has a cached authtoken of the requested type then it will
     * service the request itself. Otherwise it will pass the request on to the authenticator.
     * The authenticator can try to service this request with information it already has stored
     * in the AccountManager but may need to launch an activity to prompt the
     * user to enter credentials. If it is able to retrieve the authtoken it will be returned
     * in the result.
     * <p>
     * If the authenticator needs to prompt the user for credentials it will return an intent for
     * an activity that will do the prompting. If an intent is returned and notifyAuthFailure
     * is true then a notification will be created that launches this intent.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#USE_CREDENTIALS}.
     *
     * @param account The account whose credentials are to be updated.
     * @param authTokenType the auth token to retrieve as part of updating the credentials.
     * May be null.
     * @param notifyAuthFailure if true and the authenticator returns a {@link #KEY_INTENT} in the
     * result then a "sign-on needed" notification will be created that will launch this intent.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, which is to be used to prompt the user for the credentials
     * <li> {@link #KEY_ACCOUNT_NAME}, {@link #KEY_ACCOUNT_TYPE} and {@link #KEY_AUTHTOKEN}
     * if the authenticator is able to retrieve the auth token
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, final boolean notifyAuthFailure,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        return new AmsTask(null, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        notifyAuthFailure, false /* expectActivityLaunch */, null /* options */);
            }
        }.start();
    }

    /**
     * Request that an account be added with the given accountType. This request
     * is processed by the authenticator for the account type. If no authenticator is registered
     * in the system then {@link AuthenticatorException} is thrown.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType The type of account to add. This must not be null.
     * @param authTokenType The account that is added should be able to service this auth token
     * type. This may be null.
     * @param requiredFeatures The account that is added should support these features.
     * This array may be null or empty.
     * @param addAccountOptions A bundle of authenticator-specific options that is passed on
     * to the authenticator. This may be null.
     * @param activity If the authenticator returns a {@link #KEY_INTENT} in the result then
     * the intent will be started with this activity. If activity is null then the result will
     * be returned as-is.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, or
     * <li> {@link #KEY_ACCOUNT_NAME}, {@link #KEY_ACCOUNT_TYPE}
     * and {@link #KEY_AUTHTOKEN} (if an authTokenType was specified).
     * </ul>
     */
    public AccountManagerFuture<Bundle> addAccount(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                if (accountType == null) {
                    Log.e(TAG, "the account must not be null");
                    // to unblock caller waiting on Future.get()
                    set(new Bundle()); 
                    return;
                }
                mService.addAcount(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, addAccountOptions);
            }
        }.start();
    }

    public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(
            final String type, final String[] features,
            AccountManagerCallback<Account[]> callback, Handler handler) {
        return new Future2Task<Account[]>(handler, callback) {
            public void doWork() throws RemoteException {
                if (type == null) {
                    Log.e(TAG, "Type is null");
                    set(new Account[0]);
                    return;
                }
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
     * Requests that the authenticator checks that the user knows the credentials for the account.
     * This is typically done by returning an intent to an activity that prompts the user to
     * enter the credentials. This request
     * is processed by the authenticator for the account. If no matching authenticator is
     * registered in the system then {@link AuthenticatorException} is thrown.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The account whose credentials are to be checked
     * @param options authenticator specific options for the request
     * @param activity If the authenticator returns a {@link #KEY_INTENT} in the result then
     * the intent will be started with this activity. If activity is null then the result will
     * be returned as-is.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, which is to be used to prompt the user for the credentials
     * <li> {@link #KEY_ACCOUNT_NAME} and {@link #KEY_ACCOUNT_TYPE} if the user enters the correct
     * credentials
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> confirmCredentials(final Account account,
            final Bundle options,
            final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.confirmCredentials(mResponse, account, options, activity != null);
            }
        }.start();
    }

    /**
     * Requests that the authenticator update the the credentials for a user. This is typically
     * done by returning an intent to an activity that will prompt the user to update the stored
     * credentials for the account. This request
     * is processed by the authenticator for the account. If no matching authenticator is
     * registered in the system then {@link AuthenticatorException} is thrown.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param account The account whose credentials are to be updated.
     * @param authTokenType the auth token to retrieve as part of updating the credentials.
     * May be null.
     * @param options authenticator specific options for the request
     * @param activity If the authenticator returns a {@link #KEY_INTENT} in the result then
     * the intent will be started with this activity. If activity is null then the result will
     * be returned as-is.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, which is to be used to prompt the user for the credentials
     * <li> {@link #KEY_ACCOUNT_NAME} and {@link #KEY_ACCOUNT_TYPE} if the user enters the correct
     * credentials, and optionally a {@link #KEY_AUTHTOKEN} if an authTokenType was provided.
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> updateCredentials(final Account account,
            final String authTokenType,
            final Bundle options, final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.updateCredentials(mResponse, account, authTokenType, activity != null,
                        options);
            }
        }.start();
    }

    /**
     * Request that the properties for an authenticator be updated. This is typically done by
     * returning an intent to an activity that will allow the user to make changes. This request
     * is processed by the authenticator for the account. If no matching authenticator is
     * registered in the system then {@link AuthenticatorException} is thrown.
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType The account type of the authenticator whose properties are to be edited.
     * @param activity If the authenticator returns a {@link #KEY_INTENT} in the result then
     * the intent will be started with this activity. If activity is null then the result will
     * be returned as-is.
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, which is to be used to prompt the user for the credentials
     * <li> nothing, returned if the edit completes successfully
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> editProperties(final String accountType,
            final Activity activity, final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.editProperties(mResponse, accountType, activity != null);
            }
        }.start();
    }

    private void ensureNotOnMainThread() {
        final Looper looper = Looper.myLooper();
        if (looper != null && looper == mContext.getMainLooper()) {
            // We really want to throw an exception here, but GTalkService exercises this
            // path quite a bit and needs some serious rewrite in order to work properly.
            //noinspection ThrowableInstanceNeverThrow
//            Log.e(TAG, "calling this from your main thread can lead to deadlock and/or ANRs",
//                    new Exception());
            // TODO remove the log and throw this exception when the callers are fixed
//            throw new IllegalStateException(
//                    "calling this from your main thread can lead to deadlock");
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

        public abstract void doWork() throws RemoteException;

        private Bundle internalGetResult(Long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            ensureNotOnMainThread();
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
                Intent intent = bundle.getParcelable("intent");
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
            ensureNotOnMainThread();
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
                set(future.getResult());
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
     * Convenience method that combines the functionality of {@link #getAccountsByTypeAndFeatures},
     * {@link #getAuthToken(Account, String, Bundle, Activity, AccountManagerCallback, Handler)},
     * and {@link #addAccount}. It first gets the list of accounts that match accountType and the
     * feature set. If there are none then {@link #addAccount} is invoked with the authTokenType
     * feature set, and addAccountOptions. If there is exactly one then
     * {@link #getAuthToken(Account, String, Bundle, Activity, AccountManagerCallback, Handler)} is
     * called with that account. If there are more than one then a chooser activity is launched
     * to prompt the user to select one of them and then the authtoken is retrieved for it,
     * <p>
     * This call returns immediately but runs asynchronously and the result is accessed via the
     * {@link AccountManagerFuture} that is returned. This future is also passed as the sole
     * parameter to the {@link AccountManagerCallback}. If the caller wished to use this
     * method asynchronously then they will generally pass in a callback object that will get
     * invoked with the {@link AccountManagerFuture}. If they wish to use it synchronously then
     * they will generally pass null for the callback and instead call
     * {@link android.accounts.AccountManagerFuture#getResult()} on this method's return value,
     * which will then block until the request completes.
     * <p>
     * Requires that the caller has permission {@link android.Manifest.permission#MANAGE_ACCOUNTS}.
     *
     * @param accountType the accountType to query; this must be non-null
     * @param authTokenType the type of authtoken to retrieve; this must be non-null
     * @param features a filter for the accounts. See {@link #getAccountsByTypeAndFeatures}.
     * @param activityForPrompting The activity used to start any account management
     * activities that are required to fulfill this request. This may be null.
     * @param addAccountOptions authenticator-specific options used if an account needs to be added
     * @param getAuthTokenOptions authenticator-specific options passed to getAuthToken
     * @param callback A callback to invoke when the request completes. If null then
     * no callback is invoked.
     * @param handler The {@link Handler} to use to invoke the callback. If null then the
     * main thread's {@link Handler} is used.
     * @return an {@link AccountManagerFuture} that represents the future result of the call.
     * The future result is a {@link Bundle} that contains either:
     * <ul>
     * <li> {@link #KEY_INTENT}, if no activity is supplied yet an activity needs to launched to
     * fulfill the request.
     * <li> {@link #KEY_ACCOUNT_NAME}, {@link #KEY_ACCOUNT_TYPE} and {@link #KEY_AUTHTOKEN} if the
     * request completes successfully.
     * </ul>
     * If the user presses "back" then the request will be canceled.
     */
    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(
            final String accountType, final String authTokenType, final String[] features,
            final Activity activityForPrompting, final Bundle addAccountOptions,
            final Bundle getAuthTokenOptions,
            final AccountManagerCallback<Bundle> callback, final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("account type is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final GetAuthTokenByTypeAndFeaturesTask task =
                new GetAuthTokenByTypeAndFeaturesTask(accountType, authTokenType, features,
                activityForPrompting, addAccountOptions, getAuthTokenOptions, callback, handler);
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
     * Add a {@link OnAccountsUpdateListener} to this instance of the {@link AccountManager}.
     * The listener is guaranteed to be invoked on the thread of the Handler that is passed
     * in or the main thread's Handler if handler is null.
     * <p>
     * You must remove this listener before the context that was used to retrieve this
     * {@link AccountManager} instance goes away. This generally means when the Activity
     * or Service you are running is stopped.
     * @param listener the listener to add
     * @param handler the Handler whose thread will be used to invoke the listener. If null
     * the AccountManager context's main thread will be used.
     * @param updateImmediately if true then the listener will be invoked as a result of this
     * call.
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
     * Remove an {@link OnAccountsUpdateListener} that was previously registered with
     * {@link #addOnAccountsUpdatedListener}.
     * @param listener the listener to remove
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was not already added
     */
    public void removeOnAccountsUpdatedListener(OnAccountsUpdateListener listener) {
        if (listener == null) {
            Log.e(TAG, "Missing listener");
            return;
        }
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
