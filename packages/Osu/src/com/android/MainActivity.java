package com.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.anqp.OSUProvider;
import com.android.hotspot2.AppBridge;
import com.android.hotspot2.PasspointMatch;
import com.android.hotspot2.osu.OSUInfo;
import com.android.hotspot2.osu.OSUManager;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

//import com.android.Osu.R;

/**
 * Main activity.
 */
public class MainActivity extends Activity {
    private static final int NOTIFICATION_ID = 0; // Used for OSU count
    private static final int NOTIFICATION_MESSAGE_ID = 1; // Used for other messages
    private static final Locale LOCALE = java.util.Locale.getDefault();

    private static volatile OSUService sOsuService;

    private ListView osuListView;
    private OsuListAdapter2 osuListAdapter;
    private String message;

    public MainActivity() {

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (message != null) {
            showDialog(message);
            message = null;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();

        if (bundle == null) {   // User interaction
            if (sOsuService == null) {
                Intent serviceIntent = new Intent(this, OSUService.class);
                serviceIntent.putExtra(ACTION_KEY, "dummy-key");
                startService(serviceIntent);
                return;
            }

            List<OSUInfo> osuInfos = sOsuService.getOsuInfos();

            setContentView(R.layout.activity_main);
            Log.d("osu", "osu count:" + osuInfos.size());
            View noOsuView = findViewById(R.id.no_osu);
            if (osuInfos.size() > 0) {
                noOsuView.setVisibility(View.GONE);
                osuListAdapter = new OsuListAdapter2(this, osuInfos);
                osuListView = (ListView) findViewById(R.id.profile_list);
                osuListView.setAdapter(osuListAdapter);
                osuListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                        OSUInfo osuData = (OSUInfo) adapterView.getAdapter().getItem(position);
                        Log.d("osu", "launch osu:" + osuData.getName(LOCALE)
                                + " id:" + osuData.getOsuID());
                        sOsuService.selectOsu(osuData.getOsuID());
                        finish();
                    }
                });
            } else {
                noOsuView.setVisibility(View.VISIBLE);
            }
        } else if (intent.getAction().equals(AppBridge.ACTION_OSU_NOTIFICATION)) {
            if (bundle.containsKey(AppBridge.OSU_COUNT)) {
                showOsuCount(bundle.getInt("osu-count", 0), Collections.<OSUInfo>emptyList());
            } else if (bundle.containsKey(AppBridge.PROV_SUCCESS)) {
                showStatus(bundle.getBoolean(AppBridge.PROV_SUCCESS),
                        bundle.getString(AppBridge.SP_NAME),
                        bundle.getString(AppBridge.PROV_MESSAGE),
                        null);
            } else if (bundle.containsKey(AppBridge.DEAUTH)) {
                showDeauth(bundle.getString(AppBridge.SP_NAME),
                        bundle.getBoolean(AppBridge.DEAUTH),
                        bundle.getInt(AppBridge.DEAUTH_DELAY),
                        bundle.getString(AppBridge.DEAUTH_URL));
            }
            /*
            else if (bundle.containsKey(AppBridge.OSU_INFO)) {
                List<OsuData> osus = printOsuDataList(bundle.getParcelableArray(AppBridge.OSU_INFO));
                showOsuList(osus);
            }
            */
        }
    }

    private void showOsuCount(int osuCount, List<OSUInfo> osus) {
        if (osuCount > 0) {
            printOsuDataList(osus);
            sendNotification(osuCount);
        } else {
            cancelNotification();
        }
        finish();
    }

    private void showStatus(boolean provSuccess, String spName, String provMessage,
                            String remoteStatus) {
        if (provSuccess) {
            sendDialogMessage(
                    String.format("Credentials for %s was successfully installed", spName));
        } else {
            if (spName != null) {
                if (remoteStatus != null) {
                    sendDialogMessage(
                            String.format("Failed to install credentials for %s: %s: %s",
                                    spName, provMessage, remoteStatus));
                } else {
                    sendDialogMessage(
                            String.format("Failed to install credentials for %s: %s",
                                    spName, provMessage));
                }
            } else {
                sendDialogMessage(
                        String.format("Failed to contact OSU: %s", provMessage));
            }
        }
    }

    private void showDeauth(String spName, boolean ess, int delay, String url) {
        String delayReadable = getReadableTimeInSeconds(delay);
        if (ess) {
            if (delay > 60) {
                sendDialogMessage(
                        String.format("There is an issue connecting to %s [for the next %s]. " +
                                "Please visit %s for details", spName, delayReadable, url));
            } else {
                sendDialogMessage(
                        String.format("There is an issue connecting to %s. " +
                                "Please visit %s for details", spName, url));
            }
        } else {
            sendDialogMessage(
                    String.format("There is an issue with the closest Access Point for %s. " +
                                    "You may wait %s or move to another Access Point to " +
                                    "regain access. Please visit %s for details.",
                            spName, delayReadable, url));
        }
    }

    private static final String ACTION_KEY = "action";

    public static class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            Log.d(OSUManager.TAG, "OSU App got intent: " + intent.getAction());
            Intent serviceIntent;
            serviceIntent = new Intent(c, OSUService.class);
            serviceIntent.putExtra(ACTION_KEY, intent.getAction());
            serviceIntent.putExtras(intent);
            c.startService(serviceIntent);
        }
    }

    public static class OSUService extends IntentService {
        private OSUManager mOsuManager;
        private final IBinder mBinder = new Binder();

        public OSUService() {
            super("OSUService");
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            onHandleIntent(intent);
            return START_STICKY;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d("YYY", String.format("Service %x running, OSU %x",
                    System.identityHashCode(this), System.identityHashCode(mOsuManager)));
            if (mOsuManager == null) {
                mOsuManager = new OSUManager(this);
            }
            sOsuService = this;
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            Log.d("YYY", String.format("Service %x killed", System.identityHashCode(this)));
        }

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            Bundle bundle = intent.getExtras();
            WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            Log.d(OSUManager.TAG, "OSU Service got intent: " + intent.getStringExtra(ACTION_KEY));
            switch (intent.getStringExtra(ACTION_KEY)) {
                case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
                    mOsuManager.pushScanResults(wifiManager.getScanResults());
                    break;
                case WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION:
                    long bssid = bundle.getLong(WifiManager.EXTRA_PASSPOINT_WNM_BSSID);
                    String url = bundle.getString(WifiManager.EXTRA_PASSPOINT_WNM_URL);

                    try {
                        if (bundle.containsKey(WifiManager.EXTRA_PASSPOINT_WNM_METHOD)) {
                            int method = bundle.getInt(WifiManager.EXTRA_PASSPOINT_WNM_METHOD);
                            if (method != OSUProvider.OSUMethod.SoapXml.ordinal()) {
                                Log.w(OSUManager.TAG, "Unsupported remediation method: " + method);
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
                    } catch (IOException | SAXException e) {
                        Log.w(OSUManager.TAG, "Remediation event failed to parse: " + e);
                    }
                    break;
                case WifiManager.PASSPOINT_ICON_RECEIVED_ACTION:
                    mOsuManager.notifyIconReceived(
                            bundle.getLong(WifiManager.EXTRA_PASSPOINT_ICON_BSSID),
                            bundle.getString(WifiManager.EXTRA_PASSPOINT_ICON_FILE),
                            bundle.getByteArray(WifiManager.EXTRA_PASSPOINT_ICON_DATA));
                    break;
                case WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION:
                    mOsuManager.networkConfigChange((WifiConfiguration)
                            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_CONFIGURATION));
                    break;
                case WifiManager.WIFI_STATE_CHANGED_ACTION:
                    int state = bundle.getInt(WifiManager.EXTRA_WIFI_STATE);
                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        mOsuManager.wifiStateChange(false);
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        mOsuManager.wifiStateChange(true);
                    }
                    break;
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    mOsuManager.networkConnectEvent((WifiInfo)
                            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO));
                    break;
            }
        }

        public List<OSUInfo> getOsuInfos() {
            return mOsuManager.getAvailableOSUs();
        }

        public void selectOsu(int id) {
            mOsuManager.setOSUSelection(id);
        }
    }

    private String getReadableTimeInSeconds(int timeSeconds) {
        long hours = TimeUnit.SECONDS.toHours(timeSeconds);
        long minutes = TimeUnit.SECONDS.toMinutes(timeSeconds) - TimeUnit.HOURS.toMinutes(hours);
        long seconds =
                timeSeconds - TimeUnit.HOURS.toSeconds(hours) - TimeUnit.MINUTES.toSeconds(minutes);
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private void sendNotification(int count) {
        Notification.Builder builder =
                new Notification.Builder(this)
                        .setContentTitle(String.format("%s OSU available", count))
                        .setContentText("Choose one to connect")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setAutoCancel(false);
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setContentIntent(resultPendingIntent);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void cancelNotification() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void sendDialogMessage(String message) {
//        sendNotificationMessage(message);
        this.message = message;
    }

    private void showDialog(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
                .setTitle("OSU");
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                dialogInterface.cancel();
                finish();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void sendNotificationMessage(String title) {
        Notification.Builder builder =
                new Notification.Builder(this)
                        .setContentTitle(title)
                        .setContentText("Click to dismiss.")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setAutoCancel(true);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_MESSAGE_ID, builder.build());
    }

    private static class OsuListAdapter2 extends ArrayAdapter<OSUInfo> {
        private Activity activity;

        public OsuListAdapter2(Activity activity, List<OSUInfo> osuDataList) {
            super(activity, R.layout.list_item, osuDataList);
            this.activity = activity;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.list_item, parent, false);
            }
            OSUInfo osuData = getItem(position);
            TextView osuName = (TextView) view.findViewById(R.id.profile_name);
            osuName.setText(osuData.getName(LOCALE));
            TextView osuDetail = (TextView) view.findViewById(R.id.profile_detail);
            osuDetail.setText(osuData.getServiceDescription(LOCALE));
            ImageView osuIcon = (ImageView) view.findViewById(R.id.profile_logo);
            byte[] iconData = osuData.getIconFileElement().getIconData();
            osuIcon.setImageDrawable(
                    new BitmapDrawable(activity.getResources(),
                            BitmapFactory.decodeByteArray(iconData, 0, iconData.length)));
            return view;
        }
    }

    private void printOsuDataList(List<OSUInfo> osuDataList) {
        for (OSUInfo osuData : osuDataList) {
            Log.d("osu", String.format("OSUData:[%s][%s][%d]",
                    osuData.getName(LOCALE), osuData.getServiceDescription(LOCALE),
                    osuData.getOsuID()));
        }
    }

}
