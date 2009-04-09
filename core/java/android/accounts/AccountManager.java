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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * A class that helps with interactions with the {@link IAccountManager} interface. It provides
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

    public AccountManager(Context context, IAccountManager service) {
        mContext = context;
        mService = service;
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

    public String[] blockingGetAuthenticatorTypes() {
        ensureNotOnMainThread();
        try {
            return mService.getAuthenticatorTypes();
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Future1<String[]> getAuthenticatorTypes(Future1Callback<String[]> callback,
            Handler handler) {
        return startAsFuture(callback, handler, new Callable<String[]>() {
            public String[] call() throws Exception {
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
        };
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
        };
    }

    public Future2 addAccount(final String accountType,
            final String authTokenType, final Bundle addAccountOptions,
            final Activity activity, Future2Callback callback, Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.addAcount(mResponse, accountType, authTokenType,
                        activity != null, addAccountOptions);
            }
        };
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

    public Future2 confirmCredentials(final Account account, final Activity activity,
            final Future2Callback callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.confirmCredentials(mResponse, account, activity != null);
            }
        };
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
        };
    }

    public Future2 editProperties(final String accountType, final Activity activity,
            final Future2Callback callback,
            final Handler handler) {
        return new AmsTask(activity, handler, callback) {
            public void doWork() throws RemoteException {
                mService.editProperties(mResponse, accountType, activity != null);
            }
        };
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
        if (handler == null) {
            handler = new Handler(mContext.getMainLooper());
        }
        final Handler innerHandler = handler;
        innerHandler.post(new Runnable() {
            public void run() {
                callback.run(future);
            }
        });
    }

    private <V> void postToHandler(Handler handler, final Future1Callback<V> callback,
            final Future1<V> future) {
        if (handler == null) {
            handler = new Handler(mContext.getMainLooper());
        }
        final Handler innerHandler = handler;
        innerHandler.post(new Runnable() {
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

    public abstract class AmsTask extends FutureTask<Bundle> implements Future2 {
        final IAccountManagerResponse mResponse;
        final Handler mHandler;
        final Future2Callback mCallback;
        final Activity mActivity;
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

    public abstract class AMSTaskBoolean extends FutureTask<Boolean> implements Future1<Boolean> {
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
            return new UnsupportedOperationException();
        }

        if (code == Constants.ERROR_CODE_INVALID_RESPONSE) {
            return new AuthenticatorException("invalid response");
        }

        return new AuthenticatorException("unknown error code");
    }
}
