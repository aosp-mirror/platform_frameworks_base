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

/**
 * Base class for creating AccountAuthenticators. This implements the IAccountAuthenticator
 * binder interface and also provides helper libraries to simplify the creation of
 * AccountAuthenticators.
 */
public abstract class AbstractAccountAuthenticator {
    private static final String TAG = "AccountAuthenticator";

    class Transport extends IAccountAuthenticator.Stub {
        public void addAccount(IAccountAuthenticatorResponse response, String accountType)
                throws RemoteException {
            AbstractAccountAuthenticator.this.addAccount(new AccountAuthenticatorResponse(response),
                    accountType);
        }

        public void authenticateAccount(IAccountAuthenticatorResponse
                response, String name, String type, String password)
                throws RemoteException {
            AbstractAccountAuthenticator.this.authenticateAccount(
                    new AccountAuthenticatorResponse(response), new Account(name, type), password);
        }

        public void getAuthToken(IAccountAuthenticatorResponse response,
                String name, String type, String authTokenType)
                throws RemoteException {
            AbstractAccountAuthenticator.this.getAuthToken(
                    new AccountAuthenticatorResponse(response),
                    new Account(name, type), authTokenType);
        }

        public void getPasswordStrength(IAccountAuthenticatorResponse response,
                String accountType, String password)
                throws RemoteException {
            AbstractAccountAuthenticator.this.getPasswordStrength(
                    new AccountAuthenticatorResponse(response), accountType, password);
        }

        public void checkUsernameExistence(IAccountAuthenticatorResponse response,
                String accountType, String username)
                throws RemoteException {
            AbstractAccountAuthenticator.this.checkUsernameExistence(
                    new AccountAuthenticatorResponse(response), accountType, username);
        }

        public void updatePassword(IAccountAuthenticatorResponse response, String name, String type)
                throws RemoteException {
            AbstractAccountAuthenticator.this.updatePassword(
                    new AccountAuthenticatorResponse(response), new Account(name, type));
        }

        public void editProperties(IAccountAuthenticatorResponse response, String accountType)
                throws RemoteException {
            AbstractAccountAuthenticator.this.editProperties(
                    new AccountAuthenticatorResponse(response), accountType);
        }
    }

    Transport mTransport = new Transport();

    /**
     * @return the IAccountAuthenticator binder transport object
     */
    public final IAccountAuthenticator getIAccountAuthenticator()
    {
        return mTransport;
    }

    /**
     * prompts the user for account information and adds the result to the IAccountManager
     */
    public abstract void addAccount(AccountAuthenticatorResponse response, String accountType);

    /**
     * prompts the user for the credentials of the account
     */
    public abstract void authenticateAccount(AccountAuthenticatorResponse response,
            Account account, String password);

    /**
     * gets the password by either prompting the user or querying the IAccountManager
     */
    public abstract void getAuthToken(AccountAuthenticatorResponse response,
            Account account, String authTokenType);

    /**
     * does local analysis or uses a service in the cloud
     */
    public abstract void getPasswordStrength(AccountAuthenticatorResponse response,
        String accountType, String password);

    /**
     * checks with the login service in the cloud
     */
    public abstract void checkUsernameExistence(AccountAuthenticatorResponse response,
        String accountType, String username);

    /**
     * prompts the user for a new password and writes it to the IAccountManager
     */
    public abstract void updatePassword(AccountAuthenticatorResponse response, Account account);

    /**
     * launches an activity that lets the user edit and set the properties for an authenticator
     */
    public abstract void editProperties(AccountAuthenticatorResponse response, String accountType);
}
