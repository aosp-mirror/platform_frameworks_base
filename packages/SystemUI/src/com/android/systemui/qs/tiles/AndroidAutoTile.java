/*
 * Copyright (C) 2016 The ABC Rom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

public class AndroidAutoTile extends QSTile<QSTile.BooleanState> {

    PackageManager pm = mContext.getPackageManager();

    private final String androidautoPackage = "com.google.android.projection.gearhead";

    public AndroidAutoTile(Host host) {
        super(host);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QUICK_SETTINGS;
    }

    private void callApp() {
        mHost.collapsePanels();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        if (isNewVersion()) {
            intent.setClassName(androidautoPackage,
                "com.google.android.gearhead.vanagon.VnLaunchPadActivity");
        } else {
            intent.setClassName(androidautoPackage,
                "com.google.android.projection.gearhead.companion.SplashScreenActivity");
        }
        mHost.startActivityDismissingKeyguard(intent);
    }

    @Override
    public boolean isAvailable() {
        //do not show the tile if Android Auto not installed
        return isPackageInstalled();
    }

    private boolean isPackageInstalled(){
        try {
            PackageInfo info = pm.getPackageInfo(androidautoPackage,PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    private boolean isNewVersion() {
        try {
            PackageInfo info = pm.getPackageInfo(androidautoPackage, 0);
            String version = info.versionName;
            String cut = version.substring(0,1);
            int ver = Integer.parseInt(cut);
            if (ver == 1) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            //catch PackageManager exception -it should not happen because we check it in
            //isPackageInstalled- and if parseInt gives any exception, assume we have the new
            //Auto version thus try the related intent, if the intent doesn't work, the system
            //just does nothing so we are safe anyway.
            return true;
        }
    }

    @Override
    public void handleClick() {
        callApp();
    }

    @Override
    protected void handleLongClick() {
        callApp();
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_androidauto_label);
    }

    @Override
    public void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_androidauto_label);
        state.contentDescription = mContext.getString(
                R.string.quick_settings_androidauto_label);
        state.icon = ResourceIcon.get(R.drawable.ic_qs_androidauto);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
    }
}
