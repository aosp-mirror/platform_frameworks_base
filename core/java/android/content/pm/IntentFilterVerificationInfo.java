/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/**
 * The {@link com.android.server.pm.PackageManagerService} maintains some
 * {@link IntentFilterVerificationInfo}s for each domain / package name.
 *
 * @hide
 */
@SystemApi
public final class IntentFilterVerificationInfo implements Parcelable {
    private static final String TAG = IntentFilterVerificationInfo.class.getName();

    private static final String TAG_DOMAIN = "domain";
    private static final String ATTR_DOMAIN_NAME = "name";
    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_STATUS = "status";

    private ArraySet<String> mDomains = new ArraySet<>();
    private String mPackageName;
    private int mMainStatus;

    /** @hide */
    public IntentFilterVerificationInfo() {
        mPackageName = null;
        mMainStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    /** @hide */
    public IntentFilterVerificationInfo(String packageName, ArraySet<String> domains) {
        mPackageName = packageName;
        mDomains = domains;
        mMainStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    /** @hide */
    public IntentFilterVerificationInfo(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        readFromXml(parser);
    }

    /** @hide */
    public IntentFilterVerificationInfo(Parcel source) {
        readFromParcel(source);
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getStatus() {
        return mMainStatus;
    }

    /** @hide */
    public void setStatus(int s) {
        if (s >= INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED &&
                s <= INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            mMainStatus = s;
        } else {
            Log.w(TAG, "Trying to set a non supported status: " + s);
        }
    }

    public Set<String> getDomains() {
        return mDomains;
    }

    /** @hide */
    public void setDomains(ArraySet<String> list) {
        mDomains = list;
    }

    /** @hide */
    public String getDomainsString() {
        StringBuilder sb = new StringBuilder();
        for (String str : mDomains) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(str);
        }
        return sb.toString();
    }

    String getStringFromXml(XmlPullParser parser, String attribute, String defaultValue) {
        String value = parser.getAttributeValue(null, attribute);
        if (value == null) {
            String msg = "Missing element under " + TAG +": " + attribute + " at " +
                    parser.getPositionDescription();
            Log.w(TAG, msg);
            return defaultValue;
        } else {
            return value;
        }
    }

    int getIntFromXml(XmlPullParser parser, String attribute, int defaultValue) {
        String value = parser.getAttributeValue(null, attribute);
        if (TextUtils.isEmpty(value)) {
            String msg = "Missing element under " + TAG +": " + attribute + " at " +
                    parser.getPositionDescription();
            Log.w(TAG, msg);
            return defaultValue;
        } else {
            return Integer.parseInt(value);
        }
    }

    /** @hide */
    public void readFromXml(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        mPackageName = getStringFromXml(parser, ATTR_PACKAGE_NAME, null);
        if (mPackageName == null) {
            Log.e(TAG, "Package name cannot be null!");
        }
        int status = getIntFromXml(parser, ATTR_STATUS, -1);
        if (status == -1) {
            Log.e(TAG, "Unknown status value: " + status);
        }
        mMainStatus = status;

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_DOMAIN)) {
                String name = getStringFromXml(parser, ATTR_DOMAIN_NAME, null);
                if (!TextUtils.isEmpty(name)) {
                    mDomains.add(name);
                }
            } else {
                Log.w(TAG, "Unknown tag parsing IntentFilter: " + tagName);
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    /** @hide */
    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_PACKAGE_NAME, mPackageName);
        serializer.attribute(null, ATTR_STATUS, String.valueOf(mMainStatus));
        for (String str : mDomains) {
            serializer.startTag(null, TAG_DOMAIN);
            serializer.attribute(null, ATTR_DOMAIN_NAME, str);
            serializer.endTag(null, TAG_DOMAIN);
        }
    }

    /** @hide */
    public String getStatusString() {
        return getStatusStringFromValue(((long)mMainStatus) << 32);
    }

    /** @hide */
    public static String getStatusStringFromValue(long val) {
        StringBuilder sb = new StringBuilder();
        switch ((int)(val >> 32)) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS:
                sb.append("always : ");
                sb.append(Long.toHexString(val & 0x00000000FFFFFFFF));
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK:
                sb.append("ask");
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER:
                sb.append("never");
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK:
                sb.append("always-ask");
                break;

            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED:
            default:
                sb.append("undefined");
                break;
        }
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private void readFromParcel(Parcel source) {
        mPackageName = source.readString();
        mMainStatus = source.readInt();
        ArrayList<String> list = new ArrayList<>();
        source.readStringList(list);
        mDomains.addAll(list);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeInt(mMainStatus);
        dest.writeStringList(new ArrayList<>(mDomains));
    }

    public static final @android.annotation.NonNull Creator<IntentFilterVerificationInfo> CREATOR =
            new Creator<IntentFilterVerificationInfo>() {
                public IntentFilterVerificationInfo createFromParcel(Parcel source) {
                    return new IntentFilterVerificationInfo(source);
                }
                public IntentFilterVerificationInfo[] newArray(int size) {
                    return new IntentFilterVerificationInfo[size];
                }
            };
}
