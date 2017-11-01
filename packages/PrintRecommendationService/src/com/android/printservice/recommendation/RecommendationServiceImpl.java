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

import android.content.res.Configuration;
import android.printservice.PrintService;
import android.printservice.recommendation.RecommendationInfo;
import android.printservice.recommendation.RecommendationService;
import android.util.Log;

import com.android.printservice.recommendation.plugin.google.CloudPrintPlugin;
import com.android.printservice.recommendation.plugin.hp.HPRecommendationPlugin;
import com.android.printservice.recommendation.plugin.mdnsFilter.MDNSFilterPlugin;
import com.android.printservice.recommendation.plugin.mdnsFilter.VendorConfig;
import com.android.printservice.recommendation.plugin.mopria.MopriaRecommendationPlugin;
import com.android.printservice.recommendation.plugin.samsung.SamsungRecommendationPlugin;
import com.android.printservice.recommendation.plugin.xerox.XeroxPrintServiceRecommendationPlugin;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that recommends {@link PrintService print services} that might be a good idea to install.
 */
public class RecommendationServiceImpl extends RecommendationService
        implements RemotePrintServicePlugin.OnChangedListener {
    private static final String LOG_TAG = "PrintServiceRecService";

    /** All registered plugins */
    private ArrayList<RemotePrintServicePlugin> mPlugins;

    @Override
    public void onConnected() {
        mPlugins = new ArrayList<>();

        try {
            for (VendorConfig config : VendorConfig.getAllConfigs(this)) {
                try {
                    mPlugins.add(new RemotePrintServicePlugin(new MDNSFilterPlugin(this,
                            config.name, config.packageName, config.mDNSNames), this, false));
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Could not initiate simple MDNS plugin for " +
                            config.packageName, e);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException("Could not parse vendorconfig", e);
        }

        try {
            mPlugins.add(new RemotePrintServicePlugin(new CloudPrintPlugin(this), this,
                    true));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not initiate "
                            + getString(R.string.plugin_vendor_google_cloud_print) + " plugin", e);
        }

        try {
            mPlugins.add(new RemotePrintServicePlugin(new HPRecommendationPlugin(this), this,
                    false));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not initiate " + getString(R.string.plugin_vendor_hp) + " plugin",
                    e);
        }

        try {
            mPlugins.add(new RemotePrintServicePlugin(new MopriaRecommendationPlugin(this), this,
                    true));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not initiate " + getString(R.string.plugin_vendor_morpia) +
                    " plugin", e);
        }

        try {
            mPlugins.add(new RemotePrintServicePlugin(new SamsungRecommendationPlugin(this), this,
                    true));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not initiate " + getString(R.string.plugin_vendor_samsung) +
                    " plugin", e);
        }

        try {
            mPlugins.add(new RemotePrintServicePlugin(
                    new XeroxPrintServiceRecommendationPlugin(this), this, false));
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not initiate " + getString(R.string.plugin_vendor_xerox) +
                    " plugin", e);
        }

        final int numPlugins = mPlugins.size();
        for (int i = 0; i < numPlugins; i++) {
            try {
                mPlugins.get(i).start();
            } catch (RemotePrintServicePlugin.PluginException e) {
                Log.e(LOG_TAG, "Could not start plugin", e);
            }
        }
    }

    @Override
    public void onDisconnected() {
        final int numPlugins = mPlugins.size();
        for (int i = 0; i < numPlugins; i++) {
            try {
                mPlugins.get(i).stop();
            } catch (RemotePrintServicePlugin.PluginException e) {
                Log.e(LOG_TAG, "Could not stop plugin", e);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Need to update plugin names as they might be localized
        onChanged();
    }

    @Override
    public void onChanged() {
        ArrayList<RecommendationInfo> recommendations = new ArrayList<>();

        final int numPlugins = mPlugins.size();
        for (int i = 0; i < numPlugins; i++) {
            RemotePrintServicePlugin plugin = mPlugins.get(i);

            try {
                List<InetAddress> printers = plugin.getPrinters();

                if (!printers.isEmpty()) {
                    recommendations.add(new RecommendationInfo(plugin.packageName,
                            getString(plugin.name), printers, plugin.recommendsMultiVendorService));
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Could not read state of plugin for " + plugin.packageName, e);
            }
        }

        updateRecommendations(recommendations);
    }
}
