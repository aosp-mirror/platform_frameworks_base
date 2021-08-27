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

package android.nfc.cardemulation;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @hide
 */
public final class NfcFServiceInfo implements Parcelable {
    static final String TAG = "NfcFServiceInfo";

    private static final String DEFAULT_T3T_PMM = "FFFFFFFFFFFFFFFF";

    /**
     * The service that implements this
     */
    final ResolveInfo mService;

    /**
     * Description of the service
     */
    final String mDescription;

    /**
     * System Code of the service
     */
    final String mSystemCode;

    /**
     * System Code of the service registered by API
     */
    String mDynamicSystemCode;

    /**
     * NFCID2 of the service
     */
    final String mNfcid2;

    /**
     * NFCID2 of the service registered by API
     */
    String mDynamicNfcid2;

    /**
     * The uid of the package the service belongs to
     */
    final int mUid;

    /**
     * LF_T3T_PMM of the service
     */
    final String mT3tPmm;

    /**
     * @hide
     */
    public NfcFServiceInfo(ResolveInfo info, String description,
            String systemCode, String dynamicSystemCode, String nfcid2, String dynamicNfcid2,
            int uid, String t3tPmm) {
        this.mService = info;
        this.mDescription = description;
        this.mSystemCode = systemCode;
        this.mDynamicSystemCode = dynamicSystemCode;
        this.mNfcid2 = nfcid2;
        this.mDynamicNfcid2 = dynamicNfcid2;
        this.mUid = uid;
        this.mT3tPmm = t3tPmm;
    }

    public NfcFServiceInfo(PackageManager pm, ResolveInfo info)
            throws XmlPullParserException, IOException {
        ServiceInfo si = info.serviceInfo;
        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, HostNfcFService.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No " + HostNfcFService.SERVICE_META_DATA +
                        " meta-data");
            }

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }

            String tagName = parser.getName();
            if (!"host-nfcf-service".equals(tagName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with <host-nfcf-service> tag");
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.HostNfcFService);
            mService = info;
            mDescription = sa.getString(
                    com.android.internal.R.styleable.HostNfcFService_description);
            mDynamicSystemCode = null;
            mDynamicNfcid2 = null;
            sa.recycle();

            String systemCode = null;
            String nfcid2 = null;
            String t3tPmm = null;
            final int depth = parser.getDepth();

            while (((eventType = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && eventType != XmlPullParser.END_DOCUMENT) {
                tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG &&
                        "system-code-filter".equals(tagName) && systemCode == null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.SystemCodeFilter);
                    systemCode = a.getString(
                            com.android.internal.R.styleable.SystemCodeFilter_name).toUpperCase();
                    if (!NfcFCardEmulation.isValidSystemCode(systemCode) &&
                            !systemCode.equalsIgnoreCase("NULL")) {
                        Log.e(TAG, "Invalid System Code: " + systemCode);
                        systemCode = null;
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG &&
                        "nfcid2-filter".equals(tagName) && nfcid2 == null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.Nfcid2Filter);
                    nfcid2 = a.getString(
                            com.android.internal.R.styleable.Nfcid2Filter_name).toUpperCase();
                    if (!nfcid2.equalsIgnoreCase("RANDOM") &&
                            !nfcid2.equalsIgnoreCase("NULL") &&
                            !NfcFCardEmulation.isValidNfcid2(nfcid2)) {
                        Log.e(TAG, "Invalid NFCID2: " + nfcid2);
                        nfcid2 = null;
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG && tagName.equals("t3tPmm-filter")
                        && t3tPmm == null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.T3tPmmFilter);
                    t3tPmm = a.getString(
                            com.android.internal.R.styleable.T3tPmmFilter_name).toUpperCase();
                    a.recycle();
                }
            }
            mSystemCode = (systemCode == null ? "NULL" : systemCode);
            mNfcid2 = (nfcid2 == null ? "NULL" : nfcid2);
            mT3tPmm = (t3tPmm == null ? DEFAULT_T3T_PMM : t3tPmm);
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
        // Set uid
        mUid = si.applicationInfo.uid;
    }

    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }

    public String getSystemCode() {
        return (mDynamicSystemCode == null ? mSystemCode : mDynamicSystemCode);
    }

    public void setOrReplaceDynamicSystemCode(String systemCode) {
        mDynamicSystemCode = systemCode;
    }

    public String getNfcid2() {
        return (mDynamicNfcid2 == null ? mNfcid2 : mDynamicNfcid2);
    }

    public void setOrReplaceDynamicNfcid2(String nfcid2) {
        mDynamicNfcid2 = nfcid2;
    }

    public String getDescription() {
        return mDescription;
    }

    public int getUid() {
        return mUid;
    }

    public String getT3tPmm() {
        return mT3tPmm;
    }

    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }

    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("NfcFService: ");
        out.append(getComponent());
        out.append(", description: " + mDescription);
        out.append(", System Code: " + mSystemCode);
        if (mDynamicSystemCode != null) {
            out.append(", dynamic System Code: " + mDynamicSystemCode);
        }
        out.append(", NFCID2: " + mNfcid2);
        if (mDynamicNfcid2 != null) {
            out.append(", dynamic NFCID2: " + mDynamicNfcid2);
        }
        out.append(", T3T PMM:" + mT3tPmm);
        return out.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof NfcFServiceInfo)) return false;
        NfcFServiceInfo thatService = (NfcFServiceInfo) o;

        if (!thatService.getComponent().equals(this.getComponent())) return false;
        if (!thatService.mSystemCode.equalsIgnoreCase(this.mSystemCode)) return false;
        if (!thatService.mNfcid2.equalsIgnoreCase(this.mNfcid2)) return false;
        if (!thatService.mT3tPmm.equalsIgnoreCase(this.mT3tPmm)) return false;
        return true;
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
        dest.writeString(mSystemCode);
        dest.writeInt(mDynamicSystemCode != null ? 1 : 0);
        if (mDynamicSystemCode != null) {
            dest.writeString(mDynamicSystemCode);
        }
        dest.writeString(mNfcid2);
        dest.writeInt(mDynamicNfcid2 != null ? 1 : 0);
        if (mDynamicNfcid2 != null) {
            dest.writeString(mDynamicNfcid2);
        }
        dest.writeInt(mUid);
        dest.writeString(mT3tPmm);
    };

    public static final @android.annotation.NonNull Parcelable.Creator<NfcFServiceInfo> CREATOR =
            new Parcelable.Creator<NfcFServiceInfo>() {
        @Override
        public NfcFServiceInfo createFromParcel(Parcel source) {
            ResolveInfo info = ResolveInfo.CREATOR.createFromParcel(source);
            String description = source.readString();
            String systemCode = source.readString();
            String dynamicSystemCode = null;
            if (source.readInt() != 0) {
                dynamicSystemCode = source.readString();
            }
            String nfcid2 = source.readString();
            String dynamicNfcid2 = null;
            if (source.readInt() != 0) {
                dynamicNfcid2 = source.readString();
            }
            int uid = source.readInt();
            String t3tPmm = source.readString();
            NfcFServiceInfo service = new NfcFServiceInfo(info, description,
                    systemCode, dynamicSystemCode, nfcid2, dynamicNfcid2, uid, t3tPmm);
            return service;
        }

        @Override
        public NfcFServiceInfo[] newArray(int size) {
            return new NfcFServiceInfo[size];
        }
    };

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("    " + getComponent() +
                " (Description: " + getDescription() + ")");
        pw.println("    System Code: " + getSystemCode());
        pw.println("    NFCID2: " + getNfcid2());
        pw.println("    T3tPmm: " + getT3tPmm());
    }

    /**
     * Dump debugging info as NfcFServiceInfoProto
     *
     * If the output belongs to a sub message, the caller is responsible for wrapping this function
     * between {@link ProtoOutputStream#start(long)} and {@link ProtoOutputStream#end(long)}.
     *
     * @param proto the ProtoOutputStream to write to
     */
    public void dumpDebug(ProtoOutputStream proto) {
        getComponent().dumpDebug(proto, NfcFServiceInfoProto.COMPONENT_NAME);
        proto.write(NfcFServiceInfoProto.DESCRIPTION, getDescription());
        proto.write(NfcFServiceInfoProto.SYSTEM_CODE, getSystemCode());
        proto.write(NfcFServiceInfoProto.NFCID2, getNfcid2());
        proto.write(NfcFServiceInfoProto.T3T_PMM, getT3tPmm());
    }
}

