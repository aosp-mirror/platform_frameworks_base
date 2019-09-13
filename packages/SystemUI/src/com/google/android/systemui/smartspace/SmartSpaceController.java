package com.google.android.systemui.smartspace;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.KeyValueListParser;
import android.util.Log;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dumpable;
import com.google.android.systemui.smartspace.nano.SmartspaceProto.CardWrapper;
import com.android.systemui.util.Assert;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class SmartSpaceController implements Dumpable {
    static final boolean DEBUG = Log.isLoggable("SmartSpaceController", 3);
    private static SmartSpaceController sInstance;
    private final AlarmManager mAlarmManager;
    private boolean mAlarmRegistered;
    private final Context mAppContext;
    private final Handler mBackgroundHandler;
    private final Context mContext;
    public int mCurrentUserId;
    public final SmartSpaceData mData;
    private final OnAlarmListener mExpireAlarmAction = new OnAlarmListener() {
        @Override
        public final void onAlarm() {
            onExpire(false);
        }
    };
    private boolean mHidePrivateData;
    private final KeyguardUpdateMonitorCallback mKeyguardMonitorCallback = new KeyguardUpdateMonitorCallback() {
        public void onTimeChanged() {
            if (mData != null && mData.hasCurrent() && mData.getExpirationRemainingMillis() > 0) {
                update();
            }
        }
    };
    private final ArrayList<SmartSpaceUpdateListener> mListeners = new ArrayList<>();
    private boolean mSmartSpaceEnabledBroadcastSent;
    private final ProtoStore mStore;
    private final Handler mUiHandler;

    private class UserSwitchReceiver extends BroadcastReceiver {
        private UserSwitchReceiver() {
        }

        public void onReceive(Context context, Intent intent) {
            if (SmartSpaceController.DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("Switching user: ");
                sb.append(intent.getAction());
                sb.append(" uid: ");
                sb.append(UserHandle.myUserId());
                Log.d("SmartSpaceController", sb.toString());
            }
            if (intent.getAction().equals("android.intent.action.USER_SWITCHED")) {
                mCurrentUserId = intent.getIntExtra("android.intent.extra.user_handle", -1);
                mData.clear();
                onExpire(true);
            }
            onExpire(true);
        }
    }

    public static SmartSpaceController get(Context context) {
        if (sInstance == null) {
            if (DEBUG) {
                Log.d("SmartSpaceController", "controller created");
            }
            sInstance = new SmartSpaceController(context.getApplicationContext());
        }
        return sInstance;
    }

    private SmartSpaceController(Context context) {
        mContext = context;
        mUiHandler = new Handler(Looper.getMainLooper());
        mStore = new ProtoStore(mContext);
        HandlerThread handlerThread = new HandlerThread("smartspace-background");
        handlerThread.start();
        mBackgroundHandler = new Handler(handlerThread.getLooper());
        mCurrentUserId = UserHandle.myUserId();
        mAppContext = context;
        mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
        mData = new SmartSpaceData();
        if (!isSmartSpaceDisabledByExperiments()) {
            KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mKeyguardMonitorCallback);
            reloadData();
            onGsaChanged();
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    onGsaChanged();
                }
            }, GSAIntents.getGsaPackageFilter("android.intent.action.PACKAGE_ADDED", "android.intent.action.PACKAGE_CHANGED", "android.intent.action.PACKAGE_REMOVED", "android.intent.action.PACKAGE_DATA_CLEARED"));
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.USER_SWITCHED");
            intentFilter.addAction("android.intent.action.USER_UNLOCKED");
            context.registerReceiver(new UserSwitchReceiver(), intentFilter);
            context.registerReceiver(new SmartSpaceBroadcastReceiver(this), new IntentFilter("com.google.android.apps.nexuslauncher.UPDATE_SMARTSPACE"));
        }
    }

    private SmartSpaceCard loadSmartSpaceData(boolean z) {
        CardWrapper cardWrapper = new CardWrapper();
        ProtoStore protoStore = mStore;
        StringBuilder sb = new StringBuilder();
        sb.append("smartspace_");
        sb.append(mCurrentUserId);
        sb.append("_");
        sb.append(z);
        if (protoStore.load(sb.toString(), cardWrapper)) {
            return SmartSpaceCard.fromWrapper(mContext, cardWrapper, !z);
        }
        return null;
    }

    public void onNewCard(final NewCardInfo newCardInfo) {
        String str = "SmartSpaceController";
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("onNewCard: ");
            sb.append(newCardInfo);
            Log.d(str, sb.toString());
        }
        if (newCardInfo != null) {
            if (newCardInfo.getUserId() != mCurrentUserId) {
                if (DEBUG) {
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append("Ignore card that belongs to another user target: ");
                    sb2.append(mCurrentUserId);
                    sb2.append(" current: ");
                    sb2.append(mCurrentUserId);
                    Log.d(str, sb2.toString());
                }
                return;
            }
            mBackgroundHandler.post(new Runnable() {
                @Override
                public final void run() {
                    final CardWrapper wrapper = newCardInfo.toWrapper(mContext);
                    if (!mHidePrivateData) {
                        ProtoStore protoStore = mStore;
                        StringBuilder sb = new StringBuilder();
                        sb.append("smartspace_");
                        sb.append(mCurrentUserId);
                        sb.append("_");
                        sb.append(newCardInfo.isPrimary());
                        protoStore.store(wrapper, sb.toString());
                    }
                    mUiHandler.post(new Runnable() {
                        @Override
                        public final void run() {
                            SmartSpaceCard smartSpaceCard = newCardInfo.shouldDiscard() ? null :
                                    SmartSpaceCard.fromWrapper(mContext, wrapper, newCardInfo.isPrimary());
                            if (newCardInfo.isPrimary()) {
                                mData.mCurrentCard = smartSpaceCard;
                            } else {
                                mData.mWeatherCard = smartSpaceCard;
                            }
                            mData.handleExpire();
                            update();

                        }
                    });
                }
            });
        }
    }


    private void clearStore() {
        ProtoStore protoStore = mStore;
        StringBuilder sb = new StringBuilder();
        String str = "smartspace_";
        sb.append(str);
        sb.append(mCurrentUserId);
        sb.append("_true");
        protoStore.store(null, sb.toString());
        ProtoStore protoStore2 = mStore;
        StringBuilder sb2 = new StringBuilder();
        sb2.append(str);
        sb2.append(mCurrentUserId);
        sb2.append("_false");
        protoStore2.store(null, sb2.toString());
    }

    public void update() {
        Assert.isMainThread();
        String str = "SmartSpaceController";
        if (DEBUG) {
            Log.d(str, "update");
        }
        if (mAlarmRegistered) {
            mAlarmManager.cancel(mExpireAlarmAction);
            mAlarmRegistered = false;
        }
        long expiresAtMillis = mData.getExpiresAtMillis();
        if (expiresAtMillis > 0) {
            mAlarmManager.set(0, expiresAtMillis, "SmartSpace", mExpireAlarmAction, mUiHandler);
            mAlarmRegistered = true;
        }
        if (mListeners != null) {
            if (DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("notifying listeners data=");
                sb.append(mData);
                Log.d(str, sb.toString());
            }
            ArrayList arrayList = new ArrayList(mListeners);
            int size = arrayList.size();
            for (int i = 0; i < size; i++) {
                ((SmartSpaceUpdateListener) arrayList.get(i)).onSmartSpaceUpdated(mData);
            }
        }
    }

    public void onExpire(boolean z) {
        Assert.isMainThread();
        mAlarmRegistered = false;
        String str = "SmartSpaceController";
        if (mData.handleExpire() || z) {
            update();
            if (UserHandle.myUserId() == 0) {
                if (DEBUG) {
                    Log.d(str, "onExpire - sent");
                }
                mAppContext.sendBroadcast(new Intent("com.google.android.systemui.smartspace.EXPIRE_EVENT").setPackage("com.google.android.googlequicksearchbox").addFlags(268435456));
            }
        } else if (DEBUG) {
            Log.d(str, "onExpire - cancelled");
        }
    }

    public void setHideSensitiveData(boolean z) {
        mHidePrivateData = z;
        ArrayList arrayList = new ArrayList(mListeners);
        for (int i = 0; i < arrayList.size(); i++) {
            ((SmartSpaceUpdateListener) arrayList.get(i)).onSensitiveModeChanged(z);
        }
        if (mHidePrivateData) {
            clearStore();
        }
    }

    public void onGsaChanged() {
        if (DEBUG) {
            Log.d("SmartSpaceController", "onGsaChanged");
        }
        if (UserHandle.myUserId() == 0) {
            mAppContext.sendBroadcast(new Intent("com.google.android.systemui.smartspace.ENABLE_UPDATE").setPackage("com.google.android.googlequicksearchbox").addFlags(268435456));
            mSmartSpaceEnabledBroadcastSent = true;
        }
        ArrayList arrayList = new ArrayList(mListeners);
        for (int i = 0; i < arrayList.size(); i++) {
            ((SmartSpaceUpdateListener) arrayList.get(i)).onGsaChanged();
        }
    }

    public void reloadData() {
        mData.mCurrentCard = loadSmartSpaceData(true);
        mData.mWeatherCard = loadSmartSpaceData(false);
        update();
    }

    private boolean isSmartSpaceDisabledByExperiments() {
        boolean z;
        String string = Global.getString(mContext.getContentResolver(), "always_on_display_constants");
        KeyValueListParser keyValueListParser = new KeyValueListParser(',');
        try {
            keyValueListParser.setString(string);
            z = keyValueListParser.getBoolean("smart_space_enabled", true);
        } catch (IllegalArgumentException unused) {
            Log.e("SmartSpaceController", "Bad AOD constants");
            z = true;
        }
        if (!z) {
            return true;
        }
        return false;
    }

    @Override
    public void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        printWriter.println();
        printWriter.println("SmartspaceController");
        StringBuilder sb = new StringBuilder();
        sb.append("  initial broadcast: ");
        sb.append(mSmartSpaceEnabledBroadcastSent);
        printWriter.println(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        String str = "  weather ";
        sb2.append(str);
        sb2.append(mData.mWeatherCard);
        printWriter.println(sb2.toString());
        StringBuilder sb3 = new StringBuilder();
        String str2 = "  current ";
        sb3.append(str2);
        sb3.append(mData.mCurrentCard);
        printWriter.println(sb3.toString());
        printWriter.println("serialized:");
        StringBuilder sb4 = new StringBuilder();
        sb4.append(str);
        sb4.append(loadSmartSpaceData(false));
        printWriter.println(sb4.toString());
        StringBuilder sb5 = new StringBuilder();
        sb5.append(str2);
        sb5.append(loadSmartSpaceData(true));
        printWriter.println(sb5.toString());
        StringBuilder sb6 = new StringBuilder();
        sb6.append("disabled by experiment: ");
        sb6.append(isSmartSpaceDisabledByExperiments());
        printWriter.println(sb6.toString());
    }

    public void addListener(SmartSpaceUpdateListener smartSpaceUpdateListener) {
        Assert.isMainThread();
        mListeners.add(smartSpaceUpdateListener);
        SmartSpaceData smartSpaceData = mData;
        if (smartSpaceData != null && smartSpaceUpdateListener != null) {
            smartSpaceUpdateListener.onSmartSpaceUpdated(smartSpaceData);
        }
    }

    public void removeListener(SmartSpaceUpdateListener smartSpaceUpdateListener) {
        Assert.isMainThread();
        mListeners.remove(smartSpaceUpdateListener);
    }
}
