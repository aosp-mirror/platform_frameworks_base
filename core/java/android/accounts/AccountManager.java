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

import static android.Manifest.permission.GET_ACCOUNTS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.BroadcastBehavior;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.SQLException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.google.android.collect.Maps;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
@SystemService(Context.ACCOUNT_SERVICE)
public class AccountManager {

    private static final String TAG = "AccountManager";

    public static final int ERROR_CODE_REMOTE_EXCEPTION = 1;
    public static final int ERROR_CODE_NETWORK_ERROR = 3;
    public static final int ERROR_CODE_CANCELED = 4;
    public static final int ERROR_CODE_INVALID_RESPONSE = 5;
    public static final int ERROR_CODE_UNSUPPORTED_OPERATION = 6;
    public static final int ERROR_CODE_BAD_ARGUMENTS = 7;
    public static final int ERROR_CODE_BAD_REQUEST = 8;
    public static final int ERROR_CODE_BAD_AUTHENTICATION = 9;

    /** @hide */
    public static final int ERROR_CODE_USER_RESTRICTED = 100;
    /** @hide */
    public static final int ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE = 101;

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
     * Bundle key used for the account access id used for noting the
     * account was accessed when unmarshaled from a parcel.
     *
     * @hide
     */
    public static final String KEY_ACCOUNT_ACCESS_ID = "accountAccessId";

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
     * Bundle key used to supply the last time the credentials of the account
     * were authenticated successfully. Time is specified in milliseconds since
     * epoch. Associated time is updated on successful authentication of account
     * on adding account, confirming credentials, or updating credentials.
     */
    public static final String KEY_LAST_AUTHENTICATED_TIME = "lastAuthenticatedTime";

    /**
     * The UID of caller app.
     */
    public static final String KEY_CALLER_UID = "callerUid";

    /**
     * The process id of caller app.
     */
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

    /**
     * Bundle key used for a {@link Bundle} in result from
     * {@link #startAddAccountSession} and friends which returns session data
     * for installing an account later.
     */
    public static final String KEY_ACCOUNT_SESSION_BUNDLE = "accountSessionBundle";

    /**
     * Bundle key used for the {@link String} account status token in result
     * from {@link #startAddAccountSession} and friends which returns
     * information about a particular account.
     */
    public static final String KEY_ACCOUNT_STATUS_TOKEN = "accountStatusToken";

    public static final String ACTION_AUTHENTICATOR_INTENT =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_META_DATA_NAME =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_ATTRIBUTES_NAME = "account-authenticator";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({VISIBILITY_UNDEFINED, VISIBILITY_VISIBLE, VISIBILITY_USER_MANAGED_VISIBLE,
            VISIBILITY_NOT_VISIBLE, VISIBILITY_USER_MANAGED_NOT_VISIBLE})
    public @interface AccountVisibility {
    }

    /**
     * Account visibility was not set. Default visibility value will be used.
     * See {@link #PACKAGE_NAME_KEY_LEGACY_VISIBLE}, {@link #PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE}
     */
    public static final int VISIBILITY_UNDEFINED = 0;

    /**
     * Account is always visible to given application and only authenticator can revoke visibility.
     */
    public static final int VISIBILITY_VISIBLE = 1;

    /**
     * Account is visible to given application, but user can revoke visibility.
     */
    public static final int VISIBILITY_USER_MANAGED_VISIBLE = 2;

    /**
     * Account is not visible to given application and only authenticator can grant visibility.
     */
    public static final int VISIBILITY_NOT_VISIBLE = 3;

    /**
     * Account is not visible to given application, but user can reveal it, for example, using
     * {@link #newChooseAccountIntent(Account, List, String[], String, String, String[], Bundle)}
     */
    public static final int VISIBILITY_USER_MANAGED_NOT_VISIBLE = 4;

    /**
     * Token type for the special case where a UID has access only to an account
     * but no authenticator specific auth token types.
     *
     * @hide
     */
    public static final String ACCOUNT_ACCESS_TOKEN_TYPE =
            "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE";

    private final Context mContext;
    private final IAccountManager mService;
    private final Handler mMainHandler;

    /**
     * Action sent as a broadcast Intent by the AccountsService when accounts are added, accounts
     * are removed, or an account's credentials (saved password, etc) are changed.
     *
     * @see #addOnAccountsUpdatedListener
     * @see #ACTION_ACCOUNT_REMOVED
     *
     * @deprecated use {@link #addOnAccountsUpdatedListener} to get account updates in runtime.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(includeBackground = true)
    public static final String LOGIN_ACCOUNTS_CHANGED_ACTION =
        "android.accounts.LOGIN_ACCOUNTS_CHANGED";

    /**
     * Action sent as a broadcast Intent by the AccountsService when any account is removed
     * or renamed. Only applications which were able to see the account will receive the intent.
     * Intent extra will include the following fields:
     * <ul>
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the removed account
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(includeBackground = true)
    public static final String ACTION_ACCOUNT_REMOVED =
        "android.accounts.action.ACCOUNT_REMOVED";

    /**
     * Action sent as a broadcast Intent to specific package by the AccountsService
     * when account visibility or account's credentials (saved password, etc) are changed.
     *
     * @see #addOnAccountsUpdatedListener
     *
     * @hide
     */
    public static final String ACTION_VISIBLE_ACCOUNTS_CHANGED =
        "android.accounts.action.VISIBLE_ACCOUNTS_CHANGED";

    /**
     * Key to set visibility for applications which satisfy one of the following conditions:
     * <ul>
     * <li>Target API level below {@link android.os.Build.VERSION_CODES#O} and have
     * deprecated {@link android.Manifest.permission#GET_ACCOUNTS} permission.
     * </li>
     * <li> Have {@link android.Manifest.permission#GET_ACCOUNTS_PRIVILEGED} permission. </li>
     * <li> Have the same signature as authenticator. </li>
     * <li> Have {@link android.Manifest.permission#READ_CONTACTS} permission and
     * account type may be associated with contacts data - (verified by
     * {@link android.Manifest.permission#WRITE_CONTACTS} permission check for the authenticator).
     * </li>
     * </ul>
     * See {@link #getAccountVisibility}. If the value was not set by authenticator
     * {@link #VISIBILITY_USER_MANAGED_VISIBLE} is used.
     */
    public static final String PACKAGE_NAME_KEY_LEGACY_VISIBLE =
        "android:accounts:key_legacy_visible";

    /**
     * Key to set default visibility for applications which don't satisfy conditions in
     * {@link #PACKAGE_NAME_KEY_LEGACY_VISIBLE}. If the value was not set by authenticator
     * {@link #VISIBILITY_USER_MANAGED_NOT_VISIBLE} is used.
     */
    public static final String PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE =
            "android:accounts:key_legacy_not_visible";

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
     * Gets the saved password associated with the account. This is intended for authenticators and
     * related code; applications should get an auth token instead.
     *
     * <p>
     * It is safe to call this method from the main thread.
     *
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * <p>
     * <b>NOTE:</b> If targeting your app to work on API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} and before, AUTHENTICATE_ACCOUNTS
     * permission is needed for those platforms. See docs for this function in API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}.
     *
     * @param account The account to query for a password. Must not be {@code null}.
     * @return The account's password, null if none or if the account doesn't exist
     */
    public String getPassword(final Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the user data named by "key" associated with the account. This is intended for
     * authenticators and related code to store arbitrary metadata along with accounts. The meaning
     * of the keys and values is up to the authenticator for the account.
     *
     * <p>
     * It is safe to call this method from the main thread.
     *
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * <p>
     * <b>NOTE:</b> If targeting your app to work on API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} and before, AUTHENTICATE_ACCOUNTS
     * permission is needed for those platforms. See docs for this function in API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}.
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
            throw e.rethrowFromSystemServer();
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
            return mService.getAuthenticatorTypes(UserHandle.getCallingUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Lists the currently registered authenticators for a given user id.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>The caller has to be in the same user or have the permission
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     *
     * @return An array of {@link AuthenticatorDescription} for every
     *     authenticator known to the AccountManager service.  Empty (never
     *     null) if no authenticators are known.
     */
    public AuthenticatorDescription[] getAuthenticatorTypesAsUser(int userId) {
        try {
            return mService.getAuthenticatorTypes(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Lists all accounts visible to the caller regardless of type. Equivalent to
     * getAccountsByType(null). These accounts may be visible because the user granted access to the
     * account, or the AbstractAcccountAuthenticator managing the account did so or because the
     * client shares a signature with the managing AbstractAccountAuthenticator.
     *
     * <p>
     * It is safe to call this method from the main thread.
     *
     * @return An array of {@link Account}, one for each account. Empty (never null) if no accounts
     *         have been added.
     */
    @NonNull
    public Account[] getAccounts() {
        try {
            return mService.getAccounts(null, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Lists all accounts visible to caller regardless of type for a given user id. Equivalent to
     * getAccountsByType(null).
     *
     * <p>
     * It is safe to call this method from the main thread.
     *
     * @return An array of {@link Account}, one for each account. Empty (never null) if no accounts
     *         have been added.
     */
    @NonNull
    public Account[] getAccountsAsUser(int userId) {
        try {
            return mService.getAccountsAsUser(null, userId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * For use by internal activities. Returns the list of accounts that the calling package
     * is authorized to use, particularly for shared accounts.
     * @param packageName package name of the calling app.
     * @param uid the uid of the calling app.
     * @return the accounts that are available to this package and user.
     */
    @NonNull
    public Account[] getAccountsForPackage(String packageName, int uid) {
        try {
            return mService.getAccountsForPackage(packageName, uid, mContext.getOpPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the accounts visible to the specified package in an environment where some apps are
     * not authorized to view all accounts. This method can only be called by system apps and
     * authenticators managing the type.
     * Beginning API level {@link android.os.Build.VERSION_CODES#O} it also return accounts
     * which user can make visible to the application (see {@link #VISIBILITY_USER_MANAGED_VISIBLE}).
     *
     * @param type The type of accounts to return, null to retrieve all accounts
     * @param packageName The package name of the app for which the accounts are to be returned
     * @return An array of {@link Account}, one per matching account. Empty (never null) if no
     *         accounts of the specified type can be accessed by the package.
     *
     */
    @NonNull
    public Account[] getAccountsByTypeForPackage(String type, String packageName) {
        try {
            return mService.getAccountsByTypeForPackage(type, packageName,
                    mContext.getOpPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Lists all accounts of particular type visible to the caller. These accounts may be visible
     * because the user granted access to the account, or the AbstractAcccountAuthenticator managing
     * the account did so or because the client shares a signature with the managing
     * AbstractAccountAuthenticator.
     *
     * <p>
     * The account type is a string token corresponding to the authenticator and useful domain of
     * the account. For example, there are types corresponding to Google and Facebook. The exact
     * string token to use will be published somewhere associated with the authenticator in
     * question.
     *
     * <p>
     * It is safe to call this method from the main thread.
     *
     * <p>
     * Caller targeting API level {@link android.os.Build.VERSION_CODES#O} and above, will get list
     * of accounts made visible to it by user
     * (see {@link #newChooseAccountIntent(Account, List, String[], String,
     * String, String[], Bundle)}) or AbstractAcccountAuthenticator
     * using {@link #setAccountVisibility}.
     * {@link android.Manifest.permission#GET_ACCOUNTS} permission is not used.
     *
     * <p>
     * Caller targeting API level below {@link android.os.Build.VERSION_CODES#O} that have not been
     * granted the {@link android.Manifest.permission#GET_ACCOUNTS} permission, will only see those
     * accounts managed by AbstractAccountAuthenticators whose signature matches the client.
     *
     * <p>
     * <b>NOTE:</b> If targeting your app to work on API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} and before,
     * {@link android.Manifest.permission#GET_ACCOUNTS} permission is
     * needed for those platforms, irrespective of uid or signature match. See docs for this
     * function in API level {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}.
     *
     * @param type The type of accounts to return, null to retrieve all accounts
     * @return An array of {@link Account}, one per matching account. Empty (never null) if no
     *         accounts of the specified type have been added.
     */
    @NonNull
    public Account[] getAccountsByType(String type) {
        return getAccountsByTypeAsUser(type, Process.myUserHandle());
    }

    /** @hide Same as {@link #getAccountsByType(String)} but for a specific user. */
    @NonNull
    public Account[] getAccountsByTypeAsUser(String type, UserHandle userHandle) {
        try {
            return mService.getAccountsAsUser(type, userHandle.getIdentifier(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Change whether or not an app (identified by its uid) is allowed to retrieve an authToken
     * for an account.
     * <p>
     * This is only meant to be used by system activities and is not in the SDK.
     * @param account The account whose permissions are being modified
     * @param authTokenType The type of token whose permissions are being modified
     * @param uid The uid that identifies the app which is being granted or revoked permission.
     * @param value true is permission is being granted, false for revoked
     * @hide
     */
    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) {
        try {
            mService.updateAppPermission(account, authTokenType, uid, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the user-friendly label associated with an authenticator's auth token.
     * @param accountType the type of the authenticator. must not be null.
     * @param authTokenType the token type. must not be null.
     * @param callback callback to invoke when the result is available. may be null.
     * @param handler the handler on which to invoke the callback, or null for the main thread
     * @return a future containing the label string
     * @hide
     */
    public AccountManagerFuture<String> getAuthTokenLabel(
            final String accountType, final String authTokenType,
            AccountManagerCallback<String> callback, Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        return new Future2Task<String>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.getAuthTokenLabel(mResponse, accountType, authTokenType);
            }

            @Override
            public String bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_AUTH_TOKEN_LABEL)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getString(KEY_AUTH_TOKEN_LABEL);
            }
        }.start();
    }

    /**
     * Finds out whether a particular account has all the specified features. Account features are
     * authenticator-specific string tokens identifying boolean account properties. For example,
     * features are used to tell whether Google accounts have a particular service (such as Google
     * Calendar or Google Talk) enabled. The feature names and their meanings are published
     * somewhere associated with the authenticator in question.
     *
     * <p>
     * This method may be called from any thread, but the returned {@link AccountManagerFuture} must
     * not be used on the main thread.
     *
     * <p>
     * If caller target API level is below {@link android.os.Build.VERSION_CODES#O}, it is
     * required to hold the permission {@link android.Manifest.permission#GET_ACCOUNTS} or have a
     * signature match with the AbstractAccountAuthenticator that manages the account.
     *
     * @param account The {@link Account} to test
     * @param features An array of the account features to check
     * @param callback Callback to invoke when the request completes, null for no callback
     * @param handler {@link Handler} identifying the callback thread, null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean, true if the account
     *         exists and has all of the specified features.
     */
    public AccountManagerFuture<Boolean> hasFeatures(final Account account,
            final String[] features,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (features == null) throw new IllegalArgumentException("features is null");
        return new Future2Task<Boolean>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.hasFeatures(mResponse, account, features, mContext.getOpPackageName());
            }
            @Override
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * Lists all accounts of a type which have certain features. The account type identifies the
     * authenticator (see {@link #getAccountsByType}). Account features are authenticator-specific
     * string tokens identifying boolean account properties (see {@link #hasFeatures}).
     *
     * <p>
     * Unlike {@link #getAccountsByType}, this method calls the authenticator, which may contact the
     * server or do other work to check account features, so the method returns an
     * {@link AccountManagerFuture}.
     *
     * <p>
     * This method may be called from any thread, but the returned {@link AccountManagerFuture} must
     * not be used on the main thread.
     *
     * <p>
     * Caller targeting API level {@link android.os.Build.VERSION_CODES#O} and above, will get list
     * of accounts made visible to it by user
     * (see {@link #newChooseAccountIntent(Account, List, String[], String,
     * String, String[], Bundle)}) or AbstractAcccountAuthenticator
     * using {@link #setAccountVisibility}.
     * {@link android.Manifest.permission#GET_ACCOUNTS} permission is not used.
     *
     * <p>
     * Caller targeting API level below {@link android.os.Build.VERSION_CODES#O} that have not been
     * granted the {@link android.Manifest.permission#GET_ACCOUNTS} permission, will only see those
     * accounts managed by AbstractAccountAuthenticators whose signature matches the client.
     * <p>
     * <b>NOTE:</b> If targeting your app to work on API level
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} and before,
     * {@link android.Manifest.permission#GET_ACCOUNTS} permission is
     * needed for those platforms, irrespective of uid or signature match. See docs for this
     * function in API level {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}.
     *
     *
     * @param type The type of accounts to return, must not be null
     * @param features An array of the account features to require, may be null or empty *
     * @param callback Callback to invoke when the request completes, null for no callback
     * @param handler {@link Handler} identifying the callback thread, null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to an array of {@link Account}, one
     *         per account of the specified type which matches the requested features.
     */
    public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(
            final String type, final String[] features,
            AccountManagerCallback<Account[]> callback, Handler handler) {
        if (type == null) throw new IllegalArgumentException("type is null");
        return new Future2Task<Account[]>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.getAccountsByFeatures(mResponse, type, features,
                        mContext.getOpPackageName());
            }
            @Override
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
     * Adds an account directly to the AccountManager. Normally used by sign-up
     * wizards associated with authenticators, not directly by applications.
     * <p>Calling this method does not update the last authenticated timestamp,
     * referred by {@link #KEY_LAST_AUTHENTICATED_TIME}. To update it, call
     * {@link #notifyAccountAuthenticated(Account)} after getting success.
     * However, if this method is called when it is triggered by addAccount() or
     * addAccountAsUser() or similar functions, then there is no need to update
     * timestamp manually as it is updated automatically by framework on
     * successful completion of the mentioned functions.
     * <p>It is safe to call this method from the main thread.
     * <p>This method requires the caller to have a signature match with the
     * authenticator that owns the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission is needed for those platforms. See docs
     * for this function in API level 22.
     *
     * @param account The {@link Account} to add
     * @param password The password to associate with the account, null for none
     * @param userdata String values to use for the account's userdata, null for
     *            none
     * @return True if the account was successfully added, false if the account
     *         already exists, the account is null, or another error occurs.
     */
    public boolean addAccountExplicitly(Account account, String password, Bundle userdata) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.addAccountExplicitly(account, password, userdata);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds an account directly to the AccountManager. Additionally it specifies Account visibility
     * for given list of packages.
     * <p>
     * Normally used by sign-up wizards associated with authenticators, not directly by
     * applications.
     * <p>
     * Calling this method does not update the last authenticated timestamp, referred by
     * {@link #KEY_LAST_AUTHENTICATED_TIME}. To update it, call
     * {@link #notifyAccountAuthenticated(Account)} after getting success.
     * <p>
     * It is safe to call this method from the main thread.
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * @param account The {@link Account} to add
     * @param password The password to associate with the account, null for none
     * @param extras String values to use for the account's userdata, null for none
     * @param visibility Map from packageName to visibility values which will be set before account
     *        is added. See {@link #getAccountVisibility} for possible values.
     *
     * @return True if the account was successfully added, false if the account already exists, the
     *         account is null, or another error occurs.
     */
    public boolean addAccountExplicitly(Account account, String password, Bundle extras,
            Map<String, Integer> visibility) {
        if (account == null)
            throw new IllegalArgumentException("account is null");
        try {
            return mService.addAccountExplicitlyWithVisibility(account, password, extras,
                    visibility);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns package names and visibility which were explicitly set for given account.
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * @param account The account for which visibility data should be returned
     *
     * @return Map from package names to visibility for given account
     */
    public Map<String, Integer> getPackagesAndVisibilityForAccount(Account account) {
        try {
            if (account == null)
                throw new IllegalArgumentException("account is null");
            @SuppressWarnings("unchecked")
            Map<String, Integer> result = (Map<String, Integer>) mService
                    .getPackagesAndVisibilityForAccount(account);
            return result;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all accounts of given type and their visibility for specific package. This method
     * requires the caller to have a signature match with the authenticator that manages
     * accountType. It is a helper method which combines calls to {@link #getAccountsByType} by
     * authenticator and {@link #getAccountVisibility} for every returned account.
     *
     * <p>
     *
     * @param packageName Package name
     * @param accountType {@link Account} type
     *
     * @return Map with visibility for all accounts of given type
     * See {@link #getAccountVisibility} for possible values
     */
    public Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName,
            String accountType) {
        try {
            @SuppressWarnings("unchecked")
            Map<Account, Integer> result = (Map<Account, Integer>) mService
                    .getAccountsAndVisibilityForPackage(packageName, accountType);
            return result;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Set visibility value of given account to certain package.
     * Package name must match installed application, or be equal to
     * {@link #PACKAGE_NAME_KEY_LEGACY_VISIBLE} or {@link #PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE}.
     * <p>
     * Possible visibility values:
     * <ul>
     * <li>{@link #VISIBILITY_UNDEFINED}</li>
     * <li>{@link #VISIBILITY_VISIBLE}</li>
     * <li>{@link #VISIBILITY_USER_MANAGED_VISIBLE}</li>
     * <li>{@link #VISIBILITY_NOT_VISIBLE}
     * <li>{@link #VISIBILITY_USER_MANAGED_NOT_VISIBLE}</li>
     * </ul>
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * @param account {@link Account} to update visibility
     * @param packageName Package name of the application to modify account visibility
     * @param visibility New visibility value
     *
     * @return True, if visibility value was successfully updated.
     */
    public boolean setAccountVisibility(Account account, String packageName,
            @AccountVisibility int visibility) {
        if (account == null)
            throw new IllegalArgumentException("account is null");
        try {
            return mService.setAccountVisibility(account, packageName, visibility);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Get visibility of certain account for given application. Possible returned values are:
     * <ul>
     * <li>{@link #VISIBILITY_VISIBLE}</li>
     * <li>{@link #VISIBILITY_USER_MANAGED_VISIBLE}</li>
     * <li>{@link #VISIBILITY_NOT_VISIBLE}
     * <li>{@link #VISIBILITY_USER_MANAGED_NOT_VISIBLE}</li>
     * </ul>
     *
     * <p>
     * This method requires the caller to have a signature match with the authenticator that owns
     * the specified account.
     *
     * @param account {@link Account} to get visibility
     * @param packageName Package name of the application to get account visibility
     *
     * @return int Visibility of given account.
     */
    public @AccountVisibility int getAccountVisibility(Account account, String packageName) {
        if (account == null)
            throw new IllegalArgumentException("account is null");
        try {
            return mService.getAccountVisibility(account, packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the system that the account has just been authenticated. This
     * information may be used by other applications to verify the account. This
     * should be called only when the user has entered correct credentials for
     * the account.
     * <p>
     * It is not safe to call this method from the main thread. As such, call it
     * from another thread.
     * <p>This method requires the caller to have a signature match with the
     * authenticator that owns the specified account.
     *
     * @param account The {@link Account} to be updated.
     * @return boolean {@code true} if the authentication of the account has been successfully
     *         acknowledged. Otherwise {@code false}.
     */
    public boolean notifyAccountAuthenticated(Account account) {
        if (account == null)
            throw new IllegalArgumentException("account is null");
        try {
            return mService.accountAuthenticated(account);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Rename the specified {@link Account}.  This is equivalent to removing
     * the existing account and adding a new renamed account with the old
     * account's user data.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
     *
     * @param account The {@link Account} to rename
     * @param newName String name to be associated with the account.
     * @param callback Callback to invoke when the request completes, null for
     *     no callback
     * @param handler {@link Handler} identifying the callback thread, null for
     *     the main thread
     * @return An {@link AccountManagerFuture} which resolves to the Account
     *     after the name change. If successful the account's name will be the
     *     specified new name.
     */
    public AccountManagerFuture<Account> renameAccount(
            final Account account,
            @Size(min = 1) final String newName,
            AccountManagerCallback<Account> callback,
            Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null.");
        if (TextUtils.isEmpty(newName)) {
              throw new IllegalArgumentException("newName is empty or null.");
        }
        return new Future2Task<Account>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.renameAccount(mResponse, account, newName);
            }
            @Override
            public Account bundleToResult(Bundle bundle) throws AuthenticatorException {
                String name = bundle.getString(KEY_ACCOUNT_NAME);
                String type = bundle.getString(KEY_ACCOUNT_TYPE);
                String accessId = bundle.getString(KEY_ACCOUNT_ACCESS_ID);
                return new Account(name, type, accessId);
            }
        }.start();
    }

    /**
     * Gets the previous name associated with the account or {@code null}, if
     * none. This is intended so that clients of
     * {@link OnAccountsUpdateListener} can determine if an
     * authenticator has renamed an account.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * @param account The account to query for a previous name.
     * @return The account's previous name, null if the account has never been
     *         renamed.
     */
    public String getPreviousName(final Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.getPreviousName(account);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes an account from the AccountManager.  Does nothing if the account
     * does not exist.  Does not delete the account from the server.
     * The authenticator may have its own policies preventing account
     * deletion, in which case the account will not be deleted.
     *
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs for
     * this function in API level 22.
     *
     * @param account The {@link Account} to remove
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean,
     *     true if the account has been successfully removed
     * @deprecated use
     *     {@link #removeAccount(Account, Activity, AccountManagerCallback, Handler)}
     *     instead
     */
    @Deprecated
    public AccountManagerFuture<Boolean> removeAccount(final Account account,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        return new Future2Task<Boolean>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.removeAccount(mResponse, account, false);
            }
            @Override
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * Removes an account from the AccountManager. Does nothing if the account
     * does not exist.  Does not delete the account from the server.
     * The authenticator may have its own policies preventing account
     * deletion, in which case the account will not be deleted.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs for
     * this function in API level 22.
     *
     * @param account The {@link Account} to remove
     * @param activity The {@link Activity} context to use for launching a new
     *     authenticator-defined sub-Activity to prompt the user to delete an
     *     account; used only to call startActivity(); if null, the prompt
     *     will not be launched directly, but the {@link Intent} may be
     *     returned to the caller instead
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *     {@link #KEY_BOOLEAN_RESULT} if activity was specified and an account
     *     was removed or if active. If no activity was specified, the returned
     *     Bundle contains only {@link #KEY_INTENT} with the {@link Intent}
     *     needed to launch the actual account removal process, if authenticator
     *     needs the activity launch. If an error occurred,
     *     {@link AccountManagerFuture#getResult()} throws:
     * <ul>
     * <li> {@link AuthenticatorException} if no authenticator was registered for
     *      this account type or the authenticator failed to respond
     * <li> {@link OperationCanceledException} if the operation was canceled for
     *      any reason, including the user canceling the creation process or
     *      adding accounts (of this type) has been disabled by policy
     * </ul>
     */
    public AccountManagerFuture<Bundle> removeAccount(final Account account,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.removeAccount(mResponse, account, activity != null);
            }
        }.start();
    }

    /**
     * @see #removeAccount(Account, AccountManagerCallback, Handler)
     * @hide
     * @deprecated use
     *     {@link #removeAccountAsUser(Account, Activity, AccountManagerCallback, Handler)}
     *     instead
     */
    @Deprecated
    public AccountManagerFuture<Boolean> removeAccountAsUser(final Account account,
            AccountManagerCallback<Boolean> callback, Handler handler,
            final UserHandle userHandle) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (userHandle == null) throw new IllegalArgumentException("userHandle is null");
        return new Future2Task<Boolean>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.removeAccountAsUser(mResponse, account, false, userHandle.getIdentifier());
            }
            @Override
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * @see #removeAccount(Account, Activity, AccountManagerCallback, Handler)
     * @hide
     */
    public AccountManagerFuture<Bundle> removeAccountAsUser(final Account account,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler,
            final UserHandle userHandle) {
        if (account == null)
            throw new IllegalArgumentException("account is null");
        if (userHandle == null)
            throw new IllegalArgumentException("userHandle is null");
        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.removeAccountAsUser(mResponse, account, activity != null,
                        userHandle.getIdentifier());
            }
        }.start();
    }

    /**
     * Removes an account directly. Normally used by authenticators, not
     * directly by applications. Does not delete the account from the server.
     * The authenticator may have its own policies preventing account deletion,
     * in which case the account will not be deleted.
     * <p>
     * It is safe to call this method from the main thread.
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
     *
     * @param account The {@link Account} to delete.
     * @return True if the account was successfully deleted, false if the
     *         account did not exist, the account is null, or another error
     *         occurs.
     */
    public boolean removeAccountExplicitly(Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            return mService.removeAccountExplicitly(account);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS or USE_CREDENTIALS permission is needed for those
     * platforms. See docs for this function in API level 22.
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
            throw e.rethrowFromSystemServer();
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
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
     *
     * @param account The account for which an auth token is to be fetched. Cannot be {@code null}.
     * @param authTokenType The type of auth token to fetch. Cannot be {@code null}.
     * @return The cached auth token for this account and type, or null if
     *     no auth token is cached or the account does not exist.
     * @see #getAuthToken
     */
    public String peekAuthToken(final Account account, final String authTokenType) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets or forgets a saved password. This modifies the local copy of the
     * password used to automatically authenticate the user; it does not change
     * the user's account password on the server. Intended for use by the
     * authenticator, not directly by applications.
     * <p>Calling this method does not update the last authenticated timestamp,
     * referred by {@link #KEY_LAST_AUTHENTICATED_TIME}. To update it, call
     * {@link #notifyAccountAuthenticated(Account)} after getting success.
     * <p>It is safe to call this method from the main thread.
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
     *
     * @param account The account whose password is to be set. Cannot be
     *            {@code null}.
     * @param password The password to set, null to clear the password
     */
    public void setPassword(final Account account, final String password) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Forgets a saved password.  This erases the local copy of the password;
     * it does not change the user's account password on the server.
     * Has the same effect as setPassword(account, null) but requires fewer
     * permissions, and may be used by applications or management interfaces
     * to "sign out" from an account.
     *
     * <p>This method only successfully clear the account's password when the
     * caller has the same signature as the authenticator that owns the
     * specified account. Otherwise, this method will silently fail.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs for
     * this function in API level 22.
     *
     * @param account The account whose password to clear
     */
    public void clearPassword(final Account account) {
        if (account == null) throw new IllegalArgumentException("account is null");
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets one userdata key for an account.  Intended by use for the
     * authenticator to stash state for itself, not directly by applications.
     * The meaning of the keys and values is up to the authenticator.
     *
     * <p>It is safe to call this method from the main thread.
     *
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
     *
     * @param account Account whose user data is to be set. Must not be {@code null}.
     * @param key String user data key to set.  Must not be null
     * @param value String value to set, {@code null} to clear this user data key
     */
    public void setUserData(final Account account, final String key, final String value) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (key == null) throw new IllegalArgumentException("key is null");
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * <p>This method requires the caller to have a signature match with the
     * authenticator that manages the specified account.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * AUTHENTICATE_ACCOUNTS permission and same UID as account's authenticator
     * is needed for those platforms. See docs for this function in API level 22.
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This convenience helper synchronously gets an auth token with
     * {@link #getAuthToken(Account, String, boolean, AccountManagerCallback, Handler)}.
     *
     * <p>This method may block while a network request completes, and must
     * never be made from the main thread.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * USE_CREDENTIALS permission is needed for those platforms. See docs for
     * this function in API level 22.
     *
     * @param account The account to fetch an auth token for
     * @param authTokenType The auth token type, see {@link #getAuthToken getAuthToken()}
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * USE_CREDENTIALS permission is needed for those platforms. See docs for
     * this function in API level 22.
     *
     * <p>This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
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
        final Bundle optionsIn = new Bundle();
        if (options != null) {
            optionsIn.putAll(options);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());
        return new AmsTask(activity, handler, callback) {
            @Override
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
     * {@link #LOGIN_ACCOUNTS_CHANGED_ACTION} Intent and
     * notify {@link OnAccountsUpdateListener} which applications can
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
     * {@link #LOGIN_ACCOUNTS_CHANGED_ACTION} Intent and
     * notify {@link OnAccountsUpdateListener} which applications can
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * USE_CREDENTIALS permission is needed for those platforms. See docs for
     * this function in API level 22.
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
        final Bundle optionsIn = new Bundle();
        if (options != null) {
            optionsIn.putAll(options);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());
        return new AmsTask(null, handler, callback) {
            @Override
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs for
     * this function in API level 22.
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
     *      any reason, including the user canceling the creation process or adding accounts
     *      (of this type) has been disabled by policy
     * <li> {@link IOException} if the authenticator experienced an I/O problem
     *      creating a new account, usually because of network trouble
     * </ul>
     */
    public AccountManagerFuture<Bundle> addAccount(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        final Bundle optionsIn = new Bundle();
        if (addAccountOptions != null) {
            optionsIn.putAll(addAccountOptions);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.addAccount(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, optionsIn);
            }
        }.start();
    }

    /**
     * @see #addAccount(String, String, String[], Bundle, Activity, AccountManagerCallback, Handler)
     * @hide
     */
    public AccountManagerFuture<Bundle> addAccountAsUser(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions, final Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler, final UserHandle userHandle) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (userHandle == null) throw new IllegalArgumentException("userHandle is null");
        final Bundle optionsIn = new Bundle();
        if (addAccountOptions != null) {
            optionsIn.putAll(addAccountOptions);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.addAccountAsUser(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, optionsIn, userHandle.getIdentifier());
            }
        }.start();
    }


    /**
     * Adds shared accounts from a parent user to a secondary user. Adding the shared account
     * doesn't take effect immediately. When the target user starts up, any pending shared accounts
     * are attempted to be copied to the target user from the primary via calls to the
     * authenticator.
     * @param parentUser parent user
     * @param user target user
     * @hide
     */
    public void addSharedAccountsFromParentUser(UserHandle parentUser, UserHandle user) {
        try {
            mService.addSharedAccountsFromParentUser(parentUser.getIdentifier(),
                    user.getIdentifier(), mContext.getOpPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Copies an account from one user to another user.
     * @param account the account to copy
     * @param fromUser the user to copy the account from
     * @param toUser the target user
     * @param callback Callback to invoke when the request completes,
     *     null for no callback
     * @param handler {@link Handler} identifying the callback thread,
     *     null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean indicated wether it
     * succeeded.
     * @hide
     */
    public AccountManagerFuture<Boolean> copyAccountToUser(
            final Account account, final UserHandle fromUser, final UserHandle toUser,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (toUser == null || fromUser == null) {
            throw new IllegalArgumentException("fromUser and toUser cannot be null");
        }

        return new Future2Task<Boolean>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.copyAccountToUser(
                        mResponse, account, fromUser.getIdentifier(), toUser.getIdentifier());
            }
            @Override
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * @hide
     * Removes the shared account.
     * @param account the account to remove
     * @param user the user to remove the account from
     * @return
     */
    public boolean removeSharedAccount(final Account account, UserHandle user) {
        try {
            boolean val = mService.removeSharedAccountAsUser(account, user.getIdentifier());
            return val;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @param user
     * @return
     */
    public Account[] getSharedAccounts(UserHandle user) {
        try {
            return mService.getSharedAccountsAsUser(user.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs
     * for this function in API level 22.
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
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account verified
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * <li> {@link #KEY_BOOLEAN_RESULT} - true to indicate success
     * </ul>
     *
     * If no activity or password was specified, the returned Bundle contains
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     * password prompt.
     *
     * <p>Also the returning Bundle may contain {@link
     * #KEY_LAST_AUTHENTICATED_TIME} indicating the last time the
     * credential was validated/created.
     *
     * If an error occurred,{@link AccountManagerFuture#getResult()} throws:
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
        return confirmCredentialsAsUser(account, options, activity, callback, handler,
                Process.myUserHandle());
    }

    /**
     * @hide
     * Same as {@link #confirmCredentials(Account, Bundle, Activity, AccountManagerCallback, Handler)}
     * but for the specified user.
     */
    public AccountManagerFuture<Bundle> confirmCredentialsAsUser(final Account account,
            final Bundle options,
            final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler, UserHandle userHandle) {
        if (account == null) throw new IllegalArgumentException("account is null");
        final int userId = userHandle.getIdentifier();
        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.confirmCredentialsAsUser(mResponse, account, options, activity != null,
                        userId);
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
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs for
     * this function in API level 22.
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
     * <li> {@link #KEY_ACCOUNT_NAME} - the name of the account
     * <li> {@link #KEY_ACCOUNT_TYPE} - the type of the account
     * </ul>
     *
     * If no activity was specified, the returned Bundle contains
     * {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     * password prompt. If an error occurred,
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
            @Override
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
     * <p>This method requires the caller to have the same signature as the
     * authenticator associated with the specified account type.
     *
     * <p><b>NOTE:</b> If targeting your app to work on API level 22 and before,
     * MANAGE_ACCOUNTS permission is needed for those platforms. See docs
     * for this function in API level 22.
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
            @Override
            public void doWork() throws RemoteException {
                mService.editProperties(mResponse, accountType, activity != null);
            }
        }.start();
    }

    /**
     * @hide
     * Checks if the given account exists on any of the users on the device.
     * Only the system process can call this method.
     *
     * @param account The account to check for existence.
     * @return whether any user has this account
     */
    public boolean someUserHasAccount(@NonNull final Account account) {
        try {
            return mService.someUserHasAccount(account);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
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
            @Override
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
            @Override
            public void run() {
                synchronized (mAccountsUpdatedListeners) {
                    try {
                        if (mAccountsUpdatedListeners.containsKey(listener)) {
                            Set<String> types = mAccountsUpdatedListenersTypes.get(listener);
                            if (types != null) {
                                // filter by account type;
                                ArrayList<Account> filtered = new ArrayList<>();
                                for (Account account : accountsCopy) {
                                    if (types.contains(account.type)) {
                                        filtered.add(account);
                                    }
                                }
                                listener.onAccountsUpdated(
                                        filtered.toArray(new Account[filtered.size()]));
                            } else {
                                listener.onAccountsUpdated(accountsCopy);
                            }
                        }
                    } catch (SQLException e) {
                        // Better luck next time. If the problem was disk-full,
                        // the STORAGE_OK intent will re-trigger the update.
                        Log.e(TAG, "Can't update accounts", e);
                    }
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
                @Override
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

        @Override
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

        @Override
        public Bundle getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(null, null);
        }

        @Override
        public Bundle getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(timeout, unit);
        }

        @Override
        protected void done() {
            if (mCallback != null) {
                postToHandler(mHandler, mCallback, this);
            }
        }

        /** Handles the responses from the AccountManager */
        private class Response extends IAccountManagerResponse.Stub {
            @Override
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
                        throw e.rethrowFromSystemServer();
                    }
                } else {
                    set(bundle);
                }
            }

            @Override
            public void onError(int code, String message) {
                if (code == ERROR_CODE_CANCELED || code == ERROR_CODE_USER_RESTRICTED
                        || code == ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE) {
                    // the authenticator indicated that this request was canceled or we were
                    // forbidden to fulfill; cancel now
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
                @Override
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
            @Override
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

            @Override
            public void onError(int code, String message) {
                if (code == ERROR_CODE_CANCELED || code == ERROR_CODE_USER_RESTRICTED
                        || code == ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE) {
                    // the authenticator indicated that this request was canceled or we were
                    // forbidden to fulfill; cancel now
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

        @Override
        protected void done() {
            if (mCallback != null) {
                postRunnableToHandler(new Runnable() {
                    @Override
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

        @Override
        public T getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            return internalGetResult(null, null);
        }

        @Override
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

    private void getAccountByTypeAndFeatures(String accountType, String[] features,
        AccountManagerCallback<Bundle> callback, Handler handler) {
        (new AmsTask(null, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.getAccountByTypeAndFeatures(mResponse, accountType, features,
                    mContext.getOpPackageName());
            }

        }).start();
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

        @Override
        public void doWork() throws RemoteException {
            getAccountByTypeAndFeatures(mAccountType, mFeatures,
                    new AccountManagerCallback<Bundle>() {
                        @Override
                        public void run(AccountManagerFuture<Bundle> future) {
                            String accountName = null;
                            String accountType = null;
                            try {
                                Bundle result = future.getResult();
                                accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                                accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
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

                            if (accountName == null) {
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
                                    result.putBinder(KEY_ACCOUNT_ACCESS_ID, null);
                                    try {
                                        mResponse.onResult(result);
                                    } catch (RemoteException e) {
                                        // this will never happen
                                    }
                                    // we are done
                                }
                            } else {
                                mNumAccounts = 1;
                                Account account = new Account(accountName, accountType);
                                // have a single account, return an authtoken for it
                                if (mActivity == null) {
                                    mFuture = getAuthToken(account, mAuthTokenType,
                                            false /* notifyAuthFailure */, mMyCallback, mHandler);
                                } else {
                                    mFuture = getAuthToken(account, mAuthTokenType, mLoginOptions,
                                            mActivity, mMyCallback, mHandler);
                                }
                            }
                        }}, mHandler);
        }

        @Override
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
                    final String accessId = result.getString(KEY_ACCOUNT_ACCESS_ID);
                    final Account account = new Account(accountName, accountType, accessId);
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
     * This convenience helper combines the functionality of {@link #getAccountsByTypeAndFeatures},
     * {@link #getAuthToken}, and {@link #addAccount}.
     *
     * <p>
     * This method gets a list of the accounts matching specific type and feature set which are
     * visible to the caller (see {@link #getAccountsByType} for details);
     * if there is exactly one already visible account, it is used; if there are some
     * accounts for which user grant visibility, the user is prompted to pick one; if there are
     * none, the user is prompted to add one. Finally, an auth token is acquired for the chosen
     * account.
     *
     * <p>
     * This method may be called from any thread, but the returned {@link AccountManagerFuture} must
     * not be used on the main thread.
     *
     * <p>
     * <b>NOTE:</b> If targeting your app to work on API level 22 and before, MANAGE_ACCOUNTS
     * permission is needed for those platforms. See docs for this function in API level 22.
     *
     * @param accountType The account type required (see {@link #getAccountsByType}), must not be
     *        null
     * @param authTokenType The desired auth token type (see {@link #getAuthToken}), must not be
     *        null
     * @param features Required features for the account (see
     *        {@link #getAccountsByTypeAndFeatures}), may be null or empty
     * @param activity The {@link Activity} context to use for launching new sub-Activities to
     *        prompt to add an account, select an account, and/or enter a password, as necessary;
     *        used only to call startActivity(); should not be null
     * @param addAccountOptions Authenticator-specific options to use for adding new accounts; may
     *        be null or empty
     * @param getAuthTokenOptions Authenticator-specific options to use for getting auth tokens; may
     *        be null or empty
     * @param callback Callback to invoke when the request completes, null for no callback
     * @param handler {@link Handler} identifying the callback thread, null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with at least the
     *         following fields:
     *         <ul>
     *         <li>{@link #KEY_ACCOUNT_NAME} - the name of the account
     *         <li>{@link #KEY_ACCOUNT_TYPE} - the type of the account
     *         <li>{@link #KEY_AUTHTOKEN} - the auth token you wanted
     *         </ul>
     *
     *         If an error occurred, {@link AccountManagerFuture#getResult()} throws:
     *         <ul>
     *         <li>{@link AuthenticatorException} if no authenticator was registered for this
     *         account type or the authenticator failed to respond
     *         <li>{@link OperationCanceledException} if the operation was canceled for any reason,
     *         including the user canceling any operation
     *         <li>{@link IOException} if the authenticator experienced an I/O problem updating
     *         settings, usually because of network trouble
     *         </ul>
     */
    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(
            final String accountType, final String authTokenType, final String[] features,
            final Activity activity, final Bundle addAccountOptions,
            final Bundle getAuthTokenOptions, final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("account type is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final GetAuthTokenByTypeAndFeaturesTask task =
                new GetAuthTokenByTypeAndFeaturesTask(accountType, authTokenType, features,
                activity, addAccountOptions, getAuthTokenOptions, callback, handler);
        task.start();
        return task;
    }

    /**
     * Deprecated in favor of {@link #newChooseAccountIntent(Account, List, String[], String,
     * String, String[], Bundle)}.
     *
     * Returns an intent to an {@link Activity} that prompts the user to choose from a list of
     * accounts.
     * The caller will then typically start the activity by calling
     * <code>startActivityForResult(intent, ...);</code>.
     * <p>
     * On success the activity returns a Bundle with the account name and type specified using
     * keys {@link #KEY_ACCOUNT_NAME} and {@link #KEY_ACCOUNT_TYPE}.
     * Chosen account is marked as {@link #VISIBILITY_USER_MANAGED_VISIBLE} to the caller
     * (see {@link #setAccountVisibility}) and will be returned to it in consequent
     * {@link #getAccountsByType}) calls.
     * <p>
     * The most common case is to call this with one account type, e.g.:
     * <p>
     * <pre>  newChooseAccountIntent(null, null, new String[]{"com.google"}, false, null,
     * null, null, null);</pre>
     * @param selectedAccount if specified, indicates that the {@link Account} is the currently
     * selected one, according to the caller's definition of selected.
     * @param allowableAccounts an optional {@link List} of accounts that are allowed to be
     * shown. If not specified then this field will not limit the displayed accounts.
     * @param allowableAccountTypes an optional string array of account types. These are used
     * both to filter the shown accounts and to filter the list of account types that are shown
     * when adding an account. If not specified then this field will not limit the displayed
     * account types when adding an account.
     * @param alwaysPromptForAccount boolean that is ignored.
     * @param descriptionOverrideText if non-null this string is used as the description in the
     * accounts chooser screen rather than the default
     * @param addAccountAuthTokenType this string is passed as the {@link #addAccount}
     * authTokenType parameter
     * @param addAccountRequiredFeatures this string array is passed as the {@link #addAccount}
     * requiredFeatures parameter
     * @param addAccountOptions This {@link Bundle} is passed as the {@link #addAccount} options
     * parameter
     * @return an {@link Intent} that can be used to launch the ChooseAccount activity flow.
     */
    @Deprecated
    static public Intent newChooseAccountIntent(
            Account selectedAccount,
            ArrayList<Account> allowableAccounts,
            String[] allowableAccountTypes,
            boolean alwaysPromptForAccount,
            String descriptionOverrideText,
            String addAccountAuthTokenType,
            String[] addAccountRequiredFeatures,
            Bundle addAccountOptions) {
        return newChooseAccountIntent(
                selectedAccount,
                allowableAccounts,
                allowableAccountTypes,
                descriptionOverrideText,
                addAccountAuthTokenType,
                addAccountRequiredFeatures,
                addAccountOptions);
    }

    /**
     * Returns an intent to an {@link Activity} that prompts the user to choose from a list of
     * accounts.
     * The caller will then typically start the activity by calling
     * <code>startActivityForResult(intent, ...);</code>.
     * <p>
     * On success the activity returns a Bundle with the account name and type specified using
     * keys {@link #KEY_ACCOUNT_NAME} and {@link #KEY_ACCOUNT_TYPE}.
     * Chosen account is marked as {@link #VISIBILITY_USER_MANAGED_VISIBLE} to the caller
     * (see {@link #setAccountVisibility}) and will be returned to it in consequent
     * {@link #getAccountsByType}) calls.
     * <p>
     * The most common case is to call this with one account type, e.g.:
     * <p>
     * <pre>  newChooseAccountIntent(null, null, new String[]{"com.google"}, null, null, null,
     * null);</pre>
     * @param selectedAccount if specified, indicates that the {@link Account} is the currently
     * selected one, according to the caller's definition of selected.
     * @param allowableAccounts an optional {@link List} of accounts that are allowed to be
     * shown. If not specified then this field will not limit the displayed accounts.
     * @param allowableAccountTypes an optional string array of account types. These are used
     * both to filter the shown accounts and to filter the list of account types that are shown
     * when adding an account. If not specified then this field will not limit the displayed
     * account types when adding an account.
     * @param descriptionOverrideText if non-null this string is used as the description in the
     * accounts chooser screen rather than the default
     * @param addAccountAuthTokenType this string is passed as the {@link #addAccount}
     * authTokenType parameter
     * @param addAccountRequiredFeatures this string array is passed as the {@link #addAccount}
     * requiredFeatures parameter
     * @param addAccountOptions This {@link Bundle} is passed as the {@link #addAccount} options
     * parameter
     * @return an {@link Intent} that can be used to launch the ChooseAccount activity flow.
     */
    static public Intent newChooseAccountIntent(
            Account selectedAccount,
            List<Account> allowableAccounts,
            String[] allowableAccountTypes,
            String descriptionOverrideText,
            String addAccountAuthTokenType,
            String[] addAccountRequiredFeatures,
            Bundle addAccountOptions) {
        Intent intent = new Intent();
        ComponentName componentName = ComponentName.unflattenFromString(
                Resources.getSystem().getString(R.string.config_chooseTypeAndAccountActivity));
        intent.setClassName(componentName.getPackageName(),
                componentName.getClassName());
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_ALLOWABLE_ACCOUNTS_ARRAYLIST,
                allowableAccounts == null ? null : new ArrayList<Account>(allowableAccounts));
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_ALLOWABLE_ACCOUNT_TYPES_STRING_ARRAY,
                allowableAccountTypes);
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_OPTIONS_BUNDLE,
                addAccountOptions);
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_SELECTED_ACCOUNT, selectedAccount);
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_DESCRIPTION_TEXT_OVERRIDE,
                descriptionOverrideText);
        intent.putExtra(ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_AUTH_TOKEN_TYPE_STRING,
                addAccountAuthTokenType);
        intent.putExtra(
                ChooseTypeAndAccountActivity.EXTRA_ADD_ACCOUNT_REQUIRED_FEATURES_STRING_ARRAY,
                addAccountRequiredFeatures);
        return intent;
    }

    private final HashMap<OnAccountsUpdateListener, Handler> mAccountsUpdatedListeners =
            Maps.newHashMap();

    private final HashMap<OnAccountsUpdateListener, Set<String> > mAccountsUpdatedListenersTypes =
            Maps.newHashMap();

    /**
     * BroadcastReceiver that listens for the ACTION_VISIBLE_ACCOUNTS_CHANGED intent
     * so that it can read the updated list of accounts and send them to the listener
     * in mAccountsUpdatedListeners.
     */
    private final BroadcastReceiver mAccountsChangedBroadcastReceiver = new BroadcastReceiver() {
        @Override
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
     * Adds an {@link OnAccountsUpdateListener} to this instance of the {@link AccountManager}. This
     * listener will be notified whenever user or AbstractAcccountAuthenticator made changes to
     * accounts of any type related to the caller. This method is equivalent to
     * addOnAccountsUpdatedListener(listener, handler, updateImmediately, null)
     *
     * @see #addOnAccountsUpdatedListener(OnAccountsUpdateListener, Handler, boolean,
     *      String[])
     */
    public void addOnAccountsUpdatedListener(final OnAccountsUpdateListener listener,
            Handler handler, boolean updateImmediately) {
        addOnAccountsUpdatedListener(listener, handler,updateImmediately, null);
    }

    /**
     * Adds an {@link OnAccountsUpdateListener} to this instance of the {@link AccountManager}. This
     * listener will be notified whenever user or AbstractAcccountAuthenticator made changes to
     * accounts of given types related to the caller -
     * either list of accounts returned by {@link #getAccounts()}
     * was changed, or new account was added for which user can grant access to the caller.
     * <p>
     * As long as this listener is present, the AccountManager instance will not be
     * garbage-collected, and neither will the {@link Context} used to retrieve it, which may be a
     * large Activity instance. To avoid memory leaks, you must remove this listener before then.
     * Normally listeners are added in an Activity or Service's {@link Activity#onCreate} and
     * removed in {@link Activity#onDestroy}.
     * <p>
     * It is safe to call this method from the main thread.
     *
     * @param listener The listener to send notifications to
     * @param handler {@link Handler} identifying the thread to use for notifications, null for the
     *        main thread
     * @param updateImmediately If true, the listener will be invoked (on the handler thread) right
     *        away with the current account list
     * @param accountTypes If set, only changes to accounts of given types will be reported.
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was already added
     */
    public void addOnAccountsUpdatedListener(final OnAccountsUpdateListener listener,
            Handler handler, boolean updateImmediately, String[] accountTypes) {
        if (listener == null) {
            throw new IllegalArgumentException("the listener is null");
        }
        synchronized (mAccountsUpdatedListeners) {
            if (mAccountsUpdatedListeners.containsKey(listener)) {
                throw new IllegalStateException("this listener is already added");
            }
            final boolean wasEmpty = mAccountsUpdatedListeners.isEmpty();

            mAccountsUpdatedListeners.put(listener, handler);
            if (accountTypes != null) {
                mAccountsUpdatedListenersTypes.put(listener,
                    new HashSet<String>(Arrays.asList(accountTypes)));
            } else {
                mAccountsUpdatedListenersTypes.put(listener, null);
            }

            if (wasEmpty) {
                // Register a broadcast receiver to monitor account changes
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(ACTION_VISIBLE_ACCOUNTS_CHANGED);
                // To recover from disk-full.
                intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
                mContext.registerReceiver(mAccountsChangedBroadcastReceiver, intentFilter);
            }

            try {
                // Notify AccountManagedService about new receiver.
                // The receiver must be unregistered later exactly one time
                mService.registerAccountListener(accountTypes, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
            Set<String> accountTypes = mAccountsUpdatedListenersTypes.get(listener);
            String[] accountsArray;
            if (accountTypes != null) {
                accountsArray = accountTypes.toArray(new String[accountTypes.size()]);
            } else {
                accountsArray = null;
            }
            mAccountsUpdatedListeners.remove(listener);
            mAccountsUpdatedListenersTypes.remove(listener);
            if (mAccountsUpdatedListeners.isEmpty()) {
                mContext.unregisterReceiver(mAccountsChangedBroadcastReceiver);
            }
            try {
                mService.unregisterAccountListener(accountsArray, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Asks the user to authenticate with an account of a specified type. The
     * authenticator for this account type processes this request with the
     * appropriate user interface. If the user does elect to authenticate with a
     * new account, a bundle of session data for installing the account later is
     * returned with optional account password and account status token.
     * <p>
     * This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     * <p>
     * <p>
     * <b>NOTE:</b> The account will not be installed to the device by calling
     * this api alone. #finishSession should be called after this to install the
     * account on device.
     *
     * @param accountType The type of account to add; must not be null
     * @param authTokenType The type of auth token (see {@link #getAuthToken})
     *            this account will need to be able to generate, null for none
     * @param requiredFeatures The features (see {@link #hasFeatures}) this
     *            account must have, null for none
     * @param options Authenticator-specific options for the request, may be
     *            null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *            authenticator-defined sub-Activity to prompt the user to
     *            create an account; used only to call startActivity(); if null,
     *            the prompt will not be launched directly, but the necessary
     *            {@link Intent} will be returned to the caller instead
     * @param callback Callback to invoke when the request completes, null for
     *            no callback
     * @param handler {@link Handler} identifying the callback thread, null for
     *            the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *         these fields if activity was specified and user was authenticated
     *         with an account:
     *         <ul>
     *         <li>{@link #KEY_ACCOUNT_SESSION_BUNDLE} - encrypted Bundle for
     *         adding the the to the device later.
     *         <li>{@link #KEY_ACCOUNT_STATUS_TOKEN} - optional, token to check
     *         status of the account
     *         </ul>
     *         If no activity was specified, the returned Bundle contains only
     *         {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     *         actual account creation process. If authenticator doesn't support
     *         this method, the returned Bundle contains only
     *         {@link #KEY_ACCOUNT_SESSION_BUNDLE} with encrypted
     *         {@code options} needed to add account later. If an error
     *         occurred, {@link AccountManagerFuture#getResult()} throws:
     *         <ul>
     *         <li>{@link AuthenticatorException} if no authenticator was
     *         registered for this account type or the authenticator failed to
     *         respond
     *         <li>{@link OperationCanceledException} if the operation was
     *         canceled for any reason, including the user canceling the
     *         creation process or adding accounts (of this type) has been
     *         disabled by policy
     *         <li>{@link IOException} if the authenticator experienced an I/O
     *         problem creating a new account, usually because of network
     *         trouble
     *         </ul>
     * @see #finishSession
     */
    public AccountManagerFuture<Bundle> startAddAccountSession(
            final String accountType,
            final String authTokenType,
            final String[] requiredFeatures,
            final Bundle options,
            final Activity activity,
            AccountManagerCallback<Bundle> callback,
            Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        final Bundle optionsIn = new Bundle();
        if (options != null) {
            optionsIn.putAll(options);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.startAddAccountSession(
                        mResponse,
                        accountType,
                        authTokenType,
                        requiredFeatures,
                        activity != null,
                        optionsIn);
            }
        }.start();
    }

    /**
     * Asks the user to enter a new password for an account but not updating the
     * saved credentials for the account until {@link #finishSession} is called.
     * <p>
     * This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     * <p>
     * <b>NOTE:</b> The saved credentials for the account alone will not be
     * updated by calling this API alone. #finishSession should be called after
     * this to update local credentials
     *
     * @param account The account to update credentials for
     * @param authTokenType The credentials entered must allow an auth token of
     *            this type to be created (but no actual auth token is
     *            returned); may be null
     * @param options Authenticator-specific options for the request; may be
     *            null or empty
     * @param activity The {@link Activity} context to use for launching a new
     *            authenticator-defined sub-Activity to prompt the user to enter
     *            a password; used only to call startActivity(); if null, the
     *            prompt will not be launched directly, but the necessary
     *            {@link Intent} will be returned to the caller instead
     * @param callback Callback to invoke when the request completes, null for
     *            no callback
     * @param handler {@link Handler} identifying the callback thread, null for
     *            the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *         these fields if an activity was supplied and user was
     *         successfully re-authenticated to the account:
     *         <ul>
     *         <li>{@link #KEY_ACCOUNT_SESSION_BUNDLE} - encrypted Bundle for
     *         updating the local credentials on device later.
     *         <li>{@link #KEY_ACCOUNT_STATUS_TOKEN} - optional, token to check
     *         status of the account
     *         </ul>
     *         If no activity was specified, the returned Bundle contains
     *         {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     *         password prompt. If an error occurred,
     *         {@link AccountManagerFuture#getResult()} throws:
     *         <ul>
     *         <li>{@link AuthenticatorException} if the authenticator failed to
     *         respond
     *         <li>{@link OperationCanceledException} if the operation was
     *         canceled for any reason, including the user canceling the
     *         password prompt
     *         <li>{@link IOException} if the authenticator experienced an I/O
     *         problem verifying the password, usually because of network
     *         trouble
     *         </ul>
     * @see #finishSession
     */
    public AccountManagerFuture<Bundle> startUpdateCredentialsSession(
            final Account account,
            final String authTokenType,
            final Bundle options,
            final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }

        // Always include the calling package name. This just makes life easier
        // down stream.
        final Bundle optionsIn = new Bundle();
        if (options != null) {
            optionsIn.putAll(options);
        }
        optionsIn.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.startUpdateCredentialsSession(
                        mResponse,
                        account,
                        authTokenType,
                        activity != null,
                        optionsIn);
            }
        }.start();
    }

    /**
     * Finishes the session started by {@link #startAddAccountSession} or
     * {@link #startUpdateCredentialsSession}. This will either add the account
     * to AccountManager or update the local credentials stored.
     * <p>
     * This method may be called from any thread, but the returned
     * {@link AccountManagerFuture} must not be used on the main thread.
     *
     * @param sessionBundle a {@link Bundle} created by {@link #startAddAccountSession} or
     *            {@link #startUpdateCredentialsSession}
     * @param activity The {@link Activity} context to use for launching a new
     *            authenticator-defined sub-Activity to prompt the user to
     *            create an account or reauthenticate existing account; used
     *            only to call startActivity(); if null, the prompt will not
     *            be launched directly, but the necessary {@link Intent} will
     *            be returned to the caller instead
     * @param callback Callback to invoke when the request completes, null for
     *            no callback
     * @param handler {@link Handler} identifying the callback thread, null for
     *            the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Bundle with
     *         these fields if an activity was supplied and an account was added
     *         to device or local credentials were updated::
     *         <ul>
     *         <li>{@link #KEY_ACCOUNT_NAME} - the name of the account created
     *         <li>{@link #KEY_ACCOUNT_TYPE} - the type of the account
     *         <li>{@link #KEY_ACCOUNT_STATUS_TOKEN} - optional, token to check
     *         status of the account
     *         </ul>
     *         If no activity was specified and additional information is needed
     *         from user, the returned Bundle may contains only
     *         {@link #KEY_INTENT} with the {@link Intent} needed to launch the
     *         actual account creation process. If an error occurred,
     *         {@link AccountManagerFuture#getResult()} throws:
     *         <ul>
     *         <li>{@link AuthenticatorException} if no authenticator was
     *         registered for this account type or the authenticator failed to
     *         respond
     *         <li>{@link OperationCanceledException} if the operation was
     *         canceled for any reason, including the user canceling the
     *         creation process or adding accounts (of this type) has been
     *         disabled by policy
     *         <li>{@link IOException} if the authenticator experienced an I/O
     *         problem creating a new account, usually because of network
     *         trouble
     *         </ul>
     * @see #startAddAccountSession and #startUpdateCredentialsSession
     */
    public AccountManagerFuture<Bundle> finishSession(
            final Bundle sessionBundle,
            final Activity activity,
            AccountManagerCallback<Bundle> callback,
            Handler handler) {
        return finishSessionAsUser(
                sessionBundle,
                activity,
                Process.myUserHandle(),
                callback,
                handler);
    }

    /**
     * @see #finishSession
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public AccountManagerFuture<Bundle> finishSessionAsUser(
            final Bundle sessionBundle,
            final Activity activity,
            final UserHandle userHandle,
            AccountManagerCallback<Bundle> callback,
            Handler handler) {
        if (sessionBundle == null) {
            throw new IllegalArgumentException("sessionBundle is null");
        }

        /* Add information required by add account flow */
        final Bundle appInfo = new Bundle();
        appInfo.putString(KEY_ANDROID_PACKAGE_NAME, mContext.getPackageName());

        return new AmsTask(activity, handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.finishSessionAsUser(
                        mResponse,
                        sessionBundle,
                        activity != null,
                        appInfo,
                        userHandle.getIdentifier());
            }
        }.start();
    }

    /**
     * Checks whether {@link #updateCredentials} or {@link #startUpdateCredentialsSession} should be
     * called with respect to the specified account.
     * <p>
     * This method may be called from any thread, but the returned {@link AccountManagerFuture} must
     * not be used on the main thread.
     *
     * @param account The {@link Account} to be checked whether {@link #updateCredentials} or
     * {@link #startUpdateCredentialsSession} should be called
     * @param statusToken a String of token to check account staus
     * @param callback Callback to invoke when the request completes, null for no callback
     * @param handler {@link Handler} identifying the callback thread, null for the main thread
     * @return An {@link AccountManagerFuture} which resolves to a Boolean, true if the credentials
     *         of the account should be updated.
     */
    public AccountManagerFuture<Boolean> isCredentialsUpdateSuggested(
            final Account account,
            final String statusToken,
            AccountManagerCallback<Boolean> callback,
            Handler handler) {
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }

        if (TextUtils.isEmpty(statusToken)) {
            throw new IllegalArgumentException("status token is empty");
        }

        return new Future2Task<Boolean>(handler, callback) {
            @Override
            public void doWork() throws RemoteException {
                mService.isCredentialsUpdateSuggested(
                        mResponse,
                        account,
                        statusToken);
            }
            @Override
            public Boolean bundleToResult(Bundle bundle) throws AuthenticatorException {
                if (!bundle.containsKey(KEY_BOOLEAN_RESULT)) {
                    throw new AuthenticatorException("no result in response");
                }
                return bundle.getBoolean(KEY_BOOLEAN_RESULT);
            }
        }.start();
    }

    /**
     * Gets whether a given package under a user has access to an account.
     * Can be called only from the system UID.
     *
     * @param account The account for which to check.
     * @param packageName The package for which to check.
     * @param userHandle The user for which to check.
     * @return True if the package can access the account.
     *
     * @hide
     */
    public boolean hasAccountAccess(@NonNull Account account, @NonNull String packageName,
            @NonNull UserHandle userHandle) {
        try {
            return mService.hasAccountAccess(account, packageName, userHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates an intent to request access to a given account for a UID.
     * The returned intent should be stated for a result where {@link
     * Activity#RESULT_OK} result means access was granted whereas {@link
     * Activity#RESULT_CANCELED} result means access wasn't granted. Can
     * be called only from the system UID.
     *
     * @param account The account for which to request.
     * @param packageName The package name which to request.
     * @param userHandle The user for which to request.
     * @return The intent to request account access or null if the package
     *     doesn't exist.
     *
     * @hide
     */
    public IntentSender createRequestAccountAccessIntentSenderAsUser(@NonNull Account account,
            @NonNull String packageName, @NonNull UserHandle userHandle) {
        try {
            return mService.createRequestAccountAccessIntentSenderAsUser(account, packageName,
                    userHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
