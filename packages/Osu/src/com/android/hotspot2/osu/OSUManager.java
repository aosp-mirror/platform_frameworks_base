package com.android.hotspot2.osu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.anqp.HSIconFileElement;
import com.android.anqp.OSUProvider;
import com.android.hotspot2.AppBridge;
import com.android.hotspot2.PasspointMatch;
import com.android.hotspot2.Utils;
import com.android.hotspot2.app.OSUData;
import com.android.hotspot2.flow.FlowService;
import com.android.hotspot2.flow.OSUInfo;
import com.android.hotspot2.osu.service.RemediationHandler;
import com.android.hotspot2.flow.IFlowService;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class OSUManager {
    public static final String TAG = "OSUMGR";
    public static final boolean R2_MOCK = true;
    private static final String REMEDIATION_FILE = "remediation.state";

    // Preferred icon parameters
    public static final Locale LOCALE = java.util.Locale.getDefault();

    private final AppBridge mAppBridge;
    private final Context mContext;
    private final IconCache mIconCache;
    private final RemediationHandler mRemediationHandler;
    private final Set<String> mOSUSSIDs = new HashSet<>();
    private final Map<OSUProvider, OSUInfo> mOSUMap = new HashMap<>();
    private final AtomicInteger mOSUSequence = new AtomicInteger();

    private final OSUCache mOSUCache;

    public OSUManager(Context context) {
        mContext = context;
        mAppBridge = new AppBridge(context);
        mIconCache = new IconCache(this);
        File appFolder = context.getFilesDir();
        mRemediationHandler =
                new RemediationHandler(context, new File(appFolder, REMEDIATION_FILE));
        mOSUCache = new OSUCache();
    }

    public Context getContext() {
        return mContext;
    }

    public List<OSUData> getAvailableOSUs() {
        synchronized (mOSUMap) {
            List<OSUData> completeOSUs = new ArrayList<>();
            for (OSUInfo osuInfo : mOSUMap.values()) {
                if (osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                    completeOSUs.add(new OSUData(osuInfo));
                }
            }
            return completeOSUs;
        }
    }

    public void setOSUSelection(int osuID) {
        OSUInfo selection = null;
        for (OSUInfo osuInfo : mOSUMap.values()) {
            if (osuInfo.getOsuID() == osuID &&
                    osuInfo.getIconStatus() == OSUInfo.IconStatus.Available) {
                selection = osuInfo;
                break;
            }
        }

        Log.d(TAG, "Selected OSU ID " + osuID + ": " + selection);

        if (selection == null) {
            return;
        }

        final OSUInfo osu = selection;

        mContext.bindService(new Intent(mContext, FlowService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IFlowService fs = IFlowService.Stub.asInterface(service);
                    fs.provision(osu);
                } catch (RemoteException re) {
                    Log.e(OSUManager.TAG, "Caught re: " + re);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(OSUManager.TAG, "Service disconnect: " + name);
            }
        }, Context.BIND_AUTO_CREATE);
    }

    public void networkDeleted(final WifiConfiguration configuration) {
        if (configuration.FQDN == null) {
            return;
        }

        mRemediationHandler.networkConfigChange();
        mContext.bindService(new Intent(mContext, FlowService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IFlowService fs = IFlowService.Stub.asInterface(service);
                    fs.spDeleted(configuration.FQDN);
                } catch (RemoteException re) {
                    Log.e(OSUManager.TAG, "Caught re: " + re);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        }, Context.BIND_AUTO_CREATE);
    }

    public void networkConnectChange(WifiInfo newNetwork) {
        mRemediationHandler.newConnection(newNetwork);
    }

    public void networkConfigChanged() {
        mRemediationHandler.networkConfigChange();
    }

    public void wifiStateChange(boolean on) {
        if (on) {
            return;
        }

        // Notify the remediation handler that there are no WiFi networks available.
        // Do NOT turn it off though as remediation, per at least this implementation, can take
        // place over cellular. The subject of remediation over cellular (when restriction is
        // "unrestricted") is not addresses by the WFA spec and direct ask to authors gives no
        // distinct answer one way or the other.
        mRemediationHandler.newConnection(null);
        int current = mOSUMap.size();
        mOSUMap.clear();
        mOSUCache.clearAll();
        mIconCache.tick(true);
        if (current > 0) {
            notifyOSUCount();
        }
    }

    public boolean isOSU(String ssid) {
        synchronized (mOSUMap) {
            return mOSUSSIDs.contains(ssid);
        }
    }

    public void pushScanResults(Collection<ScanResult> scanResults) {
        Map<OSUProvider, ScanResult> results = mOSUCache.pushScanResults(scanResults);
        if (results != null) {
            updateOSUInfoCache(results);
        }
        mIconCache.tick(false);
    }

    private void updateOSUInfoCache(Map<OSUProvider, ScanResult> results) {
        Map<OSUProvider, OSUInfo> osus = new HashMap<>();
        for (Map.Entry<OSUProvider, ScanResult> entry : results.entrySet()) {
            OSUInfo existing = mOSUMap.get(entry.getKey());
            long bssid = Utils.parseMac(entry.getValue().BSSID);

            if (existing == null) {
                osus.put(entry.getKey(), new OSUInfo(entry.getValue(), entry.getKey(),
                        mOSUSequence.getAndIncrement()));
            } else if (existing.getBSSID() != bssid) {
                HSIconFileElement icon = mIconCache.getIcon(existing);
                if (icon != null && icon.equals(existing.getIconFileElement())) {
                    OSUInfo osuInfo = new OSUInfo(entry.getValue(), entry.getKey(),
                            existing.getOsuID());
                    osuInfo.setIconFileElement(icon, existing.getIconFileName());
                    osus.put(entry.getKey(), osuInfo);
                } else {
                    osus.put(entry.getKey(), new OSUInfo(entry.getValue(),
                            entry.getKey(), mOSUSequence.getAndIncrement()));
                }
            } else {
                // Maintain existing entries.
                osus.put(entry.getKey(), existing);
            }
        }

        mOSUMap.clear();
        mOSUMap.putAll(osus);

        mOSUSSIDs.clear();
        for (OSUInfo osuInfo : mOSUMap.values()) {
            mOSUSSIDs.add(osuInfo.getOsuSsid());
        }

        int mods = mIconCache.resolveIcons(mOSUMap.values());

        if (mOSUMap.isEmpty() || mods > 0) {
            notifyOSUCount();
        }
    }

    public void notifyIconReceived(long bssid, String fileName, byte[] data) {
        if (mIconCache.notifyIconReceived(bssid, fileName, data) > 0) {
            notifyOSUCount();
        }
    }

    public void doIconQuery(long bssid, String fileName) {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        wifiManager.queryPasspointIcon(bssid, fileName);
    }

    private void notifyOSUCount() {
        int count = 0;
        for (OSUInfo existing : mOSUMap.values()) {
            if (existing.getIconStatus() == OSUInfo.IconStatus.Available) {
                count++;
            }
        }
        Log.d(TAG, "Latest OSU info: " + count + " with icons, map " + mOSUMap);
        mAppBridge.showOsuCount(count);
    }

    public void deauth(long bssid, boolean ess, int delay, String url)
            throws MalformedURLException {
        Log.d(TAG, String.format("De-auth imminent on %s, delay %ss to '%s'",
                ess ? "ess" : "bss", delay, url));
        // TODO: Missing framework functionality:
        // mWifiNetworkAdapter.setHoldoffTime(delay * Constants.MILLIS_IN_A_SEC, ess);
        String spName = mRemediationHandler.getCurrentSpName();
        mAppBridge.showDeauth(spName, ess, delay, url);
    }

    public void wnmRemediate(final long bssid, final String url, PasspointMatch match) {
        mRemediationHandler.wnmReceived(bssid, url);
    }

    public void remediationDone(String fqdn, boolean policy) {
        mRemediationHandler.remediationDone(fqdn, policy);
    }
}
