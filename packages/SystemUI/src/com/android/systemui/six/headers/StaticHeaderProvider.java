/*
 *  Copyright (C) 2017 The OmniROM Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.android.systemui.R;
import com.android.internal.util.six.SixUtils;

public class StaticHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "StaticHeaderProvider";

    private Context mContext;
    private Resources mRes;
    private String mImage;
    private String mPackageName;

    public StaticHeaderProvider(Context context) {
        mContext = context;
    }

    @Override
    public String getName() {
        return "static";
    }

    @Override
    public void settingsChanged() {
        final boolean customHeader = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        String imageUrl = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_IMAGE,
                UserHandle.USER_CURRENT);

        if (imageUrl != null && customHeader) {
            int idx = imageUrl.indexOf("/");
            if (idx != -1) {
                String[] parts = imageUrl.split("/");
                mPackageName = parts[0];
                mImage = parts[1];
                loadHeaderImage();
            }
        }
    }

    @Override
    public void enableProvider() {
        settingsChanged();
    }

    @Override
    public void disableProvider() {
    }

    private void loadHeaderImage() {
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mRes = packageManager.getResourcesForApplication(mPackageName);
        } catch (Exception e) {
            mRes = null;
        }
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        if (mRes == null) {
            return null;
        }
        if (!SixUtils.isAvailableApp(mPackageName, mContext)) {
            return null;
        }
        try {
            return mRes.getDrawable(mRes.getIdentifier(mImage, "drawable", mPackageName), null);
        } catch(Resources.NotFoundException e) {
        }
        return null;
    }
}
