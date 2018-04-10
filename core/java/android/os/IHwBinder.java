/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.annotation.SystemApi;

/** @hide */
@SystemApi
public interface IHwBinder {
    // These MUST match their corresponding libhwbinder/IBinder.h definition !!!
    /** @hide */
    public static final int FIRST_CALL_TRANSACTION = 1;
    /** @hide */
    public static final int FLAG_ONEWAY = 1;

    /**
     * Process a hwbinder transaction.
     *
     * @param code interface specific code for interface.
     * @param request parceled transaction
     * @param reply object to parcel reply into
     * @param flags transaction flags to be chosen by wire protocol
     *
     * @hide
     */
    @SystemApi
    public void transact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    /**
     * Return as IHwInterface instance only if this implements descriptor.
     *
     * @param descriptor for example foo.bar@1.0::IBaz
     * @hide
     */
    @SystemApi
    public IHwInterface queryLocalInterface(String descriptor);

    /**
     * Interface for receiving a callback when the process hosting a service
     * has gone away.
     */
    @SystemApi
    public interface DeathRecipient {
        /**
         * Callback for a registered process dying.
         *
         * @param cookie cookie this death recipient was registered with.
         */
        @SystemApi
        public void serviceDied(long cookie);
    }

    /**
     * Notifies the death recipient with the cookie when the process containing
     * this binder dies.
     *
     * @param recipient callback object to be called on object death.
     * @param cookie value to be given to callback on object death.
     */
    @SystemApi
    public boolean linkToDeath(DeathRecipient recipient, long cookie);
    /**
     * Unregisters the death recipient from this binder.
     *
     * @param recipient callback to no longer recieve death notifications on this binder.
     */
    @SystemApi
    public boolean unlinkToDeath(DeathRecipient recipient);
}
