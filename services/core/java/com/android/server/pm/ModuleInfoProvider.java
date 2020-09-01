/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.pm;

import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provides data to back {@code ModuleInfo} related APIs in the package manager. The data is stored
 * as an XML resource in a configurable "module metadata" package.
 */
@VisibleForTesting
public class ModuleInfoProvider {
    private static final String TAG = "PackageManager.ModuleInfoProvider";

    /**
     * The key in the package's application level metadata bundle that provides a resource reference
     * to the module metadata.
     */
    private static final String MODULE_METADATA_KEY = "android.content.pm.MODULE_METADATA";

    private final Context mContext;
    private final IPackageManager mPackageManager;
    private final ApexManager mApexManager;
    private final Map<String, ModuleInfo> mModuleInfo;

    // TODO: Move this to an earlier boot phase if anybody requires it then.
    private volatile boolean mMetadataLoaded;
    private volatile String mPackageName;

    ModuleInfoProvider(Context context, IPackageManager packageManager) {
        mContext = context;
        mPackageManager = packageManager;
        mApexManager = ApexManager.getInstance();
        mModuleInfo = new ArrayMap<>();
    }

    @VisibleForTesting
    public ModuleInfoProvider(
            XmlResourceParser metadata, Resources resources, ApexManager apexManager) {
        mContext = null;
        mPackageManager = null;
        mApexManager = apexManager;
        mModuleInfo = new ArrayMap<>();
        loadModuleMetadata(metadata, resources);
    }

    /** Called by the {@code PackageManager} when it has completed its boot sequence */
    public void systemReady() {
        mPackageName = mContext.getResources().getString(
                R.string.config_defaultModuleMetadataProvider);
        if (TextUtils.isEmpty(mPackageName)) {
            Slog.w(TAG, "No configured module metadata provider.");
            return;
        }

        final Resources packageResources;
        final PackageInfo pi;
        try {
            pi = mPackageManager.getPackageInfo(mPackageName,
                PackageManager.GET_META_DATA, UserHandle.USER_SYSTEM);

            Context packageContext = mContext.createPackageContext(mPackageName, 0);
            packageResources = packageContext.getResources();
        } catch (RemoteException | NameNotFoundException e) {
            Slog.w(TAG, "Unable to discover metadata package: " + mPackageName, e);
            return;
        }

        XmlResourceParser parser = packageResources.getXml(
                pi.applicationInfo.metaData.getInt(MODULE_METADATA_KEY));
        loadModuleMetadata(parser, packageResources);
    }

    private void loadModuleMetadata(XmlResourceParser parser, Resources packageResources) {
        try {
            // The format for the module metadata is straightforward :
            //
            // The following attributes on <module> are currently defined :
            // -- name : A resource reference to a User visible package name, maps to
            //           ModuleInfo#getName
            // -- packageName : The package name of the module, see ModuleInfo#getPackageName
            // -- isHidden : Whether the module is hidden, see ModuleInfo#isHidden
            //
            // <module-metadata>
            //   <module name="@string/resource" packageName="package_name" isHidden="false|true" />
            //   <module .... />
            // </module-metadata>

            XmlUtils.beginDocument(parser, "module-metadata");
            while (true) {
                XmlUtils.nextElement(parser);
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (!"module".equals(parser.getName())) {
                    Slog.w(TAG, "Unexpected metadata element: " + parser.getName());
                    mModuleInfo.clear();
                    break;
                }

                // TODO: The module name here is fetched using the resource configuration applied
                // at the time of parsing this information. This is probably not the best approach
                // to dealing with this as we'll now have to listen to all config changes and
                // regenerate the data if required. Also, is this the right way to parse a resource
                // reference out of an XML file ?
                final CharSequence moduleName = packageResources.getText(
                        Integer.parseInt(parser.getAttributeValue(null, "name").substring(1)));
                final String modulePackageName = XmlUtils.readStringAttribute(parser,
                        "packageName");
                final boolean isHidden = XmlUtils.readBooleanAttribute(parser, "isHidden");

                ModuleInfo mi = new ModuleInfo();
                mi.setHidden(isHidden);
                mi.setPackageName(modulePackageName);
                mi.setName(moduleName);
                mi.setApexModuleName(
                        mApexManager.getApexModuleNameForPackageName(modulePackageName));

                mModuleInfo.put(modulePackageName, mi);
            }
        } catch (XmlPullParserException | IOException e) {
            Slog.w(TAG, "Error parsing module metadata", e);
            mModuleInfo.clear();
        } finally {
            parser.close();
            mMetadataLoaded = true;
        }
    }

    /**
     * By default, returns installed module info, including installed apex modules.
     *
     * @param flags Use {@link PackageManager#MATCH_ALL} flag to get all modules.
     */
    List<ModuleInfo> getInstalledModules(@PackageManager.InstalledModulesFlags int flags) {
        if (!mMetadataLoaded) {
            throw new IllegalStateException("Call to getInstalledModules before metadata loaded");
        }

        if ((flags & PackageManager.MATCH_ALL) != 0) {
            return new ArrayList<>(mModuleInfo.values());
        }

        List<PackageInfo> allPackages;
        try {
            allPackages = mPackageManager.getInstalledPackages(
                    flags | PackageManager.MATCH_APEX, UserHandle.USER_SYSTEM).getList();
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to retrieve all package names", e);
            return Collections.emptyList();
        }

        ArrayList<ModuleInfo> installedModules = new ArrayList<>(allPackages.size());
        for (PackageInfo p : allPackages) {
            ModuleInfo m = mModuleInfo.get(p.packageName);
            if (m != null) {
                installedModules.add(m);
            }
        }
        return installedModules;
    }

    ModuleInfo getModuleInfo(String name, @PackageManager.ModuleInfoFlags int flags) {
        if (!mMetadataLoaded) {
            throw new IllegalStateException("Call to getModuleInfo before metadata loaded");
        }
        if ((flags & PackageManager.MODULE_APEX_NAME) != 0) {
            for (ModuleInfo moduleInfo : mModuleInfo.values()) {
                if (name.equals(moduleInfo.getApexModuleName())) {
                    return moduleInfo;
                }
            }
            return null;
        }
        return mModuleInfo.get(name);
    }

    String getPackageName() {
        if (!mMetadataLoaded) {
            throw new IllegalStateException("Call to getVersion before metadata loaded");
        }
        return mPackageName;
    }
}
