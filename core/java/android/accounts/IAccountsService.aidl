/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * Central application service that allows querying the list of accounts.
 */
interface IAccountsService {
    /**
     * Gets the list of Accounts the user has previously logged
     * in to.  Accounts are of the form "username@domain".
     * <p>
     * This method will return an empty array if the device doesn't
     * know about any accounts (yet).
     *
     * @return The accounts.  The array will be zero-length if the
     *         AccountsService doesn't know about any accounts yet.
     */
    String[] getAccounts();

    /**
     * This is an interim solution for bypassing a forgotten gesture on the
     * unlock screen (it is hidden, please make sure it stays this way!). This
     * will be *removed* when the unlock screen design supports additional
     * authenticators.
     * <p>
     * The user will be presented with username and password fields that are
     * called as parameters to this method. If true is returned, the user is
     * able to define a new gesture and get back into the system. If false, the
     * user can try again.
     * 
     * @param username The username entered.
     * @param password The password entered.
     * @return Whether to allow the user to bypass the lock screen and define a
     *         new gesture.
     * @hide (The package is already hidden, but just in case someone
     *       unhides that, this should not be revealed.)
     */
    boolean shouldUnlock(String username, String password);
}
