/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.accounts;

import android.accounts.AuthenticatorDescription;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.os.Handler;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * An interface to the Authenticator specialization of RegisteredServicesCache. The use of
 * this interface by the AccountManagerService makes it easier to unit test it.
 * @hide
 */
public interface IAccountAuthenticatorCache {
    /**
     * Accessor for the {@link android.content.pm.RegisteredServicesCache.ServiceInfo} that
     * matched the specified {@link android.accounts.AuthenticatorDescription} or null
     * if none match.
     * @param type the authenticator type to return
     * @return the {@link android.content.pm.RegisteredServicesCache.ServiceInfo} that
     * matches the account type or null if none is present
     */
    RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> getServiceInfo(
            AuthenticatorDescription type, int userId);

    /**
     * @return A copy of a Collection of all the current Authenticators.
     */
    Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> getAllServices(
            int userId);

    /**
     * Dumps the state of the cache. See
     * {@link android.os.Binder#dump(java.io.FileDescriptor, java.io.PrintWriter, String[])}
     */
    void dump(FileDescriptor fd, PrintWriter fout, String[] args, int userId);

    /**
     * Sets a listener that will be notified whenever the authenticator set changes
     * @param listener the listener to notify, or null
     * @param handler the {@link Handler} on which the notification will be posted. If null
     * the notification will be posted on the main thread.
     */
    void setListener(RegisteredServicesCacheListener<AuthenticatorDescription> listener,
            Handler handler);

    void invalidateCache(int userId);

    /**
     * Request to update services info for which package has been updated, but hasn't been
     * picked up by the cache.
     */
    void updateServices(int userId);

    boolean getBindInstantServiceAllowed(int userId);
    void setBindInstantServiceAllowed(int userId, boolean allowed);
}
