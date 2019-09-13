package com.google.android.systemui.smartspace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.UserHandle;
import android.util.Log;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.SmartspaceUpdate.SmartspaceCard;
import com.google.protobuf.nano.InvalidProtocolBufferNanoException;
import com.google.protobuf.nano.MessageNano;

public class SmartSpaceBroadcastReceiver extends BroadcastReceiver {
    private final SmartSpaceController mController;

    public SmartSpaceBroadcastReceiver(SmartSpaceController smartSpaceController) {
        mController = smartSpaceController;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        SmartspaceCard[] smartspaceCardArr;
        String str = "SmartSpaceReceiver";
        if (SmartSpaceController.DEBUG) {
            Log.d(str, "receiving update");
        }
        int myUserId = UserHandle.myUserId();
        String str2 = "uid";
        if (myUserId != 0) {
            String str3 = "rebroadcast";
            if (!intent.getBooleanExtra(str3, false)) {
                intent.putExtra(str3, true);
                intent.putExtra(str2, myUserId);
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
                return;
            }
            return;
        }
        if (!intent.hasExtra(str2)) {
            intent.putExtra(str2, myUserId);
        }
        byte[] byteArrayExtra = intent.getByteArrayExtra("com.google.android.apps.nexuslauncher.extra.SMARTSPACE_CARD");
        if (byteArrayExtra != null) {
            SmartspaceUpdate smartspaceUpdate = new SmartspaceUpdate();
            try {
                MessageNano.mergeFrom(smartspaceUpdate, byteArrayExtra);
                for (SmartspaceCard smartspaceCard : smartspaceUpdate.card) {
                    boolean z = smartspaceCard.cardPriority == 1;
                    boolean z2 = smartspaceCard.cardPriority == 2;
                    if (!z) {
                        if (!z2) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("unrecognized card priority: ");
                            sb.append(smartspaceCard.cardPriority);
                            Log.w(str, sb.toString());
                        }
                    }
                    notify(smartspaceCard, context, intent, z);
                }
            } catch (InvalidProtocolBufferNanoException e) {
                Log.e(str, "proto", e);
            }
        } else {
            StringBuilder sb2 = new StringBuilder();
            sb2.append("receiving update with no proto: ");
            sb2.append(intent.getExtras());
            Log.e(str, sb2.toString());
        }
    }

    private void notify(SmartspaceCard smartspaceCard, Context context, Intent intent, boolean z) {
        PackageInfo packageInfo;
        long currentTimeMillis = System.currentTimeMillis();
        try {
            packageInfo = context.getPackageManager().getPackageInfo("com.google.android.googlequicksearchbox", 0);
        } catch (NameNotFoundException e) {
            Log.w("SmartSpaceReceiver", "Cannot find GSA", e);
            packageInfo = null;
        }
        NewCardInfo newCardInfo = new NewCardInfo(smartspaceCard, intent, z, currentTimeMillis, packageInfo);
        mController.onNewCard(newCardInfo);
    }
}
