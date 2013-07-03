package com.android.server.connectivity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ProxyProperties;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.net.IProxyService;


import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HttpContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URL;
import java.net.URLConnection;

/**
 * @hide
 */
public class PacManager implements Runnable {
    public static final int NO_ERROR = 0;
    public static final int PERMISSION_DENIED = 1;
    public static final String PROXY_SERVICE = "com.android.net.IProxyService";


    private static final String TAG = "PACManager";

    private static final String ACTION_PAC_REFRESH = "android.net.proxy.PAC_REFRESH";

    private static final String DEFAULT_DELAYS = "8 32 120 14400 43200";
    private static final int DELAY_1 = 0;
    private static final int DELAY_4 = 3;
    private static final int DELAY_LONG = 4;

    /** Keep these values up-to-date with ProxyService.java */
    public static final String KEY_PROXY = "keyProxy";
    private String mCurrentPac;
    private volatile String mPacUrl;

    private AlarmManager mAlarmManager;
    private IProxyService mProxyService;
    private PendingIntent mPacRefreshIntent;
    private Context mContext;

    private int mCurrentDelay;

    class PacRefreshIntentReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            new Thread(PacManager.this).start();
        }
    }

    public PacManager(Context context) {
        mContext = context;
        mProxyService = IProxyService.Stub.asInterface(
                ServiceManager.getService(PROXY_SERVICE));

        mPacRefreshIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_PAC_REFRESH), 0);
        context.registerReceiver(new PacRefreshIntentReceiver(),
                new IntentFilter(ACTION_PAC_REFRESH));
    }

    private AlarmManager getAlarmManager() {
        if (mAlarmManager == null) {
            mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        }
        return mAlarmManager;
    }

    public void setCurrentProxyScriptUrl(ProxyProperties proxy) {
        if (!TextUtils.isEmpty(proxy.getPacFileUrl())) {
            try {
                mProxyService.startPacSystem();
                mPacUrl = proxy.getPacFileUrl();
                mCurrentDelay = DELAY_1;
                getAlarmManager().cancel(mPacRefreshIntent);
                new Thread(this).start();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to reach ProxyService - PAC will not be started", e);
            }
        } else {
            try {
                mProxyService.stopPacSystem();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Does a post and reports back the status code.
     *
     * @throws IOException
     */
    public static String get(String urlString) throws IOException {
        URL url = new URL(urlString);
        URLConnection urlConnection = url.openConnection(java.net.Proxy.NO_PROXY);
        BufferedReader in = new BufferedReader(new InputStreamReader(
                urlConnection.getInputStream()));
        String inputLine;
        String resp = "";
        while ((inputLine = in.readLine()) != null) {
            resp = resp + inputLine + "\n";
        }
        in.close();
        return resp;
    }

    private static String toString(InputStream content) throws IOException {
        StringBuffer buffer = new StringBuffer();
        String line;
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(content));

        while ((line = bufferedReader.readLine()) != null) {
            if (buffer.length() != 0) {
                buffer.append('\n');
            }
            buffer.append(line);
        }

        return buffer.toString();
    }

    @Override
    public void run() {
        String file;
        try {
            file = get(mPacUrl);
        } catch (IOException ioe) {
            file = null;
        }
        if (file != null) {
            if (!file.equals(mCurrentPac)) {
                setCurrentProxyScript(file);
            }
            longSchedule();
        } else {
            reschedule();
        }
    }

    private int getNextDelay(int currentDelay) {
       if (++currentDelay > DELAY_4) {
           return DELAY_4;
       }
       return currentDelay;
    }

    private void longSchedule() {
        mCurrentDelay = DELAY_1;
        setDownloadIn(DELAY_LONG);
    }

    private void reschedule() {
        mCurrentDelay = getNextDelay(mCurrentDelay);
        setDownloadIn(mCurrentDelay);
    }

    private String getPacChangeDelay() {
        final ContentResolver cr = mContext.getContentResolver();

        /** Check system properties for the default value then use secure settings value, if any. */
        String defaultDelay = SystemProperties.get(
                "conn." + Settings.Global.PAC_CHANGE_DELAY,
                DEFAULT_DELAYS);
        String val = Settings.Global.getString(cr, Settings.Global.PAC_CHANGE_DELAY);
        return (val == null) ? defaultDelay : val;
    }

    private long getDownloadDelay(int delayIndex) {
        String[] list = getPacChangeDelay().split(" ");
        if (delayIndex < list.length) {
            return Long.parseLong(list[delayIndex]);
        }
        return 0;
    }

    private void setDownloadIn(int delayIndex) {
        long delay = getDownloadDelay(delayIndex);
        long timeTillTrigger = 1000 * delay + SystemClock.elapsedRealtime();
        getAlarmManager().set(AlarmManager.ELAPSED_REALTIME, timeTillTrigger, mPacRefreshIntent);
    }

    private boolean setCurrentProxyScript(String script) {
        try {
            if (mProxyService.setPacFile(script) != NO_ERROR) {
                Log.e(TAG, "Unable to parse proxy script.");
                return false;
            }
            mCurrentPac = script;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set PAC file", e);
        }
        return true;
    }
}
