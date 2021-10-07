/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.security;

import android.os.RemoteException;

/**
 * This is a Producer of {@code R} that is expected to throw a {@link RemoteException}.
 *
 * It is used by Keystore2 service wrappers to handle and convert {@link RemoteException}
 * and {@link android.os.ServiceSpecificException} into {@link KeyStoreException}.
 *
 * @hide
 * @param <R>
 */
@FunctionalInterface
interface CheckedRemoteRequest<R> {
    R execute() throws RemoteException;
}
