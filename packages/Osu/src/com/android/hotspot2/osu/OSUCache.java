package com.android.hotspot2.osu;

import android.net.wifi.AnqpInformationElement;
import android.net.wifi.ScanResult;
import android.util.Log;

import com.android.anqp.Constants;
import com.android.anqp.HSOsuProvidersElement;
import com.android.anqp.OSUProvider;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class holds a stable set of OSU information as well as scan results based on a trail of
 * scan results.
 * The purpose of this class is to provide a stable set of information over a a limited span of
 * time (SCAN_BATCH_HISTORY_SIZE scan batches) so that OSU entries in the selection list does not
 * come and go with temporarily lost scan results.
 * The stable set of scan results are used by the remediation flow to retrieve ANQP information
 * for the current network to determine whether the currently associated network is a roaming
 * network for the Home SP whose timer has currently fired.
 */
public class OSUCache {
    private static final int SCAN_BATCH_HISTORY_SIZE = 8;

    private int mInstant;
    private final Map<OSUProvider, ScanResult> mBatchedOSUs = new HashMap<>();
    private final Map<OSUProvider, ScanInstance> mCache = new HashMap<>();

    private static class ScanInstance {
        private final ScanResult mScanResult;
        private int mInstant;

        private ScanInstance(ScanResult scanResult, int instant) {
            mScanResult = scanResult;
            mInstant = instant;
        }

        public ScanResult getScanResult() {
            return mScanResult;
        }

        public int getInstant() {
            return mInstant;
        }

        private boolean bssidEqual(ScanResult scanResult) {
            return mScanResult.BSSID.equals(scanResult.BSSID);
        }

        private void updateInstant(int newInstant) {
            mInstant = newInstant;
        }

        @Override
        public String toString() {
            return mScanResult.SSID + " @ " + mInstant;
        }
    }

    public OSUCache() {
        mInstant = 0;
    }

    private void clear() {
        mBatchedOSUs.clear();
    }

    public void clearAll() {
        clear();
        mCache.clear();
    }

    public Map<OSUProvider, ScanResult> pushScanResults(Collection<ScanResult> scanResults) {
        for (ScanResult scanResult : scanResults) {
            AnqpInformationElement[] osuInfo = scanResult.anqpElements;
            if (osuInfo != null && osuInfo.length > 0) {
                putResult(scanResult, osuInfo);
            }
        }
        return scanEnd();
    }

    private void putResult(ScanResult scanResult, AnqpInformationElement[] elements) {
        for (AnqpInformationElement ie : elements) {
            if (ie.getElementId() == AnqpInformationElement.HS_OSU_PROVIDERS
                    && ie.getVendorId() == AnqpInformationElement.HOTSPOT20_VENDOR_ID) {
                try {
                    HSOsuProvidersElement providers = new HSOsuProvidersElement(
                            Constants.ANQPElementType.HSOSUProviders,
                            ByteBuffer.wrap(ie.getPayload()).order(ByteOrder.LITTLE_ENDIAN));

                    putProviders(scanResult, providers);
                } catch (ProtocolException pe) {
                    Log.w(OSUManager.TAG,
                            "Failed to parse OSU element: " + pe);
                }
            }
        }
    }

    private void putProviders(ScanResult scanResult, HSOsuProvidersElement osuProviders) {
        for (OSUProvider provider : osuProviders.getProviders()) {
            // Make a predictive put
            ScanResult existing = mBatchedOSUs.put(provider, scanResult);
            if (existing != null && existing.level > scanResult.level) {
                // But undo it if the entry already held a better RSSI
                mBatchedOSUs.put(provider, existing);
            }
        }
    }

    private Map<OSUProvider, ScanResult> scanEnd() {
        // Update the trail of OSU Providers:
        int changes = 0;
        Map<OSUProvider, ScanInstance> aged = new HashMap<>(mCache);
        for (Map.Entry<OSUProvider, ScanResult> entry : mBatchedOSUs.entrySet()) {
            ScanInstance current = aged.remove(entry.getKey());
            if (current == null || !current.bssidEqual(entry.getValue())) {
                mCache.put(entry.getKey(), new ScanInstance(entry.getValue(), mInstant));
                changes++;
                if (current == null) {
                    Log.d("ZXZ", "Add OSU " + entry.getKey() + " from " + entry.getValue().SSID);
                } else {
                    Log.d("ZXZ", "Update OSU " + entry.getKey() + " with " +
                            entry.getValue().SSID + " to " + current);
                }
            } else {
                Log.d("ZXZ", "Existing OSU " + entry.getKey() + ", "
                        + current.getInstant() + " -> " + mInstant);
                current.updateInstant(mInstant);
            }
        }

        for (Map.Entry<OSUProvider, ScanInstance> entry : aged.entrySet()) {
            if (mInstant - entry.getValue().getInstant() > SCAN_BATCH_HISTORY_SIZE) {
                Log.d("ZXZ", "Remove OSU " + entry.getKey() + ", "
                        + entry.getValue().getInstant() + " @ " + mInstant);
                mCache.remove(entry.getKey());
                changes++;
            }
        }

        mInstant++;
        clear();

        // Return the latest results if there were any changes from last batch
        if (changes > 0) {
            Map<OSUProvider, ScanResult> results = new HashMap<>(mCache.size());
            for (Map.Entry<OSUProvider, ScanInstance> entry : mCache.entrySet()) {
                results.put(entry.getKey(), entry.getValue().getScanResult());
            }
            return results;
        } else {
            return null;
        }
    }

    private static String toBSSIDStrings(Set<Long> bssids) {
        StringBuilder sb = new StringBuilder();
        for (Long bssid : bssids) {
            sb.append(String.format(" %012x", bssid));
        }
        return sb.toString();
    }
}
