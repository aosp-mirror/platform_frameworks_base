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

import android.os.RemoteException;
import android.os.Bundle;
import android.app.PendingIntent;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * A class that helps with interactions with the {@link IAccountManager} interface. It provides
 * methods to allow for account, password, and authtoken management for all accounts on the
 * device. Some of these calls are implemented with the help of the corresponding
 * {@link IAccountAuthenticator} services. One accesses the {@link AccountManager} by calling:
 *    AccountManager accountManager =
 *      (AccountManager)context.getSystemService(Context.ACCOUNT_SERVICE)
 *
 * <p>
 * TODO: this interface is still in flux
 */
public class AccountManager {
    private static final String TAG = "AccountManager";

    private final Context mContext;
    private final IAccountManager mService;

    public AccountManager(Context context, IAccountManager service) {
        mContext = context;
        mService = service;
    }

    public String getPassword(Account account) {
        try {
            return mService.getPassword(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public String getUserData(Account account, String key) {
        try {
            return mService.getUserData(account, key);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public Account[] blockingGetAccounts() {
        try {
            return mService.getAccounts();
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public void getAccounts(final PendingIntent intent, final int code) {
        getAccountsByType(null /* all account types */, intent, code);
    }

    public void getAccountsByType(final String accountType,
            final PendingIntent intent, final int code) {
        Thread t = new Thread() {
            public void run() {
                try {
                    Account[] accounts;
                    if (accountType != null) {
                        accounts = blockingGetAccountsByType(accountType);
                    } else {
                        accounts = blockingGetAccounts();
                    }
                    Intent payload = new Intent();
                    payload.putExtra("accounts", accounts);
                    intent.send(mContext, code, payload);
                } catch (PendingIntent.CanceledException e) {
                    // the pending intent is no longer accepting results, we don't
                    // need to do anything to handle this
                }
            }
        };
        t.start();
    }

    public Account[] blockingGetAccountsByType(String accountType) {
        try {
            return mService.getAccountsByType(accountType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public boolean addAccount(Account account, String password, Bundle extras) {
        try {
            return mService.addAccount(account, password, extras);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public void removeAccount(Account account) {
        try {
            mService.removeAccount(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void invalidateAuthToken(String accountType, String authToken) {
        try {
            mService.invalidateAuthToken(accountType, authToken);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public String peekAuthToken(Account account, String authTokenType) {
        try {
            return mService.peekAuthToken(account, authTokenType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
            throw new RuntimeException(e);
        }
    }

    public void setPassword(Account account, String password) {
        try {
            mService.setPassword(account, password);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void clearPassword(Account account) {
        try {
            mService.clearPassword(account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void setUserData(Account account, String key, String value) {
        try {
            mService.setUserData(account, key, value);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void getAuthToken(AccountManagerResponse response,
            Account account, String authTokenType, boolean notifyAuthFailure) {
        try {
            mService.getAuthToken(response.getIAccountManagerResponse(), account, authTokenType,
                    notifyAuthFailure);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        try {
            mService.setAuthToken(account, authTokenType, authToken);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void addAccountInteractively(AccountManagerResponse response, String accountType) {
        try {
            mService.addAccountInteractively(response.getIAccountManagerResponse(), accountType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public class AuthenticateAccountThread extends Thread {
        public Lock mLock = new ReentrantLock();
        public Condition mCondition = mLock.newCondition();
        volatile boolean mSuccess = false;
        volatile boolean mFailure = false;
        volatile boolean mResult = false;
        private final Account mAccount;
        private final String mPassword;
        public AuthenticateAccountThread(Account account, String password) {
            mAccount = account;
            mPassword = password;
        }
        public void run() {
            try {
                IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
                    public void onStringResult(String value) throws RemoteException {
                    }

                    public void onIntResult(int value) throws RemoteException {
                    }

                    public void onBooleanResult(boolean value) throws RemoteException {
                        mLock.lock();
                        try {
                            if (!mFailure && !mSuccess) {
                                mSuccess = true;
                                mResult = value;
                                mCondition.signalAll();
                            }
                        } finally {
                            mLock.unlock();
                        }
                    }

                    public void onError(int errorCode, String errorMessage) {
                        mLock.lock();
                        try {
                            if (!mFailure && !mSuccess) {
                                mFailure = true;
                                mCondition.signalAll();
                            }
                        } finally {
                            mLock.unlock();
                        }
                    }
                };

                mService.authenticateAccount(response, mAccount, mPassword);
            } catch (RemoteException e) {
                // if this happens the entire runtime will restart
            }
        }
    }

    public boolean authenticateAccount(Account account, String password) {
        AuthenticateAccountThread thread = new AuthenticateAccountThread(account, password);
        thread.mLock.lock();
        thread.start();
        try {
          while (!thread.mSuccess && !thread.mFailure) {
              try {
                  thread.mCondition.await();
              } catch (InterruptedException e) {
                  // keep waiting
              }
          }
          return thread.mResult;
        } finally {
          thread.mLock.unlock();
        }
    }

    public void updatePassword(AccountManagerResponse response, Account account) {
        try {
            mService.updatePassword(response.getIAccountManagerResponse(), account);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void editProperties(AccountManagerResponse response, String accountType) {
        try {
            mService.editProperties(response.getIAccountManagerResponse(), accountType);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void getPasswordStrength(AccountManagerResponse response,
            String accountType, String password) {
        try {
            mService.getPasswordStrength(response.getIAccountManagerResponse(),
                    accountType, password);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }

    public void checkUsernameExistence(AccountManagerResponse response,
            String accountType, String username) {
        try {
            mService.checkUsernameExistence(response.getIAccountManagerResponse(),
                    accountType, username);
        } catch (RemoteException e) {
            // if this happens the entire runtime will restart
        }
    }
}
