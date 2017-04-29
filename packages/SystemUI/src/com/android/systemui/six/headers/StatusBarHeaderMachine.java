/*
 *  Copyright (C) 2015 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.six.headers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.util.Calendar;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.Calendar;
import java.util.Map;
import java.util.HashMap;

import com.android.systemui.statusbar.phone.QuickStatusBarHeader;

public class StatusBarHeaderMachine {

    private static final String TAG = "StatusBarHeaderMachine";

    public interface IStatusBarHeaderProvider {
        public Drawable getCurrent(final Calendar time);

        public String getName();

        public void settingsChanged();

        public void enableProvider();

        public void disableProvider();
    }

    public interface IStatusBarHeaderMachineObserver {
        public void updateHeader(Drawable headerImage, boolean force);

        public void disableHeader();
    }

    private Context mContext;
    private Map<String, IStatusBarHeaderProvider> mProviders = new HashMap<String, IStatusBarHeaderProvider>();
    private List<IStatusBarHeaderMachineObserver> mObservers = new ArrayList<IStatusBarHeaderMachineObserver>();
    private Handler mHandler = new Handler();
    private boolean mAttached;
    private boolean mScreenOn = true;
    private String mDefaultProviderName;
    private String mCurrentProviderName;

    // broadcast providers sent when they update the header image
    public static final String STATUS_BAR_HEADER_UPDATE_ACTION = "com.android.systemui.six.headers.STATUS_BAR_HEADER_UPDATE";

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (STATUS_BAR_HEADER_UPDATE_ACTION.equals(intent.getAction())) {
                if (mScreenOn) {
                    doUpdateStatusHeaderObservers(false);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // we use ineact alarms so we cant be sure they have been sent during
                // screen off (sleep) - so update it on screen on
                if (!mScreenOn) {
                    mScreenOn = true;
                    doUpdateStatusHeaderObservers(true);
                }
            }
        }
    };

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CUSTOM_HEADER),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_DAYLIGHT_HEADER_PACK),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE),
                    false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                    UserHandle.USER_CURRENT) == 1;

            if (customHeader) {
                IStatusBarHeaderProvider provider = getCurrentProvider();
                // forward to current active provider
                if (provider != null) {
                    try {
                        provider.settingsChanged();
                    } catch (Exception e) {
                        // just in case
                    }
                }
            }
            updateEnablement();
        }
    }

    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    public StatusBarHeaderMachine(Context context) {
        mContext = context;
        DaylightHeaderProvider defaultProvider = new DaylightHeaderProvider(context);
        addProvider(defaultProvider);
        mDefaultProviderName = defaultProvider.getName();
        mCurrentProviderName = mDefaultProviderName;
        addProvider(new StaticHeaderProvider(context));
        mSettingsObserver.observe();
    }

    public Drawable getCurrent() {
        final Calendar now = Calendar.getInstance();
        IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider != null) {
            try {
                return provider.getCurrent(now);
            } catch (Exception e) {
                // just in case
            }
        }
        return null;
    }

    public void addProvider(IStatusBarHeaderProvider provider) {
        if (!mProviders.containsKey(provider.getName())) {
            mProviders.put(provider.getName(), provider);
        }
    }

    public void addObserver(IStatusBarHeaderMachineObserver observer) {

        // there can only be one of that kind at a time
        // so remove the old one first
        if (observer instanceof QuickStatusBarHeader) {
            IStatusBarHeaderMachineObserver prevObserver = null;
            Iterator<IStatusBarHeaderMachineObserver> nextObserver = mObservers.iterator();
            while (nextObserver.hasNext()) {
                IStatusBarHeaderMachineObserver o = nextObserver.next();
                if (o instanceof QuickStatusBarHeader) {
                    prevObserver = o;
                }
            }
            if (prevObserver != null) {
                mObservers.remove(prevObserver);
            }
        }
        if (!mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(IStatusBarHeaderMachineObserver observer) {
        mObservers.remove(observer);
    }

    private void doUpdateStatusHeaderObservers(final boolean force) {
        Iterator<IStatusBarHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusBarHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.updateHeader(getCurrent(), force);
            } catch (Exception e) {
                // just in case
            }
        }
    }

    private void doDisableStatusHeaderObservers() {
        Iterator<IStatusBarHeaderMachineObserver> nextObserver = mObservers
                .iterator();
        while (nextObserver.hasNext()) {
            IStatusBarHeaderMachineObserver observer = nextObserver.next();
            try {
                observer.disableHeader();
            } catch (Exception e) {
                // just in case
            }
        }
    }

    public void updateEnablement() {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        final String providerName = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_PROVIDER,
                UserHandle.USER_CURRENT);

        if (providerName != null && !providerName.equals(mCurrentProviderName)) {
            Iterator<IStatusBarHeaderProvider> nextProvider = mProviders
                    .values().iterator();
            while (nextProvider.hasNext()) {
                IStatusBarHeaderProvider provider = nextProvider.next();
                if (!provider.getName().equals(providerName)) {
                    provider.disableProvider();
                }
            }
            mCurrentProviderName = providerName;
        }

        IStatusBarHeaderProvider provider = getCurrentProvider();
        if (provider == null) {
            return;
        }
        if (customHeader) {
            provider.enableProvider();

            if (!mAttached) {
                // we dont want to wait for the alarm
                doUpdateStatusHeaderObservers(true);
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(STATUS_BAR_HEADER_UPDATE_ACTION);
                mContext.registerReceiver(mBroadcastReceiver, filter);
                mAttached = true;
            } else {
                // we dont want to wait for the alarm if provider has changed its header image
                doUpdateStatusHeaderObservers(true);
            }
        } else {
            provider.disableProvider();

            if (mAttached) {
                mContext.unregisterReceiver(mBroadcastReceiver);
                doDisableStatusHeaderObservers();
                mAttached = false;
            }
        }
    }

    private IStatusBarHeaderProvider getCurrentProvider() {
        if (mProviders.size() == 1) {
            return mProviders.get(mDefaultProviderName);
        }

        if (mCurrentProviderName == null || !mProviders.containsKey(mCurrentProviderName)) {
            return mProviders.get(mDefaultProviderName);
        }
        return mProviders.get(mCurrentProviderName);
    }
}
