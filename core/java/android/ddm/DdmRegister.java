/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.ddm;

import org.apache.harmony.dalvik.ddmc.DdmServer;
import android.util.Log;

/**
 * Just a place to stick handler registrations, instead of scattering
 * them around.
 */
public class DdmRegister {

    private DdmRegister() {}

    /**
     * Register handlers for all known chunk types.
     *
     * If you write a handler, add a registration call here.
     *
     * Note that this is invoked by the application (usually through a
     * static initializer in the main class), not the VM.  It's done this
     * way so that the handlers can use Android classes with native calls
     * that aren't registered until after the VM is initialized (e.g.
     * logging).  It also allows debugging of DDM handler initialization.
     *
     * The chunk dispatcher will pause until we call registrationComplete(),
     * so that we don't have a race that causes us to drop packets before
     * we finish here.
     */
    public static void registerHandlers() {
        if (false)
            Log.v("ddm", "Registering DDM message handlers");
        DdmHandleHello.register();
        DdmHandleThread.register();
        DdmHandleHeap.register();
        DdmHandleNativeHeap.register();
        DdmHandleProfiling.register();
        DdmHandleExit.register();

        DdmServer.registrationComplete();
    }
}

