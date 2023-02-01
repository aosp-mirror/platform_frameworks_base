/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.textservice;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * This class is used to specify meta information of a spell checker.
 */
public final class SpellCheckerInfo implements Parcelable {
    private static final String TAG = SpellCheckerInfo.class.getSimpleName();
    private final ResolveInfo mService;
    private final String mId;
    private final int mLabel;

    /**
     * The spell checker setting activity's name, used by the system settings to
     * launch the setting activity.
     */
    private final String mSettingsActivityName;

    /**
     * The array of subtypes.
     */
    private final ArrayList<SpellCheckerSubtype> mSubtypes = new ArrayList<>();

    /**
     * Constructor.
     * @hide
     */
    public SpellCheckerInfo(Context context, ResolveInfo service)
            throws XmlPullParserException, IOException {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();

        final PackageManager pm = context.getPackageManager();
        int label = 0;
        String settingsActivityComponent = null;

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, SpellCheckerSession.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No "
                        + SpellCheckerSession.SERVICE_META_DATA + " meta-data");
            }

            final Resources res = pm.getResourcesForApplication(si.applicationInfo);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            final String nodeName = parser.getName();
            if (!"spell-checker".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with spell-checker tag");
            }

            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.SpellChecker);
            label = sa.getResourceId(com.android.internal.R.styleable.SpellChecker_label, 0);
            settingsActivityComponent = sa.getString(
                    com.android.internal.R.styleable.SpellChecker_settingsActivity);
            sa.recycle();

            final int depth = parser.getDepth();
            // Parse all subtypes
            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String subtypeNodeName = parser.getName();
                    if (!"subtype".equals(subtypeNodeName)) {
                        throw new XmlPullParserException(
                                "Meta-data in spell-checker does not start with subtype tag");
                    }
                    final TypedArray a = res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.SpellChecker_Subtype);
                    SpellCheckerSubtype subtype = new SpellCheckerSubtype(
                            a.getResourceId(com.android.internal.R.styleable
                                    .SpellChecker_Subtype_label, 0),
                            a.getString(com.android.internal.R.styleable
                                    .SpellChecker_Subtype_subtypeLocale),
                            a.getString(com.android.internal.R.styleable
                                    .SpellChecker_Subtype_languageTag),
                            a.getString(com.android.internal.R.styleable
                                    .SpellChecker_Subtype_subtypeExtraValue),
                            a.getInt(com.android.internal.R.styleable
                                    .SpellChecker_Subtype_subtypeId, 0));
                    a.recycle();
                    mSubtypes.add(subtype);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Caught exception: " + e);
            throw new XmlPullParserException(
                    "Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
        mLabel = label;
        mSettingsActivityName = settingsActivityComponent;
    }

    /**
     * Constructor.
     * @hide
     */
    public SpellCheckerInfo(Parcel source) {
        mLabel = source.readInt();
        mId = source.readString();
        mSettingsActivityName = source.readString();
        mService = ResolveInfo.CREATOR.createFromParcel(source);
        source.readTypedList(mSubtypes, SpellCheckerSubtype.CREATOR);
    }

    /**
     * Return a unique ID for this spell checker.  The ID is generated from
     * the package and class name implementing the method.
     */
    public String getId() {
        return mId;
    }

    /**
     * Return the component of the service that implements.
     */
    public ComponentName getComponent() {
        return new ComponentName(
                mService.serviceInfo.packageName, mService.serviceInfo.name);
    }

    /**
     * Return the .apk package that implements this.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mLabel);
        dest.writeString(mId);
        dest.writeString(mSettingsActivityName);
        mService.writeToParcel(dest, flags);
        dest.writeTypedList(mSubtypes);
    }


    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<SpellCheckerInfo> CREATOR
            = new Parcelable.Creator<SpellCheckerInfo>() {
        @Override
        public SpellCheckerInfo createFromParcel(Parcel source) {
            return new SpellCheckerInfo(source);
        }

        @Override
        public SpellCheckerInfo[] newArray(int size) {
            return new SpellCheckerInfo[size];
        }
    };

    /**
     * Load the user-displayed label for this spell checker.
     *
     * @param pm Supply a PackageManager used to load the spell checker's resources.
     */
    public CharSequence loadLabel(PackageManager pm) {
        if (mLabel == 0 || pm == null) return "";
        return pm.getText(getPackageName(), mLabel, mService.serviceInfo.applicationInfo);
    }

    /**
     * Load the user-displayed icon for this spell checker.
     *
     * @param pm Supply a PackageManager used to load the spell checker's resources.
     */
    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }


    /**
     * Return the raw information about the Service implementing this
     * spell checker.  Do not modify the returned object.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Return the class name of an activity that provides a settings UI.
     * You can launch this activity be starting it with
     * an {@link android.content.Intent} whose action is MAIN and with an
     * explicit {@link android.content.ComponentName}
     * composed of {@link #getPackageName} and the class name returned here.
     *
     * <p>A null will be returned if there is no settings activity.
     */
    public String getSettingsActivity() {
        return mSettingsActivityName;
    }

    /**
     * Return the count of the subtypes.
     */
    public int getSubtypeCount() {
        return mSubtypes.size();
    }

    /**
     * Return the subtype at the specified index.
     *
     * @param index the index of the subtype to return.
     */
    public SpellCheckerSubtype getSubtypeAt(int index) {
        return mSubtypes.get(index);
    }

    /**
     * Used to make this class parcelable.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public void dump(final PrintWriter pw, final String prefix) {
        pw.println(prefix + "mId=" + mId);
        pw.println(prefix + "mSettingsActivityName=" + mSettingsActivityName);
        pw.println(prefix + "Service:");
        mService.dump(new PrintWriterPrinter(pw), prefix + "  ");
        final int N = getSubtypeCount();
        for (int i = 0; i < N; i++) {
            final SpellCheckerSubtype st = getSubtypeAt(i);
            pw.println(prefix + "  " + "Subtype #" + i + ":");
            pw.println(prefix + "    " + "locale=" + st.getLocale()
                    + " languageTag=" + st.getLanguageTag());
            pw.println(prefix + "    " + "extraValue=" + st.getExtraValue());
        }
    }
}
