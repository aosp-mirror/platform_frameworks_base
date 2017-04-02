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

package com.android.printservice.recommendation;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;

import java.net.InetAddress;
import java.util.List;

/**
 * Interface to be implemented by each print service plugin.
 * <p/>
 * A print service plugin is a minimal version of a real {@link android.printservice.PrintService
 * print service}. You cannot print using the plugin. The only functionality in the plugin is to
 * report the number of printers that the real service would discover.
 */
public interface PrintServicePlugin {
    /**
     * Call back used by the print service plugins.
     */
    interface PrinterDiscoveryCallback {
        /**
         * Announce that something changed and the UI for this plugin should be updated.
         *
         * @param discoveredPrinters The printers discovered.
         */
        void onChanged(@Nullable List<InetAddress> discoveredPrinters);
    }

    /**
     * Get the name (a string reference) of the {@link android.printservice.PrintService print
     * service} with the {@link #getPackageName specified package name}. This is read once, hence
     * returning different data at different times is not allowed.
     *
     * @return The name of the print service as a string reference. The localization is handled
     *         outside of the plugin.
     */
    @StringRes int getName();

    /**
     * The package name of the full print service.
     *
     * @return The package name
     */
    @NonNull CharSequence getPackageName();

    /**
     * Start the discovery plugin.
     *
     * @param callback Callbacks used by this plugin.
     *
     * @throws Exception If anything went wrong when starting the plugin
     */
    void start(@NonNull PrinterDiscoveryCallback callback) throws Exception;

    /**
     * Stop the plugin. This can only return once the plugin is completely finished and cleaned up.
     *
     * @throws Exception If anything went wrong while stopping plugin
     */
    void stop() throws Exception;
}
