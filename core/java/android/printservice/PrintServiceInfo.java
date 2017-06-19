/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.printservice;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * This class describes a {@link PrintService}. A print service knows
 * how to communicate with one or more printers over one or more protocols
 * and exposes printers for use by the applications via the platform print
 * APIs.
 *
 * @see PrintService
 * @see android.print.PrintManager
 *
 * @hide
 */
@SystemApi
public final class PrintServiceInfo implements Parcelable {

    private static final String LOG_TAG = PrintServiceInfo.class.getSimpleName();

    private static final String TAG_PRINT_SERVICE = "print-service";

    private final String mId;

    private boolean mIsEnabled;

    private final ResolveInfo mResolveInfo;

    private final String mSettingsActivityName;

    private final String mAddPrintersActivityName;

    private final String mAdvancedPrintOptionsActivityName;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public PrintServiceInfo(Parcel parcel) {
        mId = parcel.readString();
        mIsEnabled = parcel.readByte() != 0;
        mResolveInfo = parcel.readParcelable(null);
        mSettingsActivityName = parcel.readString();
        mAddPrintersActivityName = parcel.readString();
        mAdvancedPrintOptionsActivityName = parcel.readString();
    }

    /**
     * Creates a new instance.
     *
     * @param resolveInfo The service resolve info.
     * @param settingsActivityName Optional settings activity name.
     * @param addPrintersActivityName Optional add printers activity name.
     * @param advancedPrintOptionsActivityName Optional advanced print options activity.
     *
     * @hide
     */
    public PrintServiceInfo(ResolveInfo resolveInfo, String settingsActivityName,
            String addPrintersActivityName, String advancedPrintOptionsActivityName) {
        mId = new ComponentName(resolveInfo.serviceInfo.packageName,
                resolveInfo.serviceInfo.name).flattenToString();
        mResolveInfo = resolveInfo;
        mSettingsActivityName = settingsActivityName;
        mAddPrintersActivityName = addPrintersActivityName;
        mAdvancedPrintOptionsActivityName = advancedPrintOptionsActivityName;
    }

    /**
     * Return the component name for this print service.
     *
     * @return The component name for this print service.
     */
    public @NonNull ComponentName getComponentName() {
        return new ComponentName(mResolveInfo.serviceInfo.packageName,
                mResolveInfo.serviceInfo.name);
    }

    /**
     * Creates a new instance.
     *
     * @param context Context for accessing resources.
     * @param resolveInfo The service resolve info.
     * @return The created instance.
     *
     * @hide
     */
    public static PrintServiceInfo create(Context context, ResolveInfo resolveInfo) {
        String settingsActivityName = null;
        String addPrintersActivityName = null;
        String advancedPrintOptionsActivityName = null;

        XmlResourceParser parser = null;
        PackageManager packageManager = context.getPackageManager();
        parser = resolveInfo.serviceInfo.loadXmlMetaData(packageManager,
                PrintService.SERVICE_META_DATA);
        if (parser != null) {
            try {
                int type = 0;
                while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                    type = parser.next();
                }

                String nodeName = parser.getName();
                if (!TAG_PRINT_SERVICE.equals(nodeName)) {
                    Log.e(LOG_TAG, "Ignoring meta-data that does not start with "
                            + TAG_PRINT_SERVICE + " tag");
                } else {
                    Resources resources = packageManager.getResourcesForApplication(
                            resolveInfo.serviceInfo.applicationInfo);
                    AttributeSet allAttributes = Xml.asAttributeSet(parser);
                    TypedArray attributes = resources.obtainAttributes(allAttributes,
                            com.android.internal.R.styleable.PrintService);

                    settingsActivityName = attributes.getString(
                            com.android.internal.R.styleable.PrintService_settingsActivity);

                    addPrintersActivityName = attributes.getString(
                            com.android.internal.R.styleable.PrintService_addPrintersActivity);

                    advancedPrintOptionsActivityName = attributes.getString(com.android.internal
                            .R.styleable.PrintService_advancedPrintOptionsActivity);

                    attributes.recycle();
                }
            } catch (IOException ioe) {
                Log.w(LOG_TAG, "Error reading meta-data:" + ioe);
            } catch (XmlPullParserException xppe) {
                Log.w(LOG_TAG, "Error reading meta-data:" + xppe);
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Unable to load resources for: "
                        + resolveInfo.serviceInfo.packageName);
            } finally {
                if (parser != null) {
                    parser.close();
                }
            }
        }

        return new PrintServiceInfo(resolveInfo, settingsActivityName,
                addPrintersActivityName, advancedPrintOptionsActivityName);
    }

    /**
     * The accessibility service id.
     * <p>
     * <strong>Generated by the system.</strong>
     * </p>
     *
     * @return The id.
     *
     * @hide
     */
    public String getId() {
        return mId;
    }

    /**
     * If the service was enabled when it was read from the system.
     *
     * @return The id.
     *
     * @hide
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Mark a service as enabled or not
     *
     * @param isEnabled If the service should be marked as enabled.
     *
     * @hide
     */
    public void setIsEnabled(boolean isEnabled) {
        mIsEnabled = isEnabled;
    }

    /**
     * The service {@link ResolveInfo}.
     *
     * @return The info.
     *
     * @hide
     */
    public ResolveInfo getResolveInfo() {
        return mResolveInfo;
    }

    /**
     * The settings activity name.
     * <p>
     * <strong>Statically set from
     * {@link PrintService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     *
     * @return The settings activity name.
     *
     * @hide
     */
    public String getSettingsActivityName() {
        return mSettingsActivityName;
    }

    /**
     * The add printers activity name.
     * <p>
     * <strong>Statically set from
     * {@link PrintService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     *
     * @return The add printers activity name.
     *
     * @hide
     */
    public String getAddPrintersActivityName() {
        return mAddPrintersActivityName;
    }

    /**
     * The advanced print options activity name.
     * <p>
     * <strong>Statically set from
     * {@link PrintService#SERVICE_META_DATA meta-data}.</strong>
     * </p>
     *
     * @return The advanced print options activity name.
     *
     * @hide
     */
    public String getAdvancedOptionsActivityName() {
        return mAdvancedPrintOptionsActivityName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flagz) {
        parcel.writeString(mId);
        parcel.writeByte((byte)(mIsEnabled ? 1 : 0));
        parcel.writeParcelable(mResolveInfo, 0);
        parcel.writeString(mSettingsActivityName);
        parcel.writeString(mAddPrintersActivityName);
        parcel.writeString(mAdvancedPrintOptionsActivityName);
    }

    @Override
    public int hashCode() {
        return 31 + ((mId == null) ? 0 : mId.hashCode());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PrintServiceInfo other = (PrintServiceInfo) obj;
        if (mId == null) {
            if (other.mId != null) {
                return false;
            }
        } else if (!mId.equals(other.mId)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("PrintServiceInfo{");
        builder.append("id=").append(mId);
        builder.append("isEnabled=").append(mIsEnabled);
        builder.append(", resolveInfo=").append(mResolveInfo);
        builder.append(", settingsActivityName=").append(mSettingsActivityName);
        builder.append(", addPrintersActivityName=").append(mAddPrintersActivityName);
        builder.append(", advancedPrintOptionsActivityName=")
                .append(mAdvancedPrintOptionsActivityName);
        builder.append("}");
        return builder.toString();
    }

    public static final Parcelable.Creator<PrintServiceInfo> CREATOR =
            new Parcelable.Creator<PrintServiceInfo>() {
        @Override
        public PrintServiceInfo createFromParcel(Parcel parcel) {
            return new PrintServiceInfo(parcel);
        }

        @Override
        public PrintServiceInfo[] newArray(int size) {
            return new PrintServiceInfo[size];
        }
    };
}
