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

/** @hide */
public interface IHwBinder {
    // These MUST match their corresponding libhwbinder/IBinder.h definition !!!
    public static final int FIRST_CALL_TRANSACTION = 1;
    public static final int FLAG_ONEWAY = 1;

    public void transact(
            int code, HwParcel request, HwParcel reply, int flags)
        throws RemoteException;

    public IHwInterface queryLocalInterface(String descriptor);

    /**
     * Interface for receiving a callback when the process hosting a service
     * has gone away.
     */
    public interface DeathRecipient {
        public void serviceDied(long cookie);
    }

    public boolean linkToDeath(DeathRecipient recipient, long cookie);
    public boolean unlinkToDeath(DeathRecipient recipient);
}
