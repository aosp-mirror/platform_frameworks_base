/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.quickaccesswallet;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * {@link ServiceInfo} and meta-data about a {@link QuickAccessWalletService}.
 *
 * @hide
 */
class QuickAccessWalletServiceInfo {

    private static final String TAG = "QAWalletSInfo";
    private static final String TAG_WALLET_SERVICE = "quickaccesswallet-service";

    private final ServiceInfo mServiceInfo;
    private final ServiceMetadata mServiceMetadata;
    private final TileServiceMetadata mTileServiceMetadata;

    private QuickAccessWalletServiceInfo(
            @NonNull ServiceInfo serviceInfo,
            @NonNull ServiceMetadata metadata,
            @NonNull TileServiceMetadata tileServiceMetadata) {
        mServiceInfo = serviceInfo;
        mServiceMetadata = metadata;
        mTileServiceMetadata = tileServiceMetadata;
    }

    @Nullable
    static QuickAccessWalletServiceInfo tryCreate(@NonNull Context context) {
        ComponentName defaultPaymentApp = getDefaultPaymentApp(context);
        if (defaultPaymentApp == null) {
            return null;
        }

        ServiceInfo serviceInfo = getWalletServiceInfo(context, defaultPaymentApp.getPackageName());
        if (serviceInfo == null) {
            return null;
        }

        if (!Manifest.permission.BIND_QUICK_ACCESS_WALLET_SERVICE.equals(serviceInfo.permission)) {
            Log.w(TAG, String.format("%s.%s does not require permission %s",
                    serviceInfo.packageName, serviceInfo.name,
                    Manifest.permission.BIND_QUICK_ACCESS_WALLET_SERVICE));
            return null;
        }

        ServiceMetadata metadata = parseServiceMetadata(context, serviceInfo);
        TileServiceMetadata tileServiceMetadata =
                new TileServiceMetadata(parseTileServiceMetadata(context, serviceInfo));
        return new QuickAccessWalletServiceInfo(serviceInfo, metadata, tileServiceMetadata);
    }

    private static ComponentName getDefaultPaymentApp(Context context) {
        ContentResolver cr = context.getContentResolver();
        String comp = Settings.Secure.getString(cr, Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
        return comp == null ? null : ComponentName.unflattenFromString(comp);
    }

    private static ServiceInfo getWalletServiceInfo(Context context, String packageName) {
        Intent intent = new Intent(QuickAccessWalletService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServices(intent,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                        | PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.GET_META_DATA);
        return resolveInfos.isEmpty() ? null : resolveInfos.get(0).serviceInfo;
    }

    private static class TileServiceMetadata {
        @Nullable
        private final Drawable mTileIcon;

        private TileServiceMetadata(@Nullable Drawable tileIcon) {
            mTileIcon = tileIcon;
        }
    }

    @Nullable
    private static Drawable parseTileServiceMetadata(Context context, ServiceInfo serviceInfo) {
        PackageManager pm = context.getPackageManager();
        int tileIconDrawableId =
                serviceInfo.metaData.getInt(QuickAccessWalletService.TILE_SERVICE_META_DATA);
        if (tileIconDrawableId != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
                return resources.getDrawable(tileIconDrawableId, null);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Error parsing quickaccesswallet tile service meta-data", e);
            }
        }
        return null;
    }

    static class ServiceMetadata {
        @Nullable
        private final String mSettingsActivity;
        @Nullable
        private final String mTargetActivity;
        @Nullable
        private final CharSequence mShortcutShortLabel;
        @Nullable
        private final CharSequence mShortcutLongLabel;

        private static ServiceMetadata empty() {
            return new ServiceMetadata(null, null, null, null);
        }

        private ServiceMetadata(
                String targetActivity,
                String settingsActivity,
                CharSequence shortcutShortLabel,
                CharSequence shortcutLongLabel) {
            mTargetActivity = targetActivity;
            mSettingsActivity = settingsActivity;
            mShortcutShortLabel = shortcutShortLabel;
            mShortcutLongLabel = shortcutLongLabel;
        }
    }

    static ServiceMetadata parseServiceMetadata(Context context, ServiceInfo serviceInfo) {
        PackageManager pm = context.getPackageManager();
        final XmlResourceParser parser =
                serviceInfo.loadXmlMetaData(pm, QuickAccessWalletService.SERVICE_META_DATA);

        if (parser == null) {
            return ServiceMetadata.empty();
        }

        try {
            Resources resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            if (TAG_WALLET_SERVICE.equals(parser.getName())) {
                final AttributeSet allAttributes = Xml.asAttributeSet(parser);
                TypedArray afsAttributes = null;
                try {
                    afsAttributes = resources.obtainAttributes(allAttributes,
                            R.styleable.QuickAccessWalletService);
                    String targetActivity = afsAttributes.getString(
                            R.styleable.QuickAccessWalletService_targetActivity);
                    String settingsActivity = afsAttributes.getString(
                            R.styleable.QuickAccessWalletService_settingsActivity);
                    CharSequence shortcutShortLabel = afsAttributes.getText(
                            R.styleable.QuickAccessWalletService_shortcutShortLabel);
                    CharSequence shortcutLongLabel = afsAttributes.getText(
                            R.styleable.QuickAccessWalletService_shortcutLongLabel);
                    return new ServiceMetadata(targetActivity, settingsActivity, shortcutShortLabel,
                            shortcutLongLabel);
                } finally {
                    if (afsAttributes != null) {
                        afsAttributes.recycle();
                    }
                }
            } else {
                Log.e(TAG, "Meta-data does not start with quickaccesswallet-service tag");
            }
        } catch (PackageManager.NameNotFoundException
                | IOException
                | XmlPullParserException e) {
            Log.e(TAG, "Error parsing quickaccesswallet service meta-data", e);
        }
        return ServiceMetadata.empty();
    }

    /**
     * @return the component name of the {@link QuickAccessWalletService}
     */
    @NonNull
    ComponentName getComponentName() {
        return mServiceInfo.getComponentName();
    }

    /**
     * @return the fully qualified name of the activity that hosts the full wallet. If available,
     * this intent should be started with the action
     * {@link QuickAccessWalletService#ACTION_VIEW_WALLET}
     */
    @Nullable
    String getWalletActivity() {
        return mServiceMetadata.mTargetActivity;
    }

    /**
     * @return the fully qualified name of the activity that allows the user to change quick access
     * wallet settings. If available, this intent should be started with the action {@link
     * QuickAccessWalletService#ACTION_VIEW_WALLET_SETTINGS}
     */
    @Nullable
    String getSettingsActivity() {
        return mServiceMetadata.mSettingsActivity;
    }

    @NonNull
    Drawable getWalletLogo(Context context) {
        Drawable drawable = mServiceInfo.loadLogo(context.getPackageManager());
        if (drawable != null) {
            return drawable;
        }
        return mServiceInfo.loadIcon(context.getPackageManager());
    }

    @Nullable
    Drawable getTileIcon() {
        return mTileServiceMetadata.mTileIcon;
    }

    @NonNull
    CharSequence getShortcutShortLabel(Context context) {
        if (!TextUtils.isEmpty(mServiceMetadata.mShortcutShortLabel)) {
            return mServiceMetadata.mShortcutShortLabel;
        }
        return mServiceInfo.loadLabel(context.getPackageManager());
    }

    @NonNull
    CharSequence getShortcutLongLabel(Context context) {
        if (!TextUtils.isEmpty(mServiceMetadata.mShortcutLongLabel)) {
            return mServiceMetadata.mShortcutLongLabel;
        }
        return mServiceInfo.loadLabel(context.getPackageManager());
    }

    @NonNull
    CharSequence getServiceLabel(Context context) {
        return mServiceInfo.loadLabel(context.getPackageManager());
    }
}
