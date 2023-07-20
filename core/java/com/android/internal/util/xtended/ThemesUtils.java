/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.xtended;

import static android.os.UserHandle.USER_SYSTEM;

import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ThemesUtils {

    public static final String TAG = "ThemesUtils";

    public static final String FONT_KEY = "android.theme.customization.font";
    public static final String ICON_SHAPE_KEY= "android.theme.customization.adaptive_icon_shape";

    // Statusbar Signal icons
    private static final String[] SIGNAL_BAR = {
        "org.blissroms.systemui.signalbar_a",
        "org.blissroms.systemui.signalbar_b",
        "org.blissroms.systemui.signalbar_c",
        "org.blissroms.systemui.signalbar_d",
        "org.blissroms.systemui.signalbar_e",
        "org.blissroms.systemui.signalbar_f",
        "org.blissroms.systemui.signalbar_g",
        "org.blissroms.systemui.signalbar_h",
    };

    // Statusbar Wifi icons
    private static final String[] WIFI_BAR = {
        "org.blissroms.systemui.wifibar_a",
        "org.blissroms.systemui.wifibar_b",
        "org.blissroms.systemui.wifibar_c",
        "org.blissroms.systemui.wifibar_d",
        "org.blissroms.systemui.wifibar_e",
        "org.blissroms.systemui.wifibar_f",
        "org.blissroms.systemui.wifibar_g",
        "org.blissroms.systemui.wifibar_h",
    };

    public static final Comparator<OverlayInfo> OVERLAY_INFO_COMPARATOR =
            Comparator.comparingInt(a -> a.priority);

    private Context mContext;
    private IOverlayManager mOverlayManager;
    private PackageManager pm;
    private Resources overlayRes;

    public ThemesUtils(Context context) {
        mContext = context;
        mOverlayManager = IOverlayManager.Stub
                .asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
    }

    public void setOverlayEnabled(String category, String packageName) {
        final String currentPackageName = getOverlayInfos(category).stream()
                .filter(info -> info.isEnabled())
                .map(info -> info.packageName)
                .findFirst()
                .orElse(null);

        try {
            if ("android".equals(packageName)) {
                mOverlayManager.setEnabled(currentPackageName, false, USER_SYSTEM);
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName,
                        USER_SYSTEM);
            }

            writeSettings(category, packageName, "android".equals(packageName));

        } catch (RemoteException e) {
        }
    }

    public void writeSettings(String category, String packageName, boolean disable) {
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, USER_SYSTEM);
        JSONObject object;
        try {
            if (overlayPackageJson == null) {
                object = new JSONObject();
            } else {
                object = new JSONObject(overlayPackageJson);
            }
            if (disable) {
                if (object.has(category)) object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    object.toString(), USER_SYSTEM);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
    }

    public List<String> getOverlayPackagesForCategory(String category) {
        return getOverlayPackagesForCategory(category, "android");
    }

    public List<String> getOverlayPackagesForCategory(String category, String target) {
        List<String> overlays = new ArrayList<>();
        overlays.add("android");
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                overlays.add(info.getPackageName());
            }
        }
        return overlays;
    }

    public List<OverlayInfo> getOverlayInfos(String category) {
        return getOverlayInfos(category, "android");
    }

    public List<OverlayInfo> getOverlayInfos(String category, String target) {
        final List<OverlayInfo> filteredInfos = new ArrayList<>();
        try {
            List<OverlayInfo> overlayInfos = mOverlayManager
                    .getOverlayInfosForTarget(target, USER_SYSTEM);
            for (OverlayInfo overlayInfo : overlayInfos) {
                if (category.equals(overlayInfo.category)) {
                    filteredInfos.add(overlayInfo);
                }
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
        filteredInfos.sort(OVERLAY_INFO_COMPARATOR);
        return filteredInfos;
    }

    public List<String> getLabels(String category) {
        List<String> labels = new ArrayList<>();
        labels.add("Default");
        for (OverlayInfo info : getOverlayInfos(category)) {
            if (category.equals(info.getCategory())) {
                try {
                    labels.add(pm.getApplicationInfo(info.packageName, 0)
                            .loadLabel(pm).toString());
                } catch (PackageManager.NameNotFoundException e) {
                    labels.add(info.packageName);
                }
            }
        }
        return labels;
    }

    public List<Typeface> getFonts() {
        final List<Typeface> fontlist = new ArrayList<>();
            for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
                try {
                    overlayRes = overlayPackage.equals("android") ? Resources.getSystem()
                           : pm.getResourcesForApplication(overlayPackage);
                    final String font = overlayRes.getString(
                            overlayRes.getIdentifier("config_bodyFontFamily",
                            "string", overlayPackage));
                    fontlist.add(Typeface.create(font, Typeface.NORMAL));
                } catch (NameNotFoundException | NotFoundException e) {
                // Do nothing
                }
            }
        return fontlist;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        final List<ShapeDrawable> shapelist = new ArrayList<>();
            for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
                    shapelist.add(createShapeDrawable(overlayPackage));
            }
        return shapelist;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        try {
            if (overlayPackage.equals("android")) {
                overlayRes = Resources.getSystem();
            } else {
                if (overlayPackage.equals("default")) overlayPackage = "android";
                overlayRes = pm.getResourcesForApplication(overlayPackage);
            }
        } catch (NameNotFoundException | NotFoundException e) {
            // Do nothing
        }
        final String shape = overlayRes.getString(
            overlayRes.getIdentifier("config_icon_mask",
            "string", overlayPackage));
        Path path = TextUtils.isEmpty(shape) ? null : PathParser.createPathFromPathData(shape);
        PathShape pathShape = new PathShape(path, 100f, 100f);
        ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
        int mThumbSize = (int) (mContext.getResources().getDisplayMetrics().density * 72);
        shapeDrawable.setIntrinsicHeight(mThumbSize);
        shapeDrawable.setIntrinsicWidth(mThumbSize);
        return shapeDrawable;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
            return info == null ? false : info.isEnabled();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        for (String overlayPackage : getOverlayPackagesForCategory(category)) {
            try {
                OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
                if (info != null && info.isEnabled()) {
                    return false;
                } else {
                    continue;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return true;
    }
}
