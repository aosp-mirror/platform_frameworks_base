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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.android.printservice.recommendation.util.Preconditions;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/**
 * Wrapper for a {@link PrintServicePlugin}, isolating issues with the plugin as good as possible
 * from the {@link RecommendationServiceImpl service}.
 */
class RemotePrintServicePlugin implements PrintServicePlugin.PrinterDiscoveryCallback {
    /** Lock for this object */
    private final Object mLock = new Object();

    /** The name of the print service. */
    public final @StringRes int name;

    /** If the print service if for more than a single vendor */
    public final boolean recommendsMultiVendorService;

    /** The package name of the full print service */
    public final @NonNull CharSequence packageName;

    /** Wrapped plugin */
    private final @NonNull PrintServicePlugin mPlugin;

    /** The printers discovered by the plugin */
    private @NonNull List<InetAddress> mPrinters;

    /** If the plugin is started by not yet stopped */
    private boolean isRunning;

    /** Listener for changes to {@link #mPrinters}. */
    private @NonNull OnChangedListener mListener;

    /**
     * Create a new remote for a {@link PrintServicePlugin plugin}.
     *
     * @param plugin                       The plugin to be wrapped
     * @param listener                     The listener to be notified about changes in this plugin
     * @param recommendsMultiVendorService If the plugin detects printers of more than a single
     *                                     vendor
     *
     * @throws PluginException If the plugin has issues while caching basic stub properties
     */
    public RemotePrintServicePlugin(@NonNull PrintServicePlugin plugin,
            @NonNull OnChangedListener listener, boolean recommendsMultiVendorService)
            throws PluginException {
        mListener = listener;
        mPlugin = plugin;
        mPrinters = Collections.emptyList();

        this.recommendsMultiVendorService = recommendsMultiVendorService;

        // We handle any throwable to isolate our self from bugs in the plugin code.
        // Cache simple properties to avoid having to deal with exceptions later in the code.
        try {
            name = Preconditions.checkArgumentPositive(mPlugin.getName(), "name");
            packageName = Preconditions.checkStringNotEmpty(mPlugin.getPackageName(),
                    "packageName");
        } catch (Throwable e) {
            throw new PluginException(mPlugin, "Cannot cache simple properties ", e);
        }

        isRunning = false;
    }

    /**
     * Start the plugin. From now on there might be callbacks to the registered listener.
     */
    public void start()
            throws PluginException {
        // We handle any throwable to isolate our self from bugs in the stub code
        try {
            synchronized (mLock) {
                isRunning = true;
                mPlugin.start(this);
            }
        } catch (Throwable e) {
            throw new PluginException(mPlugin, "Cannot start", e);
        }
    }

    /**
     * Stop the plugin. From this call on there will not be any more callbacks.
     */
    public void stop() throws PluginException {
        // We handle any throwable to isolate our self from bugs in the stub code
        try {
            synchronized (mLock) {
                mPlugin.stop();
                isRunning = false;
            }
        } catch (Throwable e) {
            throw new PluginException(mPlugin, "Cannot stop", e);
        }
    }

    /**
     * Get the current number of printers reported by the stub.
     *
     * @return The number of printers reported by the stub.
     */
    public @NonNull List<InetAddress> getPrinters() {
        return mPrinters;
    }

    @Override
    public void onChanged(@Nullable List<InetAddress> discoveredPrinters) {
        synchronized (mLock) {
            Preconditions.checkState(isRunning);

            if (discoveredPrinters == null) {
                mPrinters = Collections.emptyList();
            } else {
                mPrinters = Preconditions.checkCollectionElementsNotNull(discoveredPrinters,
                        "discoveredPrinters");
            }

            mListener.onChanged();
        }
    }

    /**
     * Listener to listen for changes to {@link #getPrinters}
     */
    public interface OnChangedListener {
        void onChanged();
    }

    /**
     * Exception thrown if the stub has any issues.
     */
    public class PluginException extends Exception {
        private PluginException(PrintServicePlugin plugin, String message, Throwable e) {
            super(plugin + ": " + message, e);
        }
    }
}
