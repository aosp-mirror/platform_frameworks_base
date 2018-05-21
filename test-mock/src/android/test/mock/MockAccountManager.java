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
package android.test.mock;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OnAccountsUpdateListener;
import android.accounts.OperationCanceledException;
import android.content.Context;
import android.os.Handler;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A mock {@link android.accounts.AccountManager} class.
 *
 * <p>Provided for use by {@code android.test.IsolatedContext}.
 *
 * @deprecated Use a mocking framework like <a href="https://github.com/mockito/mockito">Mockito</a>.
 * New tests should be written using the
 * <a href="{@docRoot}
 * tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class MockAccountManager {

    /**
     * Create a new mock {@link AccountManager} instance.
     *
     * @param context the {@link Context} to which the returned object belongs.
     * @return the new instance.
     */
    public static AccountManager newMockAccountManager(Context context) {
        return new MockAccountManagerImpl(context);
    }

    private MockAccountManager() {
    }

    private static class MockAccountManagerImpl extends AccountManager {

        MockAccountManagerImpl(Context context) {
            super(context, null /* IAccountManager */, null /* handler */);
        }

        public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
                Handler handler, boolean updateImmediately) {
            // do nothing
        }

        public Account[] getAccounts() {
            return new Account[] {};
        }

        public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(
                final String type, final String[] features,
                AccountManagerCallback<Account[]> callback, Handler handler) {
            return new MockAccountManagerFuture<Account[]>(new Account[0]);
        }

        public String blockingGetAuthToken(Account account, String authTokenType,
                boolean notifyAuthFailure)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return null;
        }
    }

    /**
     * A very simple AccountManagerFuture class
     * that returns what ever was passed in
     */
    private static class MockAccountManagerFuture<T>
            implements AccountManagerFuture<T> {

        T mResult;

        MockAccountManagerFuture(T result) {
            mResult = result;
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        public boolean isCancelled() {
            return false;
        }

        public boolean isDone() {
            return true;
        }

        public T getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            return mResult;
        }

        public T getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return getResult();
        }
    }
}
