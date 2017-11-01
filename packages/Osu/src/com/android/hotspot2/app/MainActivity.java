package com.android.hotspot2.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
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

import com.android.hotspot2.AppBridge;
import com.android.hotspot2.R;
import com.android.hotspot2.osu.OSUManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main activity.
 */
public class MainActivity extends Activity {
    private static final int NOTIFICATION_ID = 0; // Used for OSU count
    private static final int NOTIFICATION_MESSAGE_ID = 1; // Used for other messages
    private static final String ACTION_SVC_BOUND = "SVC_BOUND";

    private volatile OSUService mLocalService;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LocalServiceBinder binder = (LocalServiceBinder) service;
            mLocalService = binder.getService();
            showOsuSelection(mLocalService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mLocalService = null;
        }
    };

    private ListView osuListView;
    private OsuListAdapter osuListAdapter;
    private String message;

    public MainActivity() {

    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mLocalService != null) {
            unbindService(mConnection);
            mLocalService = null;
        }
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

        final Intent intent = getIntent();
        Bundle bundle = intent.getExtras();

        if (intent.getAction() == null) {
            if (mLocalService == null) {
                bindService(new Intent(this, OSUService.class), mConnection, 0);
            }
        } else if (intent.getAction().equals(AppBridge.ACTION_OSU_NOTIFICATION)) {
            if (bundle == null) {
                Log.d(OSUManager.TAG, "No parameters for OSU notification");
                return;
            }
            if (bundle.containsKey(AppBridge.OSU_COUNT)) {
                showOsuCount(bundle.getInt("osu-count", 0), Collections.<OSUData>emptyList());
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
        }
    }

    private void showOsuSelection(final OSUService osuService) {
        List<OSUData> osuData = osuService.getOsuData();

        setContentView(R.layout.activity_main);
        Log.d("osu", "osu count:" + osuData.size());
        View noOsuView = findViewById(R.id.no_osu);
        if (osuData.size() > 0) {
            noOsuView.setVisibility(View.GONE);
            osuListAdapter = new OsuListAdapter(this, osuData);
            osuListView = findViewById(R.id.profile_list);
            osuListView.setAdapter(osuListAdapter);
            osuListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                    OSUData osuData = (OSUData) adapterView.getAdapter().getItem(position);
                    Log.d("osu", "launch osu:" + osuData.getName()
                            + " id:" + osuData.getId());
                    osuService.selectOsu(osuData.getId());
                    finish();
                }
            });
        } else {
            noOsuView.setVisibility(View.VISIBLE);
        }
    }

    private void showOsuCount(int osuCount, List<OSUData> osus) {
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

    private static class OsuListAdapter extends ArrayAdapter<OSUData> {
        private Activity activity;

        public OsuListAdapter(Activity activity, List<OSUData> osuDataList) {
            super(activity, R.layout.list_item, osuDataList);
            this.activity = activity;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.list_item, parent, false);
            }
            OSUData osuData = getItem(position);
            TextView osuName = (TextView) view.findViewById(R.id.profile_name);
            osuName.setText(osuData.getName());
            TextView osuDetail = (TextView) view.findViewById(R.id.profile_detail);
            osuDetail.setText(osuData.getServiceDescription());
            ImageView osuIcon = (ImageView) view.findViewById(R.id.profile_logo);
            byte[] iconData = osuData.getIconData();
            osuIcon.setImageDrawable(
                    new BitmapDrawable(activity.getResources(),
                            BitmapFactory.decodeByteArray(iconData, 0, iconData.length)));
            return view;
        }
    }

    private void printOsuDataList(List<OSUData> osuDataList) {
        for (OSUData osuData : osuDataList) {
            Log.d("osu", String.format("OSUData:[%s][%s][%d]",
                    osuData.getName(), osuData.getServiceDescription(),
                    osuData.getId()));
        }
    }

}
