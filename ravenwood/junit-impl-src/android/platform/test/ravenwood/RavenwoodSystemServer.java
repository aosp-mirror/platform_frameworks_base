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
import android.util.ArrayMap;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.utils.TimingsTraceAndSlog;

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

    // TODO: expand SystemService API to support dependency expression, so we don't need test
    // authors to exhaustively declare all transitive services

    static {
        sKnownServices.put(ClipboardManager.class,
                "com.android.server.FakeClipboardService$Lifecycle");
        sKnownServices.put(SerialManager.class,
                "com.android.server.SerialService$Lifecycle");
    }

    private static TimingsTraceAndSlog sTimings;
    private static SystemServiceManager sServiceManager;

    public static void init(RavenwoodRule rule) {
        // Avoid overhead if no services required
        if (rule.mServicesRequired.isEmpty()) return;

        sTimings = new TimingsTraceAndSlog();
        sServiceManager = new SystemServiceManager(rule.mContext);
        sServiceManager.setStartInfo(false,
                SystemClock.elapsedRealtime(),
                SystemClock.uptimeMillis());
        LocalServices.addService(SystemServiceManager.class, sServiceManager);

        for (Class<?> service : rule.mServicesRequired) {
            final String target = sKnownServices.get(service);
            if (target == null) {
                throw new RuntimeException("The requested service " + service
                        + " is not yet supported under the Ravenwood deviceless testing "
                        + "environment; consider requesting support from the API owner or "
                        + "consider using Mockito; more details at go/ravenwood-docs");
            } else {
                sServiceManager.startService(target);
            }
        }
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
    }
}
