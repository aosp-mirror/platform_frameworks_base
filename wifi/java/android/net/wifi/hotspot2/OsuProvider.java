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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.Uri;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Contained information for a Hotspot 2.0 OSU (Online Sign-Up provider).
 *
 * @hide
 */
@SystemApi
public final class OsuProvider implements Parcelable {
    /**
     * OSU (Online Sign-Up) method: OMA DM (Open Mobile Alliance Device Management).
     * For more info, refer to Section 8.3 of the Hotspot 2.0 Release 2 Technical Specification.
     * @hide
     */
    public static final int METHOD_OMA_DM = 0;

    /**
     * OSU (Online Sign-Up) method: SOAP XML SPP (Subscription Provisioning Protocol).
     * For more info, refer to Section 8.4 of the Hotspot 2.0 Release 2 Technical Specification.
     * @hide
     */
    public static final int METHOD_SOAP_XML_SPP = 1;

    /**
     * SSID of the network to connect for service sign-up.
     */
    private WifiSsid mOsuSsid;

    /**
     * Map of friendly names expressed as different language for the OSU provider.
     */
    private final Map<String, String> mFriendlyNames;

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

    /** @hide */
    public OsuProvider(String osuSsid, Map<String, String> friendlyNames,
            String serviceDescription, Uri serverUri, String nai, List<Integer> methodList) {
        this(WifiSsid.createFromByteArray(osuSsid.getBytes(StandardCharsets.UTF_8)),
                friendlyNames, serviceDescription, serverUri, nai, methodList);
    }

    /** @hide */
    public OsuProvider(WifiSsid osuSsid, Map<String, String> friendlyNames,
            String serviceDescription, Uri serverUri, String nai, List<Integer> methodList) {
        mOsuSsid = osuSsid;
        mFriendlyNames = friendlyNames;
        mServiceDescription = serviceDescription;
        mServerUri = serverUri;
        mNetworkAccessIdentifier = nai;
        if (methodList == null) {
            mMethodList = new ArrayList<>();
        } else {
            mMethodList = new ArrayList<>(methodList);
        }
    }

    /**
     * Copy constructor.
     *
     * @param source The source to copy from
     * @hide
     */
    public OsuProvider(OsuProvider source) {
        if (source == null) {
            mOsuSsid = null;
            mFriendlyNames = null;
            mServiceDescription = null;
            mServerUri = null;
            mNetworkAccessIdentifier = null;
            mMethodList = new ArrayList<>();
            return;
        }

        mOsuSsid = source.mOsuSsid;
        mFriendlyNames = source.mFriendlyNames;
        mServiceDescription = source.mServiceDescription;
        mServerUri = source.mServerUri;
        mNetworkAccessIdentifier = source.mNetworkAccessIdentifier;
        if (source.mMethodList == null) {
            mMethodList = new ArrayList<>();
        } else {
            mMethodList = new ArrayList<>(source.mMethodList);
        }
    }

    /** @hide */
    public WifiSsid getOsuSsid() {
        return mOsuSsid;
    }

    /** @hide */
    public void setOsuSsid(WifiSsid osuSsid) {
        mOsuSsid = osuSsid;
    }

    /**
     * Return the friendly Name for current language from the list of friendly names of OSU
     * provider.
     *
     * The string matching the default locale will be returned if it is found, otherwise the string
     * in english or the first string in the list will be returned if english is not found.
     * A null will be returned if the list is empty.
     *
     * @return String matching the default locale, null otherwise
     */
    public @Nullable String getFriendlyName() {
        if (mFriendlyNames == null || mFriendlyNames.isEmpty()) return null;
        String lang = Locale.getDefault().getLanguage();
        String friendlyName = mFriendlyNames.get(lang);
        if (friendlyName != null) {
            return friendlyName;
        }
        friendlyName = mFriendlyNames.get("en");
        if (friendlyName != null) {
            return friendlyName;
        }
        return mFriendlyNames.get(mFriendlyNames.keySet().stream().findFirst().get());
    }

    /** @hide */
    public Map<String, String> getFriendlyNameList() {
        return mFriendlyNames;
    }

    /** @hide */
    public String getServiceDescription() {
        return mServiceDescription;
    }

    public @Nullable Uri getServerUri() {
        return mServerUri;
    }

    /** @hide */
    public String getNetworkAccessIdentifier() {
        return mNetworkAccessIdentifier;
    }

    /** @hide */
    public List<Integer> getMethodList() {
        return mMethodList;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mOsuSsid, flags);
        dest.writeString(mServiceDescription);
        dest.writeParcelable(mServerUri, flags);
        dest.writeString(mNetworkAccessIdentifier);
        dest.writeList(mMethodList);
        Bundle bundle = new Bundle();
        bundle.putSerializable("friendlyNameMap", (HashMap<String, String>) mFriendlyNames);
        dest.writeBundle(bundle);
    }

    @Override
    public boolean equals(@Nullable Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof OsuProvider)) {
            return false;
        }
        OsuProvider that = (OsuProvider) thatObject;
        return Objects.equals(mOsuSsid, that.mOsuSsid)
                && Objects.equals(mFriendlyNames, that.mFriendlyNames)
                && TextUtils.equals(mServiceDescription, that.mServiceDescription)
                && Objects.equals(mServerUri, that.mServerUri)
                && TextUtils.equals(mNetworkAccessIdentifier, that.mNetworkAccessIdentifier)
                && Objects.equals(mMethodList, that.mMethodList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOsuSsid, mServiceDescription, mFriendlyNames,
                mServerUri, mNetworkAccessIdentifier, mMethodList);
    }

    @NonNull
    @Override
    public String toString() {
        return "OsuProvider{mOsuSsid=" + mOsuSsid
                + " mFriendlyNames=" + mFriendlyNames
                + " mServiceDescription=" + mServiceDescription
                + " mServerUri=" + mServerUri
                + " mNetworkAccessIdentifier=" + mNetworkAccessIdentifier
                + " mMethodList=" + mMethodList;
    }

    public static final @android.annotation.NonNull Creator<OsuProvider> CREATOR =
            new Creator<OsuProvider>() {
                @Override
                public OsuProvider createFromParcel(Parcel in) {
                    WifiSsid osuSsid = in.readParcelable(null);
                    String serviceDescription = in.readString();
                    Uri serverUri = in.readParcelable(null);
                    String nai = in.readString();
                    List<Integer> methodList = new ArrayList<>();
                    in.readList(methodList, null);
                    Bundle bundle = in.readBundle();
                    Map<String, String> friendlyNamesMap = (HashMap) bundle.getSerializable(
                            "friendlyNameMap");
                    return new OsuProvider(osuSsid, friendlyNamesMap, serviceDescription,
                            serverUri, nai, methodList);
                }

            @Override
            public OsuProvider[] newArray(int size) {
                return new OsuProvider[size];
            }
        };
}
