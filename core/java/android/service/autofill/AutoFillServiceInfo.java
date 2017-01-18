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
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
public final class AutoFillServiceInfo {
    static final String TAG = "AutoFillServiceInfo";

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

    @Nullable
    private String mParseError;

    @Nullable
    private ServiceInfo mServiceInfo;
    @Nullable
    private String mSettingsActivity;

    public AutoFillServiceInfo(PackageManager pm, ComponentName comp, int userHandle)
            throws PackageManager.NameNotFoundException {
        this(pm, getServiceInfoOrThrow(comp, userHandle));
    }

    public AutoFillServiceInfo(PackageManager pm, ServiceInfo si)
            throws PackageManager.NameNotFoundException{
        if (si == null) {
            mParseError = "Service not available";
            return;
        }
        if (!Manifest.permission.BIND_AUTO_FILL.equals(si.permission)) {
            mParseError = "Service does not require permission "
                    + Manifest.permission.BIND_AUTO_FILL;
            return;
        }

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, AutoFillService.SERVICE_META_DATA);
            if (parser == null) {
                mParseError = "No " + AutoFillService.SERVICE_META_DATA
                        + " meta-data for " + si.packageName;
                return;
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"autofill-service".equals(nodeName)) {
                mParseError = "Meta-data does not start with autofill-service tag";
                return;
            }

            TypedArray array = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.AutoFillService);
            mSettingsActivity = array.getString(
                    com.android.internal.R.styleable.AutoFillService_settingsActivity);
            array.recycle();
        } catch (XmlPullParserException | IOException | PackageManager.NameNotFoundException e) {
            mParseError = "Error parsing auto fill service meta-data: " + e;
            Log.w(TAG, "error parsing auto fill service meta-data", e);
            return;
        } finally {
            if (parser != null) parser.close();
        }
        mServiceInfo = si;
    }

    @Nullable
    public String getParseError() {
        return mParseError;
    }

    @Nullable
    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    @Nullable
    public String getSettingsActivity() {
        return mSettingsActivity;
    }
}
