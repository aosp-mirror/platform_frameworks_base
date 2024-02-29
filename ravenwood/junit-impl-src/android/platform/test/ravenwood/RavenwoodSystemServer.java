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

import android.content.ClipboardManager;
import android.hardware.SerialManager;
import android.os.SystemClock;
import android.ravenwood.example.BlueManager;
import android.ravenwood.example.RedManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.utils.TimingsTraceAndSlog;

import java.util.List;
import java.util.Set;

public class RavenwoodSystemServer {
    /**
     * Set of services that we know how to provide under Ravenwood. We keep this set distinct
     * from {@code com.android.server.SystemServer} to give us the ability to choose either
     * "real" or "fake" implementations based on the commitments of the service owner.
     *
     * Map from {@code FooManager.class} to the {@code com.android.server.SystemService}
     * lifecycle class name used to instantiate and drive that service.
     */
    private static final ArrayMap<Class<?>, String> sKnownServices = new ArrayMap<>();

    static {
        // Services provided by a typical shipping device
        sKnownServices.put(ClipboardManager.class,
                "com.android.server.FakeClipboardService$Lifecycle");
        sKnownServices.put(SerialManager.class,
                "com.android.server.SerialService$Lifecycle");

        // Additional services we provide for testing purposes
        sKnownServices.put(BlueManager.class,
                "com.android.server.example.BlueManagerService$Lifecycle");
        sKnownServices.put(RedManager.class,
                "com.android.server.example.RedManagerService$Lifecycle");
    }

    private static Set<Class<?>> sStartedServices;
    private static TimingsTraceAndSlog sTimings;
    private static SystemServiceManager sServiceManager;

    public static void init(RavenwoodRule rule) {
        // Avoid overhead if no services required
        if (rule.mServicesRequired.isEmpty()) return;

        sStartedServices = new ArraySet<>();
        sTimings = new TimingsTraceAndSlog();
        sServiceManager = new SystemServiceManager(rule.mContext);
        sServiceManager.setStartInfo(false,
                SystemClock.elapsedRealtime(),
                SystemClock.uptimeMillis());
        LocalServices.addService(SystemServiceManager.class, sServiceManager);

        startServices(rule.mServicesRequired);
        sServiceManager.sealStartedServices();

        // TODO: expand to include additional boot phases when relevant
        sServiceManager.startBootPhase(sTimings, SystemService.PHASE_SYSTEM_SERVICES_READY);
        sServiceManager.startBootPhase(sTimings, SystemService.PHASE_BOOT_COMPLETED);
    }

    public static void reset(RavenwoodRule rule) {
        // TODO: consider introducing shutdown boot phases

        LocalServices.removeServiceForTest(SystemServiceManager.class);
        sServiceManager = null;
        sTimings = null;
        sStartedServices = null;
    }

    private static void startServices(List<Class<?>> serviceClasses) {
        for (Class<?> serviceClass : serviceClasses) {
            // Quietly ignore duplicate requests if service already started
            if (sStartedServices.contains(serviceClass)) continue;
            sStartedServices.add(serviceClass);

            final String serviceName = sKnownServices.get(serviceClass);
            if (serviceName == null) {
                throw new RuntimeException("The requested service " + serviceClass
                        + " is not yet supported under the Ravenwood deviceless testing "
                        + "environment; consider requesting support from the API owner or "
                        + "consider using Mockito; more details at go/ravenwood-docs");
            }

            // Start service and then depth-first traversal of any dependencies
            final SystemService instance = sServiceManager.startService(serviceName);
            startServices(instance.getDependencies());
        }
    }
}
