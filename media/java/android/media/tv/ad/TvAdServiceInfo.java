/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Xml;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to specify meta information of a TV AD service.
 */
@FlaggedApi(Flags.FLAG_ENABLE_AD_SERVICE_FW)
public final class TvAdServiceInfo implements Parcelable {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvAdServiceInfo";

    private static final String XML_START_TAG_NAME = "tv-ad-service";

    private final ResolveInfo mService;
    private final String mId;
    private final List<String> mTypes = new ArrayList<>();

    /**
     * Constructs a TvAdServiceInfo object.
     *
     * @param context the application context
     * @param component the component name of the TvAdService
     */
    public TvAdServiceInfo(@NonNull Context context, @NonNull ComponentName component) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        Intent intent = new Intent(TvAdService.SERVICE_INTERFACE).setComponent(component);
        ResolveInfo resolveInfo = context.getPackageManager().resolveService(
                intent, PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null) {
            throw new IllegalArgumentException("Invalid component. Can't find the service.");
        }

        ComponentName componentName = new ComponentName(resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name);
        String id;
        id = generateAdServiceId(componentName);
        List<String> types = new ArrayList<>();
        parseServiceMetadata(resolveInfo, context, types);

        mService = resolveInfo;
        mId = id;
        mTypes.addAll(types);
    }

    private TvAdServiceInfo(ResolveInfo service, String id, List<String> types) {
        mService = service;
        mId = id;
        mTypes.addAll(types);
    }

    private TvAdServiceInfo(@NonNull Parcel in) {
        mService = ResolveInfo.CREATOR.createFromParcel(in);
        mId = in.readString();
        in.readStringList(mTypes);
    }

    @NonNull
    public static final Creator<TvAdServiceInfo> CREATOR = new Creator<TvAdServiceInfo>() {
        @Override
        public TvAdServiceInfo createFromParcel(Parcel in) {
            return new TvAdServiceInfo(in);
        }

        @Override
        public TvAdServiceInfo[] newArray(int size) {
            return new TvAdServiceInfo[size];
        }
    };

    /**
     * Returns a unique ID for this TV AD service. The ID is generated from the package and class
     * name implementing the TV AD service.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the component of the TV AD service.
     * @hide
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Returns the information of the service that implements this AD service.
     */
    @Nullable
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Gets supported TV AD types.
     */
    @NonNull
    public List<String> getSupportedTypes() {
        return mTypes;
    }

    private static String generateAdServiceId(ComponentName name) {
        return name.flattenToShortString();
    }

    private static void parseServiceMetadata(
            ResolveInfo resolveInfo, Context context, List<String> types) {
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        PackageManager pm = context.getPackageManager();
        try (XmlResourceParser parser =
                     serviceInfo.loadXmlMetaData(pm, TvAdService.SERVICE_META_DATA)) {
            if (parser == null) {
                throw new IllegalStateException(
                        "No " + "android.media.tv.ad.service"
                                + " meta-data found for " + serviceInfo.name);
            }

            Resources resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // move to the START_TAG
            }

            String nodeName = parser.getName();
            if (!XML_START_TAG_NAME.equals(nodeName)) {
                throw new IllegalStateException("Meta-data does not start with "
                        + XML_START_TAG_NAME + " tag for " + serviceInfo.name);
            }

            TypedArray sa = resources.obtainAttributes(attrs,
                    com.android.internal.R.styleable.TvAdService);
            CharSequence[] textArr = sa.getTextArray(
                    com.android.internal.R.styleable.TvAdService_adServiceTypes);
            for (CharSequence cs : textArr) {
                types.add(cs.toString().toLowerCase());
            }

            sa.recycle();
        } catch (IOException | XmlPullParserException e) {
            throw new IllegalStateException(
                    "Failed reading meta-data for " + serviceInfo.packageName, e);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("No resources found for " + serviceInfo.packageName, e);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mId);
        dest.writeStringList(mTypes);
    }
}
