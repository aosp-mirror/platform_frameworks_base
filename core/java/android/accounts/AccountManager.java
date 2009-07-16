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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Parcelable;
import android.util.Config;
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
 * device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. One accesses the {@link AccountManager} by calling:
 *    AccountManager accountManager = AccountManager.get(context);
 *
 * <p>
 * TODO(fredq) this interface is still in flux
 */
public class AccountManager {
    private static final String TAG = "AccountManager";

    private final Context mContext;
    private final IAccountManager mService;
    private final Handler mMainHandler;

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

    public String blockingGetPassword(Account account) {
        ensureNotOnMainThread();
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<String> getPassword(final Future1Callback<String> callback,
            final Account account, final Handler handler) {
        return startAsFuture(callback, handler, new Callable<String>() {
            public String call() throws Exception {
                return blockingGetPassword(account);
            }
        });
    }

    public String blockingGetUserData(Account account, String key) {
        ensureNotOnMainThread();
        try {
            return mService.getUserData(account, key);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<String> getUserData(Future1Callback<String> callback,
            final Account account, final String key, Handler handler) {
        return startAsFuture(callback, handler, new Callable<String>() {
            public String call() throws Exception {
                return blockingGetUserData(account, key);
            }
        });
    }

    public AuthenticatorDescription[] blockingGetAuthenticatorTypes() {
        ensureNotOnMainThread();
        try {
            return mService.getAuthenticatorTypes();
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<AuthenticatorDescription[]> getAuthenticatorTypes(
            Future1Callback<AuthenticatorDescription[]> callback, Handler handler) {
        return startAsFuture(callback, handler, new Callable<AuthenticatorDescription[]>() {
            public AuthenticatorDescription[] call() throws Exception {
                return blockingGetAuthenticatorTypes();
            }
        });
    }

    public Account[] blockingGetAccounts() {
        ensureNotOnMainThread();
        try {
            return mService.getAccounts();
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Account[] blockingGetAccountsByType(String accountType) {
        ensureNotOnMainThread();
        try {
            return mService.getAccountsByType(accountType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<Account[]> getAccounts(Future1Callback<Account[]> callback, Handler handler) {
        return startAsFuture(callback, handler, new Callable<Account[]>() {
            public Account[] call() throws Exception {
                return blockingGetAccounts();
            }
        });
    }

    public Future1<Account[]> getAccountsByType(Future1Callback<Account[]> callback,
            final String type, Handler handler) {
        return startAsFuture(callback, handler, new Callable<Account[]>() {
            public Account[] call() throws Exception {
                return blockingGetAccountsByType(type);
            }
        });
    }

    public boolean blockingAddAccountExplicitly(Account account, String password, Bundle extras) {
        ensureNotOnMainThread();
        try {
            return mService.addAccount(account, password, extras);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<Boolean> addAccountExplicitly(final Future1Callback<Boolean> callback,
            final Account account, final String password, final Bundle extras,
            final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Boolean>() {
            public Boolean call() throws Exception {
                return blockingAddAccountExplicitly(account, password, extras);
            }
        });
    }

    public void blockingRemoveAccount(Account account) {
        ensureNotOnMainThread();
        try {
            mService.removeAccount(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> removeAccount(Future1Callback<Void> callback, final Account account,
            final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingRemoveAccount(account);
                return null;
            }
        });
    }

    public void blockingInvalidateAuthToken(String accountType, String authToken) {
        ensureNotOnMainThread();
        try {
            mService.invalidateAuthToken(accountType, authToken);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> invalidateAuthToken(Future1Callback<Void> callback,
            final String accountType, final String authToken, final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingInvalidateAuthToken(accountType, authToken);
                return null;
            }
        });
    }

    public String blockingPeekAuthToken(Account account, String authTokenType) {
        ensureNotOnMainThread();
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<String> peekAuthToken(Future1Callback<String> callback,
            final Account account, final String authTokenType, final Handler handler) {
        return startAsFuture(callback, handler, new Callable<String>() {
            public String call() throws Exception {
                return blockingPeekAuthToken(account, authTokenType);
            }
        });
    }

    public void blockingSetPassword(Account account, String password) {
        ensureNotOnMainThread();
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> setPassword(Future1Callback<Void> callback,
            final Account account, final String password, final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingSetPassword(account, password);
                return null;
            }
        });
    }

    public void blockingClearPassword(Account account) {
        ensureNotOnMainThread();
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> clearPassword(final Future1Callback<Void> callback, final Account account,
            final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingClearPassword(account);
                return null;
            }
        });
    }

    public void blockingSetUserData(Account account, String key, String value) {
        ensureNotOnMainThread();
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> setUserData(Future1Callback<Void> callback,
            final Account account, final String key, final String value, final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingSetUserData(account, key, value);
                return null;
            }
        });
    }

    public void blockingSetAuthToken(Account account, String authTokenType, String authToken) {
        ensureNotOnMainThread();
        try {
            mService.setAuthToken(account, authTokenType, authToken);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public Future1<Void> setAuthToken(Future1Callback<Void> callback,
            final Account account, final String authTokenType, final String authToken,
            final Handler handler) {
        return startAsFuture(callback, handler, new Callable<Void>() {
            public Void call() throws Exception {
                blockingSetAuthToken(account, authTokenType, authToken);
                return null;
            }
        });
    }

    public String blockingGetAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {
        ensureNotOnMainThread();
        Bundle bundle = getAuthToken(account, authTokenType, notifyAuthFailure, null /* callback */,
                null /* handler */).getResult();
        return bundle.getString(Constants.AUTHTOKEN_KEY);
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
    public Future2 getAuthToken(
            final Account account, final String authTokenType, final Bundle loginOptions,
            final Activity activity, Future2Callback callback, Handler handler) {
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

    public Future2 getAuthToken(
            final Account account, final String authTokenType, final boolean notifyAuthFailure,
            Future2Callback callback, Handler handler) {
        if (account == null) throw new IllegalArgumentException("account is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        return new AmsTask(null, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAuthToken(mResponse, account, authTokenType,
                        notifyAuthFailure, false /* expectActivityLaunch */, null /* options */);
            }
        }.start();
    }

    public Future2 addAccount(final String accountType,
            final String authTokenType, final String[] requiredFeatures,
            final Bundle addAccountOptions,
            final Activity activity, Future2Callback callback, Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.addAcount(mResponse, accountType, authTokenType,
                        requiredFeatures, activity != null, addAccountOptions);
            }
        }.start();
    }

    /** @deprecated use {@link #confirmCredentials} instead */
    public Future1<Boolean> confirmPassword(final Account account, final String password,
            Future1Callback<Boolean> callback, Handler handler) {
        return new AMSTaskBoolean(handler, callback) {
            public void doWork() throws RemoteException {
                mService.confirmPassword(response, account, password);
            }
        };
    }

    public Account[] blockingGetAccountsWithTypeAndFeatures(String type, String[] features)
            throws AuthenticatorException, IOException, OperationCanceledException {
        Future2 future = getAccountsWithTypeAndFeatures(type, features,
                null /* callback */, null /* handler */);
        Bundle result = future.getResult();
        Parcelable[] accountsTemp = result.getParcelableArray(Constants.ACCOUNTS_KEY);
        if (accountsTemp == null) {
            throw new AuthenticatorException("accounts should not be null");
        }
        Account[] accounts = new Account[accountsTemp.length];
        for (int i = 0; i < accountsTemp.length; i++) {
            accounts[i] = (Account) accountsTemp[i];
        }
        return accounts;
    }

    public Future2 getAccountsWithTypeAndFeatures(
            final String type, final String[] features,
            Future2Callback callback, Handler handler) {
        if (type == null) throw new IllegalArgumentException("type is null");
        return new AmsTask(null /* activity */, handler, callback) {
            public void doWork() throws RemoteException {
                mService.getAccountsByTypeAndFeatures(mResponse, type, features);
            }
        }.start();
    }

    public Future2 confirmCredentials(final Account account, final Activity activity,
            final Future2Callback callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.confirmCredentials(mResponse, account, activity != null);
            }
        }.start();
    }

    public Future2 updateCredentials(final Account account, final String authTokenType,
            final Bundle loginOptions, final Activity activity,
            final Future2Callback callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.updateCredentials(mResponse, account, authTokenType, activity != null,
                        loginOptions);
            }
        }.start();
    }

    public Future2 editProperties(final String accountType, final Activity activity,
            final Future2Callback callback,
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

    private void postToHandler(Handler handler, final Future2Callback callback,
            final Future2 future) {
        handler = handler == null ? mMainHandler : handler;
        handler.post(new Runnable() {
            public void run() {
                callback.run(future);
            }
        });
    }

    private void postToHandler(Handler handler, final OnAccountsUpdatedListener listener,
            final Account[] accounts) {
        handler = handler == null ? mMainHandler : handler;
        handler.post(new Runnable() {
            public void run() {
                listener.onAccountsUpdated(accounts);
            }
        });
    }

    private <V> void postToHandler(Handler handler, final Future1Callback<V> callback,
            final Future1<V> future) {
        handler = handler == null ? mMainHandler : handler;
        handler.post(new Runnable() {
            public void run() {
                callback.run(future);
            }
        });
    }

    private <V> Future1<V> startAsFuture(Future1Callback<V> callback, Handler handler,
            Callable<V> callable) {
        final FutureTaskWithCallback<V> task =
                new FutureTaskWithCallback<V>(callback, callable, handler);
        new Thread(task).start();
        return task;
    }

    private class FutureTaskWithCallback<V> extends FutureTask<V> implements Future1<V> {
        final Future1Callback<V> mCallback;
        final Handler mHandler;

        public FutureTaskWithCallback(Future1Callback<V> callback, Callable<V> callable,
                Handler handler) {
            super(callable);
            mCallback = callback;
            mHandler = handler;
        }

        protected void done() {
            if (mCallback != null) {
                postToHandler(mHandler, mCallback, this);
            }
        }

        public V internalGetResult(Long timeout, TimeUnit unit) throws OperationCanceledException {
            try {
                if (timeout == null) {
                    return get();
                } else {
                    return get(timeout, unit);
                }
            } catch (InterruptedException e) {
                // we will cancel the task below
            } catch (CancellationException e) {
                // we will cancel the task below
            } catch (TimeoutException e) {
                // we will cancel the task below
            } catch (ExecutionException e) {
                // this should never happen
                throw new IllegalStateException(e.getCause());
            } finally {
                cancel(true /* interruptIfRunning */);
            }
            throw new OperationCanceledException();
        }

        public V getResult() throws OperationCanceledException {
            return internalGetResult(null, null);
        }

        public V getResult(long timeout, TimeUnit unit) throws OperationCanceledException {
            return internalGetResult(null, null);
        }
    }

    private abstract class AmsTask extends FutureTask<Bundle> implements Future2 {
        final IAccountManagerResponse mResponse;
        final Handler mHandler;
        final Future2Callback mCallback;
        final Activity mActivity;
        final Thread mThread;
        public AmsTask(Activity activity, Handler handler, Future2Callback callback) {
            super(new Callable<Bundle>() {
                public Bundle call() throws Exception {
                    throw new IllegalStateException("this should never be called");
                }
            });

            mHandler = handler;
            mCallback = callback;
            mActivity = activity;
            mResponse = new Response();
            mThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        doWork();
                    } catch (RemoteException e) {
                        // never happens
                    }
                }
            }, "AmsTask");
        }

        public final Future2 start() {
            mThread.start();
            return this;
        }

        public abstract void doWork() throws RemoteException;

        private Bundle internalGetResult(Long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
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
                if (code == Constants.ERROR_CODE_CANCELED) {
                    // the authenticator indicated that this request was canceled, do so now
                    cancel(true /* mayInterruptIfRunning */);
                    return;
                }
                setException(convertErrorToException(code, message));
            }
        }

    }

    private abstract class AMSTaskBoolean extends FutureTask<Boolean> implements Future1<Boolean> {
        final IAccountManagerResponse response;
        final Handler mHandler;
        final Future1Callback<Boolean> mCallback;
        public AMSTaskBoolean(Handler handler, Future1Callback<Boolean> callback) {
            super(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    throw new IllegalStateException("this should never be called");
                }
            });

            mHandler = handler;
            mCallback = callback;
            response = new Response();

            new Thread(new Runnable() {
                public void run() {
                    try {
                        doWork();
                    } catch (RemoteException e) {
                        // never happens
                    }
                }
            }).start();
        }

        public abstract void doWork() throws RemoteException;


        protected void done() {
            if (mCallback != null) {
                postToHandler(mHandler, mCallback, this);
            }
        }

        private Boolean internalGetResult(Long timeout, TimeUnit unit) {
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
                return false;
            } catch (ExecutionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    return false;
                } else if (cause instanceof UnsupportedOperationException) {
                    return false;
                } else if (cause instanceof AuthenticatorException) {
                    return false;
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
            return false;
        }

        public Boolean getResult() throws OperationCanceledException {
            return internalGetResult(null, null);
        }

        public Boolean getResult(long timeout, TimeUnit unit) throws OperationCanceledException {
            return internalGetResult(timeout, unit);
        }

        private class Response extends IAccountManagerResponse.Stub {
            public void onResult(Bundle bundle) {
                try {
                    if (bundle.containsKey(Constants.BOOLEAN_RESULT_KEY)) {
                        set(bundle.getBoolean(Constants.BOOLEAN_RESULT_KEY));
                        return;
                    }
                } catch (ClassCastException e) {
                    // we will set the exception below
                }
                onError(Constants.ERROR_CODE_INVALID_RESPONSE, "no result in response");
            }

            public void onError(int code, String message) {
                if (code == Constants.ERROR_CODE_CANCELED) {
                    cancel(true /* mayInterruptIfRunning */);
                    return;
                }
                setException(convertErrorToException(code, message));
            }
        }

    }

    private Exception convertErrorToException(int code, String message) {
        if (code == Constants.ERROR_CODE_NETWORK_ERROR) {
            return new IOException(message);
        }

        if (code == Constants.ERROR_CODE_UNSUPPORTED_OPERATION) {
            return new UnsupportedOperationException(message);
        }

        if (code == Constants.ERROR_CODE_INVALID_RESPONSE) {
            return new AuthenticatorException(message);
        }

        if (code == Constants.ERROR_CODE_BAD_ARGUMENTS) {
            return new IllegalArgumentException(message);
        }

        return new AuthenticatorException(message);
    }

    private class GetAuthTokenByTypeAndFeaturesTask extends AmsTask implements Future2Callback {
        GetAuthTokenByTypeAndFeaturesTask(final String accountType, final String authTokenType,
                final String[] features, Activity activityForPrompting,
                final Bundle addAccountOptions, final Bundle loginOptions,
                Future2Callback callback, Handler handler) {
            super(activityForPrompting, handler, callback);
            if (accountType == null) throw new IllegalArgumentException("account type is null");
            mAccountType = accountType;
            mAuthTokenType = authTokenType;
            mFeatures = features;
            mAddAccountOptions = addAccountOptions;
            mLoginOptions = loginOptions;
            mMyCallback = this;
        }
        volatile Future2 mFuture = null;
        final String mAccountType;
        final String mAuthTokenType;
        final String[] mFeatures;
        final Bundle mAddAccountOptions;
        final Bundle mLoginOptions;
        final Future2Callback mMyCallback;

        public void doWork() throws RemoteException {
            getAccountsWithTypeAndFeatures(mAccountType, mFeatures, new Future2Callback() {
                public void run(Future2 future) {
                    Bundle getAccountsResult;
                    try {
                        getAccountsResult = future.getResult();
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

                    Parcelable[] accounts =
                            getAccountsResult.getParcelableArray(Constants.ACCOUNTS_KEY);
                    if (accounts.length == 0) {
                        if (mActivity != null) {
                            // no accounts, add one now. pretend that the user directly
                            // made this request
                            mFuture = addAccount(mAccountType, mAuthTokenType, mFeatures,
                                    mAddAccountOptions, mActivity, mMyCallback, mHandler);
                        } else {
                            // send result since we can't prompt to add an account
                            Bundle result = new Bundle();
                            result.putString(Constants.ACCOUNT_NAME_KEY, null);
                            result.putString(Constants.ACCOUNT_TYPE_KEY, null);
                            result.putString(Constants.AUTHTOKEN_KEY, null);
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
                            mFuture = getAuthToken((Account) accounts[0], mAuthTokenType,
                                    false /* notifyAuthFailure */, mMyCallback, mHandler);
                        } else {
                            mFuture = getAuthToken((Account) accounts[0],
                                    mAuthTokenType, mLoginOptions,
                                    mActivity, mMyCallback, mHandler);
                        }
                    } else {
                        if (mActivity != null) {
                            IAccountManagerResponse chooseResponse =
                                    new IAccountManagerResponse.Stub() {
                                public void onResult(Bundle value) throws RemoteException {
                                    Account account = new Account(
                                            value.getString(Constants.ACCOUNT_NAME_KEY),
                                            value.getString(Constants.ACCOUNT_TYPE_KEY));
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
                            intent.putExtra(Constants.ACCOUNTS_KEY, accounts);
                            intent.putExtra(Constants.ACCOUNT_MANAGER_RESPONSE_KEY,
                                    new AccountManagerResponse(chooseResponse));
                            mActivity.startActivity(intent);
                            // the result will arrive via the IAccountManagerResponse
                        } else {
                            // send result since we can't prompt to select an account
                            Bundle result = new Bundle();
                            result.putString(Constants.ACCOUNTS_KEY, null);
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



        // TODO(fredq) pass through the calls to our implemention of Future2 to the underlying
        // future that we create. We need to do things like have cancel cancel the mFuture, if set
        // or to cause this to be canceled if mFuture isn't set.
        // Once this is done then getAuthTokenByFeatures can be changed to return a Future2.

        public void run(Future2 future) {
            try {
                set(future.get());
            } catch (InterruptedException e) {
                cancel(true);
            } catch (CancellationException e) {
                cancel(true);
            } catch (ExecutionException e) {
                setException(e.getCause());
            }
        }
    }

    public void getAuthTokenByFeatures(
            final String accountType, final String authTokenType, final String[] features,
            final Activity activityForPrompting, final Bundle addAccountOptions,
            final Bundle loginOptions,
            final Future2Callback callback, final Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("account type is null");
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        new GetAuthTokenByTypeAndFeaturesTask(accountType, authTokenType,  features,
                activityForPrompting, addAccountOptions, loginOptions, callback, handler).start();
    }

    private final HashMap<OnAccountsUpdatedListener, Handler> mAccountsUpdatedListeners =
            Maps.newHashMap();

    // These variable are only used from the LOGIN_ACCOUNTS_CHANGED_ACTION BroadcastReceiver
    // and its getAccounts() callback which are both invoked only on the main thread. As a
    // result we don't need to protect against concurrent accesses and any changes are guaranteed
    // to be visible when used. Basically, these two variables are thread-confined.
    private Future1<Account[]> mAccountsLookupFuture = null;
    private boolean mAccountLookupPending = false;

    /**
     * BroadcastReceiver that listens for the LOGIN_ACCOUNTS_CHANGED_ACTION intent
     * so that it can read the updated list of accounts and send them to the listener
     * in mAccountsUpdatedListeners.
     */
    private final BroadcastReceiver mAccountsChangedBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(final Context context, final Intent intent) {
            if (mAccountsLookupFuture != null) {
                // an accounts lookup is already in progress,
                // don't bother starting another request
                mAccountLookupPending = true;
                return;
            }
            // initiate a read of the accounts
            mAccountsLookupFuture = getAccounts(new Future1Callback<Account[]>() {
                public void run(Future1<Account[]> future) {
                    // clear the future so that future receives will try the lookup again
                    mAccountsLookupFuture = null;

                    // get the accounts array
                    Account[] accounts;
                    try {
                        accounts = future.getResult();
                    } catch (OperationCanceledException e) {
                        // this should never happen, but if it does pretend we got another
                        // accounts changed broadcast
                        if (Config.LOGD) {
                            Log.d(TAG, "the accounts lookup for listener notifications was "
                                    + "canceled, try again by simulating the receipt of "
                                    + "a LOGIN_ACCOUNTS_CHANGED_ACTION broadcast");
                        }
                        onReceive(context, intent);
                        return;
                    }

                    // send the result to the listeners
                    synchronized (mAccountsUpdatedListeners) {
                        for (Map.Entry<OnAccountsUpdatedListener, Handler> entry :
                                mAccountsUpdatedListeners.entrySet()) {
                            Account[] accountsCopy = new Account[accounts.length];
                            // send the listeners a copy to make sure that one doesn't
                            // change what another sees
                            System.arraycopy(accounts, 0, accountsCopy, 0, accountsCopy.length);
                            postToHandler(entry.getValue(), entry.getKey(), accountsCopy);
                        }
                    }

                    // If mAccountLookupPending was set when the account lookup finished it
                    // means that we had previously ignored a LOGIN_ACCOUNTS_CHANGED_ACTION
                    // intent because a lookup was already in progress. Now that we are done
                    // with this lookup and notification pretend that another intent
                    // was received by calling onReceive() directly.
                    if (mAccountLookupPending) {
                        mAccountLookupPending = false;
                        onReceive(context, intent);
                        return;
                    }
                }
            }, mMainHandler);
        }
    };

    /**
     * Add a {@link OnAccountsUpdatedListener} to this instance of the {@link AccountManager}.
     * The listener is guaranteed to be invoked on the thread of the Handler that is passed
     * in or the main thread's Handler if handler is null.
     * @param listener the listener to add
     * @param handler the Handler whose thread will be used to invoke the listener. If null
     * the AccountManager context's main thread will be used.
     * @param updateImmediately if true then the listener will be invoked as a result of this
     * call.
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was already added
     */
    public void addOnAccountsUpdatedListener(final OnAccountsUpdatedListener listener,
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
                intentFilter.addAction(Constants.LOGIN_ACCOUNTS_CHANGED_ACTION);
                mContext.registerReceiver(mAccountsChangedBroadcastReceiver, intentFilter);
            }
        }

        if (updateImmediately) {
            getAccounts(new Future1Callback<Account[]>() {
                public void run(Future1<Account[]> future) {
                    try {
                        listener.onAccountsUpdated(future.getResult());
                    } catch (OperationCanceledException e) {
                        // ignore
                    }
                }
            }, handler);
        }
    }

    /**
     * Remove an {@link OnAccountsUpdatedListener} that was previously registered with
     * {@link #addOnAccountsUpdatedListener}.
     * @param listener the listener to remove
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalStateException if listener was not already added
     */
    public void removeOnAccountsUpdatedListener(OnAccountsUpdatedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("the listener is null");
        }
        synchronized (mAccountsUpdatedListeners) {
            if (mAccountsUpdatedListeners.remove(listener) == null) {
                throw new IllegalStateException("this listener was not previously added");
            }
            if (mAccountsUpdatedListeners.isEmpty()) {
                mContext.unregisterReceiver(mAccountsChangedBroadcastReceiver);
            }
        }
    }
}
