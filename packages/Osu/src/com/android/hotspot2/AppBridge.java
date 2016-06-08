package com.android.hotspot2;

import android.content.Context;
import android.content.Intent;

import com.android.hotspot2.osu.OSUOperationStatus;

public class AppBridge {
    public static final String ACTION_OSU_NOTIFICATION = "com.android.hotspot2.OSU_NOTIFICATION";
    public static final String OSU_COUNT = "osu-count";
    public static final String SP_NAME = "sp-name";
    public static final String PROV_SUCCESS = "prov-success";
    public static final String DEAUTH = "deauth";
    public static final String DEAUTH_DELAY = "deauth-delay";
    public static final String DEAUTH_URL = "deauth-url";
    public static final String PROV_MESSAGE = "prov-message";
    public static final String OSU_INFO = "osu-info";

    public static final String GET_OSUS_ACTION = "com.android.hotspot2.GET_OSUS";

    private final Context mContext;

    public AppBridge(Context context) {
        mContext = context;
    }

    public void showOsuCount(int osuCount) {
        Intent intent = new Intent(ACTION_OSU_NOTIFICATION);
        intent.putExtra(OSU_COUNT, osuCount);
        intent.setFlags(
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

        mContext.startActivity(intent);
    }

    public void showStatus(OSUOperationStatus status, String spName, String message,
                           String remoteStatus) {
        Intent intent = new Intent(ACTION_OSU_NOTIFICATION);
        intent.putExtra(SP_NAME, spName);
        intent.putExtra(PROV_SUCCESS, status == OSUOperationStatus.ProvisioningSuccess);
        if (message != null) {
            intent.putExtra(PROV_MESSAGE, message);
        }
        intent.setFlags(
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    public void showDeauth(String spName, boolean ess, int delay, String url) {
        Intent intent = new Intent(ACTION_OSU_NOTIFICATION);
        intent.putExtra(SP_NAME, spName);
        intent.putExtra(DEAUTH, ess);
        intent.putExtra(DEAUTH_DELAY, delay);
        intent.putExtra(DEAUTH_URL, url);
        intent.setFlags(
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
}
