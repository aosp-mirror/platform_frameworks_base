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

package android.nfc.cardemulation;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * @hide
 */
public final class ApduServiceInfo implements Parcelable {
    static final String TAG = "ApduServiceInfo";

    /**
     * The service that implements this
     */
    final ResolveInfo mService;

    /**
     * Description of the service
     */
    final String mDescription;

    /**
     * Convenience AID list
     */
    final ArrayList<String> mAids;

    /**
     * Whether this service represents AIDs running on the host CPU
     */
    final boolean mOnHost;

    /**
     * All AID groups this service handles
     */
    final ArrayList<AidGroup> mAidGroups;

    /**
     * Convenience hashmap
     */
    final HashMap<String, AidGroup> mCategoryToGroup;

    /**
     * Whether this service should only be started when the device is unlocked.
     */
    final boolean mRequiresDeviceUnlock;

    /**
     * The id of the service banner specified in XML.
     */
    final int mBannerResourceId;

    /**
     * @hide
     */
    public ApduServiceInfo(ResolveInfo info, boolean onHost, String description,
            ArrayList<AidGroup> aidGroups, boolean requiresUnlock, int bannerResource) {
        this.mService = info;
        this.mDescription = description;
        this.mAidGroups = aidGroups;
        this.mAids = new ArrayList<String>();
        this.mCategoryToGroup = new HashMap<String, AidGroup>();
        this.mOnHost = onHost;
        this.mRequiresDeviceUnlock = requiresUnlock;
        for (AidGroup aidGroup : aidGroups) {
            this.mCategoryToGroup.put(aidGroup.category, aidGroup);
            this.mAids.addAll(aidGroup.aids);
        }
        this.mBannerResourceId = bannerResource;
    }

    public ApduServiceInfo(PackageManager pm, ResolveInfo info, boolean onHost)
            throws XmlPullParserException, IOException {
        ServiceInfo si = info.serviceInfo;
        XmlResourceParser parser = null;
        try {
            if (onHost) {
                parser = si.loadXmlMetaData(pm, HostApduService.SERVICE_META_DATA);
                if (parser == null) {
                    throw new XmlPullParserException("No " + HostApduService.SERVICE_META_DATA +
                            " meta-data");
                }
            } else {
                parser = si.loadXmlMetaData(pm, OffHostApduService.SERVICE_META_DATA);
                if (parser == null) {
                    throw new XmlPullParserException("No " + OffHostApduService.SERVICE_META_DATA +
                            " meta-data");
                }
            }

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }

            String tagName = parser.getName();
            if (onHost && !"host-apdu-service".equals(tagName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with <host-apdu-service> tag");
            } else if (!onHost && !"offhost-apdu-service".equals(tagName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with <offhost-apdu-service> tag");
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            if (onHost) {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.HostApduService);
                mService = info;
                mDescription = sa.getString(
                        com.android.internal.R.styleable.HostApduService_description);
                mRequiresDeviceUnlock = sa.getBoolean(
                        com.android.internal.R.styleable.HostApduService_requireDeviceUnlock,
                        false);
                mBannerResourceId = sa.getResourceId(
                        com.android.internal.R.styleable.HostApduService_apduServiceBanner, -1);
                sa.recycle();
            } else {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.OffHostApduService);
                mService = info;
                mDescription = sa.getString(
                        com.android.internal.R.styleable.OffHostApduService_description);
                mRequiresDeviceUnlock = false;
                mBannerResourceId = sa.getResourceId(
                        com.android.internal.R.styleable.OffHostApduService_apduServiceBanner, -1);
                sa.recycle();
            }

            mAidGroups = new ArrayList<AidGroup>();
            mCategoryToGroup = new HashMap<String, AidGroup>();
            mAids = new ArrayList<String>();
            mOnHost = onHost;
            final int depth = parser.getDepth();
            AidGroup currentGroup = null;

            // Parsed values for the current AID group
            while (((eventType = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && eventType != XmlPullParser.END_DOCUMENT) {
                tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG && "aid-group".equals(tagName) &&
                        currentGroup == null) {
                    final TypedArray groupAttrs = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidGroup);
                    // Get category of AID group
                    String groupDescription = groupAttrs.getString(
                            com.android.internal.R.styleable.AidGroup_description);
                    String groupCategory = groupAttrs.getString(
                            com.android.internal.R.styleable.AidGroup_category);
                    if (!CardEmulation.CATEGORY_PAYMENT.equals(groupCategory)) {
                        groupCategory = CardEmulation.CATEGORY_OTHER;
                    }
                    currentGroup = mCategoryToGroup.get(groupCategory);
                    if (currentGroup != null) {
                        if (!CardEmulation.CATEGORY_OTHER.equals(groupCategory)) {
                            Log.e(TAG, "Not allowing multiple aid-groups in the " +
                                    groupCategory + " category");
                            currentGroup = null;
                        }
                    } else {
                        currentGroup = new AidGroup(groupCategory, groupDescription);
                    }
                    groupAttrs.recycle();
                } else if (eventType == XmlPullParser.END_TAG && "aid-group".equals(tagName) &&
                        currentGroup != null) {
                    if (currentGroup.aids.size() > 0) {
                        if (!mCategoryToGroup.containsKey(currentGroup.category)) {
                            mAidGroups.add(currentGroup);
                            mCategoryToGroup.put(currentGroup.category, currentGroup);
                        }
                    } else {
                        Log.e(TAG, "Not adding <aid-group> with empty or invalid AIDs");
                    }
                    currentGroup = null;
                } else if (eventType == XmlPullParser.START_TAG && "aid-filter".equals(tagName) &&
                        currentGroup != null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidFilter);
                    String aid = a.getString(com.android.internal.R.styleable.AidFilter_name).
                            toUpperCase();
                    if (isValidAid(aid) && !currentGroup.aids.contains(aid)) {
                        currentGroup.aids.add(aid);
                        mAids.add(aid);
                    } else {
                        Log.e(TAG, "Ignoring invalid or duplicate aid: " + aid);
                    }
                    a.recycle();
                }
            }
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
    }

    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }

    public ArrayList<String> getAids() {
        return mAids;
    }

    public ArrayList<AidGroup> getAidGroups() {
        return mAidGroups;
    }

    public boolean hasCategory(String category) {
        return mCategoryToGroup.containsKey(category);
    }

    public boolean isOnHost() {
        return mOnHost;
    }

    public boolean requiresUnlock() {
        return mRequiresDeviceUnlock;
    }

    public String getDescription() {
        return mDescription;
    }

    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }

    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }

    public Drawable loadBanner(PackageManager pm) {
        Resources res;
        try {
            res = pm.getResourcesForApplication(mService.serviceInfo.packageName);
            Drawable banner = res.getDrawable(mBannerResourceId);
            return banner;
        } catch (NotFoundException e) {
            Log.e(TAG, "Could not load banner.");
            return null;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not load banner.");
            return null;
        }
    }

    static boolean isValidAid(String aid) {
        if (aid == null)
            return false;

        int aidLength = aid.length();
        if (aidLength == 0 || (aidLength % 2) != 0) {
            Log.e(TAG, "AID " + aid + " is not correctly formatted.");
            return false;
        }
        // Minimum AID length is 5 bytes, 10 hex chars
        if (aidLength < 10) {
            Log.e(TAG, "AID " + aid + " is shorter than 5 bytes.");
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ApduService: ");
        out.append(getComponent());
        out.append(", description: " + mDescription);
        out.append(", AID Groups: ");
        for (AidGroup aidGroup : mAidGroups) {
            out.append(aidGroup.toString());
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApduServiceInfo)) return false;
        ApduServiceInfo thatService = (ApduServiceInfo) o;

        return thatService.getComponent().equals(this.getComponent());
    }

    @Override
    public int hashCode() {
        return getComponent().hashCode();
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mDescription);
        dest.writeInt(mOnHost ? 1 : 0);
        dest.writeInt(mAidGroups.size());
        if (mAidGroups.size() > 0) {
            dest.writeTypedList(mAidGroups);
        }
        dest.writeInt(mRequiresDeviceUnlock ? 1 : 0);
        dest.writeInt(mBannerResourceId);
    };

    public static final Parcelable.Creator<ApduServiceInfo> CREATOR =
            new Parcelable.Creator<ApduServiceInfo>() {
        @Override
        public ApduServiceInfo createFromParcel(Parcel source) {
            ResolveInfo info = ResolveInfo.CREATOR.createFromParcel(source);
            String description = source.readString();
            boolean onHost = (source.readInt() != 0) ? true : false;
            ArrayList<AidGroup> aidGroups = new ArrayList<AidGroup>();
            int numGroups = source.readInt();
            if (numGroups > 0) {
                source.readTypedList(aidGroups, AidGroup.CREATOR);
            }
            boolean requiresUnlock = (source.readInt() != 0) ? true : false;
            int bannerResource = source.readInt();
            return new ApduServiceInfo(info, onHost, description, aidGroups, requiresUnlock, bannerResource);
        }

        @Override
        public ApduServiceInfo[] newArray(int size) {
            return new ApduServiceInfo[size];
        }
    };

    public static class AidGroup implements Parcelable {
        final ArrayList<String> aids;
        final String category;
        final String description;

        AidGroup(ArrayList<String> aids, String category, String description) {
            this.aids = aids;
            this.category = category;
            this.description = description;
        }

        AidGroup(String category, String description) {
            this.aids = new ArrayList<String>();
            this.category = category;
            this.description = description;
        }

        public String getCategory() {
            return category;
        }

        public ArrayList<String> getAids() {
            return aids;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("Category: " + category +
                      ", description: " + description + ", AIDs:");
            for (String aid : aids) {
                out.append(aid);
                out.append(", ");
            }
            return out.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(category);
            dest.writeString(description);
            dest.writeInt(aids.size());
            if (aids.size() > 0) {
                dest.writeStringList(aids);
            }
        }

        public static final Parcelable.Creator<ApduServiceInfo.AidGroup> CREATOR =
                new Parcelable.Creator<ApduServiceInfo.AidGroup>() {

            @Override
            public AidGroup createFromParcel(Parcel source) {
                String category = source.readString();
                String description = source.readString();
                int listSize = source.readInt();
                ArrayList<String> aidList = new ArrayList<String>();
                if (listSize > 0) {
                    source.readStringList(aidList);
                }
                return new AidGroup(aidList, category, description);
            }

            @Override
            public AidGroup[] newArray(int size) {
                return new AidGroup[size];
            }
        };
    }
}
