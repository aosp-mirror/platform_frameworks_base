/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.service.autofill;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * {@link ServiceInfo} and meta-data about an {@link AutofillService}.
 *
 * @hide
 */
public final class AutofillServiceInfo {
    private static final String TAG = "AutofillServiceInfo";

    private static ServiceInfo getServiceInfoOrThrow(ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(
                    comp,
                    PackageManager.GET_META_DATA,
                    userHandle);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
        }
        throw new PackageManager.NameNotFoundException(comp.toString());
    }

    @NonNull
    private final ServiceInfo mServiceInfo;

    @Nullable
    private final String mSettingsActivity;

    public AutofillServiceInfo(PackageManager pm, ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        this(pm, getServiceInfoOrThrow(comp, userHandle));
    }

    public AutofillServiceInfo(PackageManager pm, ServiceInfo si) {
        mServiceInfo = si;
        final TypedArray metaDataArray = getMetaDataArray(pm, si);
        if (metaDataArray != null) {
            mSettingsActivity = metaDataArray
                    .getString(R.styleable.AutofillService_settingsActivity);
            metaDataArray.recycle();
        } else {
            mSettingsActivity = null;
        }
    }

    /**
     * Gets the meta-data as a {@link TypedArray}, or {@code null} if not provided,
     * or throws if invalid.
     */
    @Nullable
    private static TypedArray getMetaDataArray(PackageManager pm, ServiceInfo si) {
        // Check for permissions.
        // TODO(b/37563972): remove support to BIND_AUTOFILL once clients use BIND_AUTOFILL_SERVICE
        if (!Manifest.permission.BIND_AUTOFILL_SERVICE.equals(si.permission)
                && !Manifest.permission.BIND_AUTOFILL.equals(si.permission)) {
            Log.w(TAG, "AutofillService from '" + si.packageName + "' does not require permission "
                    + Manifest.permission.BIND_AUTOFILL_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_AUTOFILL_SERVICE);
        }

        // Get the AutoFill metadata, if declared.
        XmlResourceParser parser = si.loadXmlMetaData(pm, AutofillService.SERVICE_META_DATA);
        if (parser == null) {
            return null;
        }

        // Parse service info and get the <autofill-service> tag as an AttributeSet.
        AttributeSet attrs;
        try {
            // Move the XML parser to the first tag.
            try {
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) {
                }
            } catch (XmlPullParserException | IOException e) {
                Log.e(TAG, "Error parsing auto fill service meta-data", e);
                return null;
            }

            if (!"autofill-service".equals(parser.getName())) {
                Log.e(TAG, "Meta-data does not start with autofill-service tag");
                return null;
            }
            attrs = Xml.asAttributeSet(parser);

            // Get resources required to read the AttributeSet.
            Resources res;
            try {
                res = pm.getResourcesForApplication(si.applicationInfo);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Error getting application resources", e);
                return null;
            }

            return res.obtainAttributes(attrs, R.styleable.AutofillService);
        } finally {
            parser.close();
        }
    }

    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    @Nullable
    public String getSettingsActivity() {
        return mSettingsActivity;
    }

    @Override
    public String toString() {
        return mServiceInfo == null ? "null" : mServiceInfo.toString();
    }
}
