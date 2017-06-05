/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2;

import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.wifi.WifiSsid;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Contained information for a Hotspot 2.0 OSU (Online Sign-Up provider).
 *
 * @hide
 */
public final class OsuProvider implements Parcelable {
    /**
     * OSU (Online Sign-Up) method: OMA DM (Open Mobile Alliance Device Management).
     * For more info, refer to Section 8.3 of the Hotspot 2.0 Release 2 Technical Specification.
     */
    public static final int METHOD_OMA_DM = 0;

    /**
     * OSU (Online Sign-Up) method: SOAP XML SPP (Subscription Provisioning Protocol).
     * For more info, refer to Section 8.4 of the Hotspot 2.0 Release 2 Technical Specification.
     */
    public static final int METHOD_SOAP_XML_SPP = 1;

    /**
     * SSID of the network to connect for service sign-up.
     */
    private final WifiSsid mOsuSsid;

    /**
     * Friendly name of the OSU provider.
     */
    private final String mFriendlyName;

    /**
     * Description of the OSU provider.
     */
    private final String mServiceDescription;

    /**
     * URI to browse to for service sign-up.
     */
    private final Uri mServerUri;

    /**
     * Network Access Identifier used for authenticating with the OSU network when OSEN is used.
     */
    private final String mNetworkAccessIdentifier;

    /**
     * List of OSU (Online Sign-Up) method supported.
     */
    private final List<Integer> mMethodList;

    /**
     * Icon data for the OSU (Online Sign-Up) provider.
     */
    private final Icon mIcon;

    public OsuProvider(WifiSsid osuSsid, String friendlyName, String serviceDescription,
            Uri serverUri, String nai, List<Integer> methodList, Icon icon) {
        mOsuSsid = osuSsid;
        mFriendlyName = friendlyName;
        mServiceDescription = serviceDescription;
        mServerUri = serverUri;
        mNetworkAccessIdentifier = nai;
        if (methodList == null) {
            mMethodList = new ArrayList<>();
        } else {
            mMethodList = new ArrayList<>(methodList);
        }
        mIcon = icon;
    }

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     */
    public OsuProvider(OsuProvider source) {
        if (source == null) {
            mOsuSsid = null;
            mFriendlyName = null;
            mServiceDescription = null;
            mServerUri = null;
            mNetworkAccessIdentifier = null;
            mMethodList = new ArrayList<>();
            mIcon = null;
            return;
        }

        mOsuSsid = source.mOsuSsid;
        mFriendlyName = source.mFriendlyName;
        mServiceDescription = source.mServiceDescription;
        mServerUri = source.mServerUri;
        mNetworkAccessIdentifier = source.mNetworkAccessIdentifier;
        if (source.mMethodList == null) {
            mMethodList = new ArrayList<>();
        } else {
            mMethodList = new ArrayList<>(source.mMethodList);
        }
        mIcon = source.mIcon;
    }

    public WifiSsid getOsuSsid() {
        return mOsuSsid;
    }

    public String getFriendlyName() {
        return mFriendlyName;
    }

    public String getServiceDescription() {
        return mServiceDescription;
    }

    public Uri getServerUri() {
        return mServerUri;
    }

    public String getNetworkAccessIdentifier() {
        return mNetworkAccessIdentifier;
    }

    public List<Integer> getMethodList() {
        return Collections.unmodifiableList(mMethodList);
    }

    public Icon getIcon() {
        return mIcon;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mOsuSsid, flags);
        dest.writeString(mFriendlyName);
        dest.writeString(mServiceDescription);
        dest.writeParcelable(mServerUri, flags);
        dest.writeString(mNetworkAccessIdentifier);
        dest.writeList(mMethodList);
        dest.writeParcelable(mIcon, flags);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof OsuProvider)) {
            return false;
        }
        OsuProvider that = (OsuProvider) thatObject;
        return (mOsuSsid == null ? that.mOsuSsid == null : mOsuSsid.equals(that.mOsuSsid))
                && TextUtils.equals(mFriendlyName, that.mFriendlyName)
                && TextUtils.equals(mServiceDescription, that.mServiceDescription)
                && (mServerUri == null ? that.mServerUri == null
                            : mServerUri.equals(that.mServerUri))
                && TextUtils.equals(mNetworkAccessIdentifier, that.mNetworkAccessIdentifier)
                && (mMethodList == null ? that.mMethodList == null
                            : mMethodList.equals(that.mMethodList))
                && (mIcon == null ? that.mIcon == null : mIcon.sameAs(that.mIcon));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOsuSsid, mFriendlyName, mServiceDescription, mServerUri,
                mNetworkAccessIdentifier, mMethodList, mIcon);
    }

    @Override
    public String toString() {
        return "OsuProvider{mOsuSsid=" + mOsuSsid
                + " mFriendlyName=" + mFriendlyName
                + " mServiceDescription=" + mServiceDescription
                + " mServerUri=" + mServerUri
                + " mNetworkAccessIdentifier=" + mNetworkAccessIdentifier
                + " mMethodList=" + mMethodList
                + " mIcon=" + mIcon;
    }

    public static final Creator<OsuProvider> CREATOR =
        new Creator<OsuProvider>() {
            @Override
            public OsuProvider createFromParcel(Parcel in) {
                WifiSsid osuSsid = (WifiSsid) in.readParcelable(null);
                String friendlyName = in.readString();
                String serviceDescription = in.readString();
                Uri serverUri = (Uri) in.readParcelable(null);
                String nai = in.readString();
                List<Integer> methodList = new ArrayList<>();
                in.readList(methodList, null);
                Icon icon = (Icon) in.readParcelable(null);
                return new OsuProvider(osuSsid, friendlyName, serviceDescription, serverUri,
                        nai, methodList, icon);
            }

            @Override
            public OsuProvider[] newArray(int size) {
                return new OsuProvider[size];
            }
        };
}
