package com.android.hotspot2.flow;

import android.annotation.Nullable;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import com.android.hotspot2.osu.OSUFlowManager;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.osu.OSUOperationStatus;
import com.android.hotspot2.pps.HomeSP;

import java.io.IOException;

/**
 * This is the Hotspot 2.0 release 2 service that handles actual provisioning and remediation flows.
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
public class FlowService extends IntentService {
    private static final String[] INTENTS = {
            WifiManager.NETWORK_STATE_CHANGED_ACTION
    };

    private OSUFlowManager mOSUFlowManager;
    private PlatformAdapter mPlatformAdapter;
    private final FlowServiceImpl mOSUAccessor = new FlowServiceImpl();

    /*
    public FlowService(Context context) {
        super("FlowService");
        mOSUFlowManager = new OSUFlowManager();
        mPlatformAdapter = new PlatformAdapter(context);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleIntent(intent.getAction(), intent);
            }
        };
        for (String intentString : INTENTS) {
            context.registerReceiver(receiver, new IntentFilter(intentString));
        }
    }
    */

    public FlowService() {
        super("FlowService");
    }

    public final class FlowServiceImpl extends IFlowService.Stub {
        public void provision(OSUInfo osuInfo) {
            FlowService.this.provision(osuInfo);
        }

        public void remediate(String spFqdn, String url, boolean policy, Network network) {
            FlowService.this.remediate(spFqdn, url, policy, network);
        }

        public void spDeleted(String fqdn) {
            FlowService.this.serviceProviderDeleted(fqdn);
        }
    }

    public void provision(OSUInfo osuInfo) {
        try {
            mOSUFlowManager.appendFlow(new OSUFlowManager.OSUFlow(osuInfo, mPlatformAdapter,
                    mPlatformAdapter.getKeyManager(null)));
        } catch (IOException ioe) {
            mPlatformAdapter.notifyUser(OSUOperationStatus.ProvisioningFailure, ioe.getMessage(),
                    osuInfo.getName(PlatformAdapter.LOCALE));
        }
    }

    /**
     * Initiate remediation
     * @param spFqdn The FQDN of the current SP, not set for WNM based remediation
     * @param url The URL of the remediation server
     * @param policy Set if this is a policy update rather than a subscription update
     * @param network The network to use for remediation
     */
    public void remediate(String spFqdn, String url, boolean policy, Network network) {
        Log.d(OSUManager.TAG, "Starting remediation for " + spFqdn + " to " + url);
        if (spFqdn != null) {
            HomeSP homeSP = mPlatformAdapter.getHomeSP(spFqdn);
            if (homeSP == null) {
                Log.e(OSUManager.TAG, "No HomeSP object matches '" + spFqdn + "'");
                return;
            }

            try {
                mOSUFlowManager.appendFlow(new OSUFlowManager.OSUFlow(network, url,
                        mPlatformAdapter, mPlatformAdapter.getKeyManager(homeSP),
                        homeSP, policy
                        ? OSUFlowManager.FlowType.Policy : OSUFlowManager.FlowType.Remediation));
            } catch (IOException ioe) {
                Log.e(OSUManager.TAG, "Failed to remediate: " + ioe, ioe);
            }
        } else {
            HomeSP homeSP = mPlatformAdapter.getCurrentSP();
            if (homeSP == null) {
                Log.e(OSUManager.TAG, "Remediation request on unidentified Passpoint network ");
                return;
            }

            try {
                mOSUFlowManager.appendFlow(new OSUFlowManager.OSUFlow(network, url,
                        mPlatformAdapter, mPlatformAdapter.getKeyManager(homeSP), homeSP,
                        OSUFlowManager.FlowType.Remediation));
            } catch (IOException ioe) {
                Log.e(OSUManager.TAG, "Failed to start remediation: " + ioe, ioe);
            }
        }
    }

    public void serviceProviderDeleted(String fqdn) {
        mPlatformAdapter.serviceProviderDeleted(fqdn);
    }

    @Override
    public IBinder onBind(Intent intent) {
        mOSUFlowManager = new OSUFlowManager(this);
        mPlatformAdapter = new PlatformAdapter(this);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleIntent(intent.getAction(), intent);
            }
        };
        for (String intentString : INTENTS) {
            registerReceiver(receiver, new IntentFilter(intentString));
        }
        return mOSUAccessor;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
    }

    private void handleIntent(String action, Intent intent) {
        if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            WifiInfo wifiInfo = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
            if (wifiInfo != null) {
                mOSUFlowManager.networkChange();
            }
        }
    }
}
