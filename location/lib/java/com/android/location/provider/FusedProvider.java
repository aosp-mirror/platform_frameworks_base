/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.location.provider;

import android.os.IBinder;

/**
 * Base class for Fused providers implemented as unbundled services.
 *
 * <p>Fused providers can be implemented as services and return the result of
 * {@link com.android.location.provider.FusedProvider#getBinder()} in its getBinder() method.
 *
 * @deprecated This class should no longer be used. The location service does not uses this.
 * This class exist here just to prevent existing apps having reference to this class from
 * breaking.
 */
@Deprecated
public abstract class FusedProvider {
    /**
     * Gets the Binder associated with the provider.
     * This is intended to be used for the onBind() method of a service that implements a fused
     * service.
     *
     * @return The IBinder instance associated with the provider.
     */
    public IBinder getBinder() {
        return null;
    }
}
