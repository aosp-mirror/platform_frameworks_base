/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package android.view.inputmethod;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
import android.util.Printer;
import android.util.Xml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class is used to specify meta information of an input method.
 */
public final class InputMethodInfo implements Parcelable {
    static final String TAG = "InputMethodInfo";

    /**
     * The Service that implements this input method component.
     */
    final ResolveInfo mService;
    
    /**
     * The unique string Id to identify the input method.  This is generated
     * from the input method component.
     */
    final String mId;

    /**
     * The input method setting activity's name, used by the system settings to
     * launch the setting activity of this input method.
     */
    final String mSettingsActivityName;

    /**
     * The resource in the input method's .apk that holds a boolean indicating
     * whether it should be considered the default input method for this
     * system.  This is a resource ID instead of the final value so that it
     * can change based on the configuration (in particular locale).
     */
    final int mIsDefaultResId;

    /**
     * The array of the subtypes.
     */
    private final ArrayList<InputMethodSubtype> mSubtypes = new ArrayList<InputMethodSubtype>();

    /**
     * Constructor.
     *
     * @param context The Context in which we are parsing the input method.
     * @param service The ResolveInfo returned from the package manager about
     * this input method's component.
     */
    public InputMethodInfo(Context context, ResolveInfo service)
            throws XmlPullParserException, IOException {
        this(context, service, null);
    }

    /**
     * Constructor.
     *
     * @param context The Context in which we are parsing the input method.
     * @param service The ResolveInfo returned from the package manager about
     * this input method's component.
     * @param additionalSubtypes additional subtypes being added to this InputMethodInfo
     * @hide
     */
    public InputMethodInfo(Context context, ResolveInfo service,
            Map<String, List<InputMethodSubtype>> additionalSubtypesMap)
            throws XmlPullParserException, IOException {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();

        PackageManager pm = context.getPackageManager();
        String settingsActivityComponent = null;
        int isDefaultResId = 0;

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, InputMethod.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No "
                        + InputMethod.SERVICE_META_DATA + " meta-data");
            }
        
            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            
            AttributeSet attrs = Xml.asAttributeSet(parser);
            
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            
            String nodeName = parser.getName();
            if (!"input-method".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with input-method tag");
            }
            
            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.InputMethod);
            settingsActivityComponent = sa.getString(
                    com.android.internal.R.styleable.InputMethod_settingsActivity);
            isDefaultResId = sa.getResourceId(
                    com.android.internal.R.styleable.InputMethod_isDefault, 0);
            sa.recycle();

            final int depth = parser.getDepth();
            // Parse all subtypes
            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    nodeName = parser.getName();
                    if (!"subtype".equals(nodeName)) {
                        throw new XmlPullParserException(
                                "Meta-data in input-method does not start with subtype tag");
                    }
                    final TypedArray a = res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.InputMethod_Subtype);
                    InputMethodSubtype subtype = new InputMethodSubtype(
                            a.getResourceId(com.android.internal.R.styleable
                                    .InputMethod_Subtype_label, 0),
                            a.getResourceId(com.android.internal.R.styleable
                                    .InputMethod_Subtype_icon, 0),
                            a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeLocale),
                            a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeMode),
                            a.getString(com.android.internal.R.styleable
                                    .InputMethod_Subtype_imeSubtypeExtraValue),
                            a.getBoolean(com.android.internal.R.styleable
                                    .InputMethod_Subtype_isAuxiliary, false),
                            a.getBoolean(com.android.internal.R.styleable
                                    .InputMethod_Subtype_overridesImplicitlyEnabledSubtype, false));
                    mSubtypes.add(subtype);
                }
            }
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException(
                    "Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }

        if (additionalSubtypesMap != null && additionalSubtypesMap.containsKey(mId)) {
            final List<InputMethodSubtype> additionalSubtypes = additionalSubtypesMap.get(mId);
            final int N = additionalSubtypes.size();
            for (int i = 0; i < N; ++i) {
                final InputMethodSubtype subtype = additionalSubtypes.get(i);
                if (!mSubtypes.contains(subtype)) {
                    mSubtypes.add(subtype);
                }
            }
        }
        mSettingsActivityName = settingsActivityComponent;
        mIsDefaultResId = isDefaultResId;
    }

    InputMethodInfo(Parcel source) {
        mId = source.readString();
        mSettingsActivityName = source.readString();
        mIsDefaultResId = source.readInt();
        mService = ResolveInfo.CREATOR.createFromParcel(source);
        source.readTypedList(mSubtypes, InputMethodSubtype.CREATOR);
    }

    /**
     * Temporary API for creating a built-in input method.
     */
    public InputMethodInfo(String packageName, String className,
            CharSequence label, String settingsActivity) {
        ResolveInfo ri = new ResolveInfo();
        ServiceInfo si = new ServiceInfo();
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = className;
        si.exported = true;
        si.nonLocalizedLabel = label;
        ri.serviceInfo = si;
        mService = ri;
        mId = new ComponentName(si.packageName, si.name).flattenToShortString();
        mSettingsActivityName = settingsActivity;
        mIsDefaultResId = 0;
    }

    /**
     * Return a unique ID for this input method.  The ID is generated from
     * the package and class name implementing the method.
     */
    public String getId() {
        return mId;
    }

    /**
     * Return the .apk package that implements this input method.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
    }

    /**
     * Return the class name of the service component that implements
     * this input method.
     */
    public String getServiceName() {
        return mService.serviceInfo.name;
    }

    /**
     * Return the raw information about the Service implementing this
     * input method.  Do not modify the returned object.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Return the component of the service that implements this input
     * method.
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }

    /**
     * Load the user-displayed label for this input method.
     * 
     * @param pm Supply a PackageManager used to load the input method's
     * resources.
     */
    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }

    /**
     * Load the user-displayed icon for this input method.
     * 
     * @param pm Supply a PackageManager used to load the input method's
     * resources.
     */
    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }

    /**
     * Return the class name of an activity that provides a settings UI for
     * the input method.  You can launch this activity be starting it with
     * an {@link android.content.Intent} whose action is MAIN and with an
     * explicit {@link android.content.ComponentName}
     * composed of {@link #getPackageName} and the class name returned here.
     * 
     * <p>A null will be returned if there is no settings activity associated
     * with the input method.
     */
    public String getSettingsActivity() {
        return mSettingsActivityName;
    }

    /**
     * Return the count of the subtypes of Input Method.
     */
    public int getSubtypeCount() {
        return mSubtypes.size();
    }

    /**
     * Return the Input Method's subtype at the specified index.
     *
     * @param index the index of the subtype to return.
     */
    public InputMethodSubtype getSubtypeAt(int index) {
        return mSubtypes.get(index);
    }

    /**
     * Return the resource identifier of a resource inside of this input
     * method's .apk that determines whether it should be considered a
     * default input method for the system.
     */
    public int getIsDefaultResourceId() {
        return mIsDefaultResId;
    }

    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "mId=" + mId
                + " mSettingsActivityName=" + mSettingsActivityName);
        pw.println(prefix + "mIsDefaultResId=0x"
                + Integer.toHexString(mIsDefaultResId));
        pw.println(prefix + "Service:");
        mService.dump(pw, prefix + "  ");
    }
    
    @Override
    public String toString() {
        return "InputMethodInfo{" + mId
                + ", settings: "
                + mSettingsActivityName + "}";
    }

    /**
     * Used to test whether the given parameter object is an
     * {@link InputMethodInfo} and its Id is the same to this one.
     * 
     * @return true if the given parameter object is an
     *         {@link InputMethodInfo} and its Id is the same to this one.
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;

        if (!(o instanceof InputMethodInfo)) return false;

        InputMethodInfo obj = (InputMethodInfo) o;
        return mId.equals(obj.mId);
    }

    @Override
    public int hashCode() {
        return mId.hashCode();
    }

    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mSettingsActivityName);
        dest.writeInt(mIsDefaultResId);
        mService.writeToParcel(dest, flags);
        dest.writeTypedList(mSubtypes);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<InputMethodInfo> CREATOR
            = new Parcelable.Creator<InputMethodInfo>() {
        public InputMethodInfo createFromParcel(Parcel source) {
            return new InputMethodInfo(source);
        }

        public InputMethodInfo[] newArray(int size) {
            return new InputMethodInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
