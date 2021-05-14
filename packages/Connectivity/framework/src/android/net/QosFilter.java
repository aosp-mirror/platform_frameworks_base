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

package android.net;

import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.net.InetAddress;

/**
 * Provides the related filtering logic to the {@link NetworkAgent} to match {@link QosSession}s
 * to their related {@link QosCallback}.
 *
 * Used by the {@link com.android.server.ConnectivityService} to validate a {@link QosCallback}
 * is still able to receive a {@link QosSession}.
 *
 * @hide
 */
@SystemApi
public abstract class QosFilter {

    /**
     * The constructor is kept hidden from outside this package to ensure that all derived types
     * are known and properly handled when being passed to and from {@link NetworkAgent}.
     *
     * @hide
     */
    QosFilter() {
    }

    /**
     * The network used with this filter.
     *
     * @return the registered {@link Network}
     */
    @NonNull
    public abstract Network getNetwork();

    /**
     * Validates that conditions have not changed such that no further {@link QosSession}s should
     * be passed back to the {@link QosCallback} associated to this filter.
     *
     * @return the error code when present, otherwise the filter is valid
     *
     * @hide
     */
    @QosCallbackException.ExceptionType
    public abstract int validate();

    /**
     * Determines whether or not the parameters is a match for the filter.
     *
     * @param address the local address
     * @param startPort the start of the port range
     * @param endPort the end of the port range
     * @return whether the parameters match the local address of the filter
     */
    public abstract boolean matchesLocalAddress(@NonNull InetAddress address,
            int startPort, int endPort);

    /**
     * Determines whether or not the parameters is a match for the filter.
     *
     * @param address the remote address
     * @param startPort the start of the port range
     * @param endPort the end of the port range
     * @return whether the parameters match the remote address of the filter
     */
    public abstract boolean matchesRemoteAddress(@NonNull InetAddress address,
            int startPort, int endPort);
}

