/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.carriermessaging;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.android.internal.util.Preconditions;

/**
 * Provides basic structure for platform to connect to the carrier messaging service.
 * <p>
 * <code>
 * CarrierMessagingServiceManager carrierMessagingServiceManager =
 *     new CarrierMessagingServiceManagerImpl();
 * if (carrierMessagingServiceManager.bindToCarrierMessagingService(context, carrierPackageName)) {
 *   // wait for onServiceReady callback
 * } else {
 *   // Unable to bind: handle error.
 * }
 * </code>
 * <p> Upon completion {@link #disposeConnection} should be called to unbind the
 * CarrierMessagingService.
 * @hide
 */
public abstract class CarrierMessagingServiceManager {
    // Populated by bindToCarrierMessagingService. bindToCarrierMessagingService must complete
    // prior to calling disposeConnection so that mCarrierMessagingServiceConnection is initialized.
    private volatile CarrierMessagingServiceConnection mCarrierMessagingServiceConnection;

    /**
     * Binds to the carrier messaging service under package {@code carrierPackageName}. This method
     * should be called exactly once.
     *
     * @param context the context
     * @param carrierPackageName the carrier package name
     * @return true upon successfully binding to a carrier messaging service, false otherwise
     */
    public boolean bindToCarrierMessagingService(Context context, String carrierPackageName) {
        Preconditions.checkState(mCarrierMessagingServiceConnection == null);

        Intent intent = new Intent(CarrierMessagingService.SERVICE_INTERFACE);
        intent.setPackage(carrierPackageName);
        mCarrierMessagingServiceConnection = new CarrierMessagingServiceConnection();
        return context.bindService(intent, mCarrierMessagingServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds the carrier messaging service. This method should be called exactly once.
     *
     * @param context the context
     */
    public void disposeConnection(Context context) {
        Preconditions.checkNotNull(mCarrierMessagingServiceConnection);
        context.unbindService(mCarrierMessagingServiceConnection);
        mCarrierMessagingServiceConnection = null;
    }

    /**
     * Implemented by subclasses to use the carrier messaging service once it is ready.
     *
     * @param carrierMessagingService the carirer messaing service interface
     */
    protected abstract void onServiceReady(ICarrierMessagingService carrierMessagingService);

    /**
     * A basic {@link ServiceConnection}.
     */
    private final class CarrierMessagingServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            onServiceReady(ICarrierMessagingService.Stub.asInterface(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }
}
