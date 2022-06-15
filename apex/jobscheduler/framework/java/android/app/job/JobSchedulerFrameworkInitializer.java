/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.job;

import android.annotation.SystemApi;
import android.app.JobSchedulerImpl;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.os.DeviceIdleManager;
import android.os.IDeviceIdleController;
import android.os.PowerExemptionManager;
import android.os.PowerWhitelistManager;

/**
 * Class holding initialization code for the job scheduler module.
 *
 * @hide
 */
@SystemApi
public class JobSchedulerFrameworkInitializer {
    private JobSchedulerFrameworkInitializer() {
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers
     * {@link JobScheduler} and other services to {@link Context}, so
     * {@link Context#getSystemService} can return them.
     *
     * <p>If this is called from other places, it throws a {@link IllegalStateException).
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerStaticService(
                Context.JOB_SCHEDULER_SERVICE, JobScheduler.class,
                (b) -> new JobSchedulerImpl(IJobScheduler.Stub.asInterface(b)));
        SystemServiceRegistry.registerContextAwareService(
                Context.DEVICE_IDLE_CONTROLLER, DeviceIdleManager.class,
                (context, b) -> new DeviceIdleManager(
                        context, IDeviceIdleController.Stub.asInterface(b)));
        SystemServiceRegistry.registerContextAwareService(
                Context.POWER_WHITELIST_MANAGER, PowerWhitelistManager.class,
                PowerWhitelistManager::new);
        SystemServiceRegistry.registerContextAwareService(
                Context.POWER_EXEMPTION_SERVICE, PowerExemptionManager.class,
                PowerExemptionManager::new);
    }
}
