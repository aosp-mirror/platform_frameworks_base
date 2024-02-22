/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.test.ravenwood;

import android.content.Context;
import android.hardware.ISerialManager;
import android.hardware.SerialManager;
import android.os.PermissionEnforcer;
import android.os.ServiceManager;
import android.test.mock.MockContext;
import android.util.ArrayMap;
import android.util.Singleton;

import java.util.function.Supplier;

public class RavenwoodContext extends MockContext {
    private final RavenwoodPermissionEnforcer mEnforcer = new RavenwoodPermissionEnforcer();

    private final ArrayMap<Class<?>, String> mClassToName = new ArrayMap<>();
    private final ArrayMap<String, Supplier<?>> mNameToFactory = new ArrayMap<>();

    private void registerService(Class<?> serviceClass, String serviceName,
            Supplier<?> serviceSupplier) {
        mClassToName.put(serviceClass, serviceName);
        mNameToFactory.put(serviceName, serviceSupplier);
    }

    public RavenwoodContext() {
        registerService(PermissionEnforcer.class,
                Context.PERMISSION_ENFORCER_SERVICE, () -> mEnforcer);
        registerService(SerialManager.class,
                Context.SERIAL_SERVICE, asSingleton(() ->
                        new SerialManager(this, ISerialManager.Stub.asInterface(
                                ServiceManager.getService(Context.SERIAL_SERVICE)))
                ));
    }

    @Override
    public Object getSystemService(String serviceName) {
        // TODO: pivot to using SystemServiceRegistry
        final Supplier<?> serviceSupplier = mNameToFactory.get(serviceName);
        if (serviceSupplier != null) {
            return serviceSupplier.get();
        } else {
            throw new UnsupportedOperationException(
                    "Service " + serviceName + " not yet supported under Ravenwood");
        }
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        // TODO: pivot to using SystemServiceRegistry
        final String serviceName = mClassToName.get(serviceClass);
        if (serviceName != null) {
            return serviceName;
        } else {
            throw new UnsupportedOperationException(
                    "Service " + serviceClass + " not yet supported under Ravenwood");
        }
    }

    /**
     * Wrap the given {@link Supplier} to become a memoized singleton.
     */
    private static <T> Supplier<T> asSingleton(Supplier<T> supplier) {
        final Singleton<T> singleton = new Singleton<>() {
            @Override
            protected T create() {
                return supplier.get();
            }
        };
        return () -> {
            return singleton.get();
        };
    }
}
