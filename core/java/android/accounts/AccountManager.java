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
 * A class that helps with interactions with the AccountManagerService. It provides
 * methods to allow for account, password, and authtoken management for all accounts on the
 * device. One accesses the {@link AccountManager} by calling:
 *    AccountManager accountManager = AccountManager.get(context);
 *
 * <p>
 * TODO(fredq) this interface is still in flux
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

    public static AccountManager get(Context context) {
        return (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
    }

    public String getPassword(final Account account) {
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    public String getUserData(final Account account, final String key) {
        try {
            return mService.getUserData(account, key);
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    public AuthenticatorDescription[] getAuthenticatorTypes() {
        try {
            return mService.getAuthenticatorTypes();
        } catch (RemoteException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    public Account[] getAccounts() {
        try {
            return mService.getAccounts(null);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public Account[] getAccountsByType(String type) {
        try {
            return mService.getAccounts(type);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
        try {
            return mService.addAccount(account, password, extras);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

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

    public void invalidateAuthToken(final String accountType, final String authToken) {
        try {
            mService.invalidateAuthToken(accountType, authToken);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public String peekAuthToken(final Account account, final String authTokenType) {
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public void setPassword(final Account account, final String password) {
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public void clearPassword(final Account account) {
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public void setUserData(final Account account, final String key, final String value) {
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public void setAuthToken(Account account, final String authTokenType, final String authToken) {
        try {
            mService.setAuthToken(account, authTokenType, authToken);
        } catch (RemoteException e) {
            // won't ever happen
            throw new RuntimeException(e);
        }
    }

    public String blockingGetAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {
        Bundle bundle = getAuthToken(account, authTokenType, notifyAuthFailure, null /* callback */,
                null /* handler */).getResult();
        return bundle.getString(KEY_AUTHTOKEN);
    }

    /**
     * Request the auth token for this account/authTokenType. If this succeeds then the
     * auth token will then be passed to the activity. If this results in an authentication
     * failure then a login intent will be returned that can be invoked to prompt the user to
     * update their credentials. This login activity will return the auth token to the calling
     * activity. If activity is null then the login intent will not be invoked.
     *
     * @param account the account whose auth token should be retrieved
     * @param authTokenType the auth token type that should be retrieved
     * @param loginOptions
     * @param activity the activity to launch the login intent, if necessary, and to which
     */
    public AccountManagerFuture<Bundle> getAuthToken(
            final Account account, final String authTokenType, final Bundle loginOptions,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (activity == null) throw new IllegalArgumentException("activity is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        false /* notifyOnAuthFailure */, true /* expectActivityLaunch */,
                        loginOptions);
            }
        }.start();
    }

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

    public AccountManagerFuture<Bundle> addAccount(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.addAcount(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, addAccountOptions);
            }
        }.start();
    }

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

    public AccountManagerFuture<Bundle> updateCredentials(final Account account, final String authTokenType,
            final Bundle loginOptions, final Activity activity,
            final AccountManagerCallback<Bundle> callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.updateCredentials(mResponse, account, authTokenType, activity != null,
                        loginOptions);
            }
        }.start();
    }

    public AccountManagerFuture<Bundle> editProperties(final String accountType, final Activity activity,
            final AccountManagerCallback<Bundle> callback,
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
            // TODO(fredq) remove the log and throw this exception when the callers are fixed
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

    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(
            final String accountType, final String authTokenType, final String[] features,
            final Activity activityForPrompting, final Bundle addAccountOptions,
            final Bundle loginOptions,
            final AccountManagerCallback<Bundle> callback, final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("account type is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final GetAuthTokenByTypeAndFeaturesTask task =
                new GetAuthTokenByTypeAndFeaturesTask(accountType, authTokenType, features,
                activityForPrompting, addAccountOptions, loginOptions, callback, handler);
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
            throw new IllegalArgumentException("the listener is null");
        }
        synchronized (mAccountsUpdatedListeners) {
            if (!mAccountsUpdatedListeners.containsKey(listener)) {
                throw new IllegalStateException("this listener was not previously added");
            }
            mAccountsUpdatedListeners.remove(listener);
            if (mAccountsUpdatedListeners.isEmpty()) {
                mContext.unregisterReceiver(mAccountsChangedBroadcastReceiver);
            }
        }
    }
}
