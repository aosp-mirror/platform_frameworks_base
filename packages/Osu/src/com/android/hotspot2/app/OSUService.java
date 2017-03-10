package com.android.hotspot2.app;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.anqp.OSUProvider;
import com.android.hotspot2.PasspointMatch;
import com.android.hotspot2.osu.OSUManager;

import java.io.IOException;
import java.util.List;

/**
 * This is the Hotspot 2.0 release 2 OSU background service that is continuously running and caches
 * OSU information.
 *
 * The OSU App is made up of two services; FlowService and OSUService.
 *
 * OSUService is a long running light weight service, kept alive throughout the lifetime of the
 * operating system by being bound from the framework (in WifiManager in stage
 * PHASE_THIRD_PARTY_APPS_CAN_START), and is responsible for continuously caching OSU information
 * and notifying the UI when OSUs are available.
 *
 * FlowService is only started on demand from OSUService and is responsible for handling actual
 * provisioning and remediation flows, and requires a fairly significant memory footprint.
 *
 * FlowService is defined to run in its own process through the definition
 *      <service android:name=".flow.FlowService" android:process=":osuflow">
 * in the AndroidManifest.
 * This is done as a means to keep total app memory footprint low (pss < 10M) and only start the
 * FlowService on demand and make it available for "garbage collection" by the OS when not in use.
 */
public class OSUService extends IntentService {
    public static final String REMEDIATION_DONE_ACTION = "com.android.hotspot2.REMEDIATION_DONE";
    public static final String REMEDIATION_FQDN_EXTRA = "com.android.hotspot2.REMEDIATION_FQDN";
    public static final String REMEDIATION_POLICY_EXTRA = "com.android.hotspot2.REMEDIATION_POLICY";

    private static final String[] INTENTS = {
            WifiManager.SCAN_RESULTS_AVAILABLE_ACTION,
            // TODO(b/32883320): use updated intent definitions.
            //WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION,
            //WifiManager.PASSPOINT_ICON_RECEIVED_ACTION,
            WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            REMEDIATION_DONE_ACTION
    };

    private OSUManager mOsuManager;
    private final LocalServiceBinder mLocalServiceBinder;

    public OSUService() {
        super("OSUService");
        mLocalServiceBinder = new LocalServiceBinder(this);
    }

    /*
    public final class OSUAccessorImpl extends IOSUAccessor.Stub {
        public List<OSUData> getOsuData() {
            List<OSUInfo> infos = getOsuInfos();
            List<OSUData> data = new ArrayList<>(infos.size());
            for (OSUInfo osuInfo : infos) {
                data.add(new OSUData(osuInfo));
            }
            return data;
        }

        public void selectOsu(int id) {
            OSUService.this.selectOsu(id);
        }
    }
    */

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        onHandleIntent(intent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleIntent(intent.getAction(), intent);
            }
        };
        for (String intentString : INTENTS) {
            registerReceiver(receiver, new IntentFilter(intentString));
        }
        return mLocalServiceBinder;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            Log.d(OSUManager.TAG, "Null intent!");
            return;
        }
        //handleIntent(intent.getStringExtra(MainActivity.ACTION_KEY), intent);
    }

    private void handleIntent(String action, Intent intent) {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        Bundle bundle = intent.getExtras();
        if (mOsuManager == null) {
            mOsuManager = new OSUManager(this);
        }
        Log.d(OSUManager.TAG, "Got intent " + intent.getAction());

        switch (action) {
            case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
                mOsuManager.pushScanResults(wifiManager.getScanResults());
                break;
            // TODO(b/32883320): use updated intent definitions.
            /*
            case WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION:
                long bssid = bundle.getLong(WifiManager.EXTRA_PASSPOINT_WNM_BSSID);
                String url = bundle.getString(WifiManager.EXTRA_PASSPOINT_WNM_URL);

                try {
                    if (bundle.containsKey(WifiManager.EXTRA_PASSPOINT_WNM_METHOD)) {
                        int method = bundle.getInt(WifiManager.EXTRA_PASSPOINT_WNM_METHOD);
                        if (method != OSUProvider.OSUMethod.SoapXml.ordinal()) {
                            Log.w(OSUManager.TAG, "Unsupported remediation method: " + method);
                            return;
                        }
                        PasspointMatch match = null;
                        if (bundle.containsKey(WifiManager.EXTRA_PASSPOINT_WNM_PPOINT_MATCH)) {
                            int ordinal =
                                    bundle.getInt(WifiManager.EXTRA_PASSPOINT_WNM_PPOINT_MATCH);
                            if (ordinal >= 0 && ordinal < PasspointMatch.values().length) {
                                match = PasspointMatch.values()[ordinal];
                            }
                        }
                        mOsuManager.wnmRemediate(bssid, url, match);
                    } else if (bundle.containsKey(WifiManager.EXTRA_PASSPOINT_WNM_ESS)) {
                        boolean ess = bundle.getBoolean(WifiManager.EXTRA_PASSPOINT_WNM_ESS);
                        int delay = bundle.getInt(WifiManager.EXTRA_PASSPOINT_WNM_DELAY);
                        mOsuManager.deauth(bssid, ess, delay, url);
                    } else {
                        Log.w(OSUManager.TAG, "Unknown WNM event");
                    }
                } catch (IOException e) {
                    Log.w(OSUManager.TAG, "Remediation event failed to parse: " + e);
                }
                break;
            case WifiManager.PASSPOINT_ICON_RECEIVED_ACTION:
                mOsuManager.notifyIconReceived(
                        bundle.getLong(WifiManager.EXTRA_PASSPOINT_ICON_BSSID),
                        bundle.getString(WifiManager.EXTRA_PASSPOINT_ICON_FILE),
                        bundle.getByteArray(WifiManager.EXTRA_PASSPOINT_ICON_DATA));
                break;
            */
            case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                mOsuManager.networkConnectChange(
                        (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO));
                break;
            case WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION:
                boolean multiNetwork =
                        bundle.getBoolean(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
                if (multiNetwork) {
                    mOsuManager.networkConfigChanged();
                } else if (bundle.getInt(WifiManager.EXTRA_CHANGE_REASON,
                        WifiManager.CHANGE_REASON_CONFIG_CHANGE)
                        == WifiManager.CHANGE_REASON_REMOVED) {
                    WifiConfiguration configuration =
                            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
                    mOsuManager.networkDeleted(configuration);
                } else {
                    mOsuManager.networkConfigChanged();
                }
                break;
            case WifiManager.WIFI_STATE_CHANGED_ACTION:
                int state = bundle.getInt(WifiManager.EXTRA_WIFI_STATE);
                if (state == WifiManager.WIFI_STATE_DISABLED) {
                    mOsuManager.wifiStateChange(false);
                } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                    mOsuManager.wifiStateChange(true);
                }
                break;
            case REMEDIATION_DONE_ACTION:
                String fqdn = bundle.getString(REMEDIATION_FQDN_EXTRA);
                boolean policy = bundle.getBoolean(REMEDIATION_POLICY_EXTRA);
                mOsuManager.remediationDone(fqdn, policy);
                break;
            }
    }

    public List<OSUData> getOsuData() {
        return mOsuManager.getAvailableOSUs();
    }

    public void selectOsu(int id) {
        mOsuManager.setOSUSelection(id);
    }
}
