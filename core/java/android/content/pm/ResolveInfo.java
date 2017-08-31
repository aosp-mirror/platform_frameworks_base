/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Printer;
import android.util.Slog;

import java.text.Collator;
import java.util.Comparator;

/**
 * Information that is returned from resolving an intent
 * against an IntentFilter. This partially corresponds to
 * information collected from the AndroidManifest.xml's
 * &lt;intent&gt; tags.
 */
public class ResolveInfo implements Parcelable {
    private static final String TAG = "ResolveInfo";

    /**
     * The activity or broadcast receiver that corresponds to this resolution
     * match, if this resolution is for an activity or broadcast receiver.
     * Exactly one of {@link #activityInfo}, {@link #serviceInfo}, or
     * {@link #providerInfo} will be non-null.
     */
    public ActivityInfo activityInfo;

    /**
     * The service that corresponds to this resolution match, if this resolution
     * is for a service. Exactly one of {@link #activityInfo},
     * {@link #serviceInfo}, or {@link #providerInfo} will be non-null.
     */
    public ServiceInfo serviceInfo;

    /**
     * The provider that corresponds to this resolution match, if this
     * resolution is for a provider. Exactly one of {@link #activityInfo},
     * {@link #serviceInfo}, or {@link #providerInfo} will be non-null.
     */
    public ProviderInfo providerInfo;

    /**
     * An auxiliary response that may modify the resolved information. This is
     * only set under certain circumstances; such as when resolving instant apps
     * or components defined in un-installed splits.
     * @hide
     */
    public AuxiliaryResolveInfo auxiliaryInfo;

    /**
     * Whether or not an instant app is available for the resolved intent.
     */
    public boolean isInstantAppAvailable;

    /** @removed */
    @Deprecated
    public boolean instantAppAvailable;

    /**
     * The IntentFilter that was matched for this ResolveInfo.
     */
    public IntentFilter filter;

    /**
     * The declared priority of this match.  Comes from the "priority"
     * attribute or, if not set, defaults to 0.  Higher values are a higher
     * priority.
     */
    public int priority;

    /**
     * Order of result according to the user's preference.  If the user
     * has not set a preference for this result, the value is 0; higher
     * values are a higher priority.
     */
    public int preferredOrder;

    /**
     * The system's evaluation of how well the activity matches the
     * IntentFilter.  This is a match constant, a combination of
     * {@link IntentFilter#MATCH_CATEGORY_MASK IntentFilter.MATCH_CATEGORY_MASK}
     * and {@link IntentFilter#MATCH_ADJUSTMENT_MASK IntentFiler.MATCH_ADJUSTMENT_MASK}.
     */
    public int match;

    /**
     * Only set when returned by
     * {@link PackageManager#queryIntentActivityOptions}, this tells you
     * which of the given specific intents this result came from.  0 is the
     * first in the list, < 0 means it came from the generic Intent query.
     */
    public int specificIndex = -1;

    /**
     * This filter has specified the Intent.CATEGORY_DEFAULT, meaning it
     * would like to be considered a default action that the user can
     * perform on this data.
     */
    public boolean isDefault;

    /**
     * A string resource identifier (in the package's resources) of this
     * match's label.  From the "label" attribute or, if not set, 0.
     */
    public int labelRes;

    /**
     * The actual string retrieve from <var>labelRes</var> or null if none
     * was provided.
     */
    public CharSequence nonLocalizedLabel;

    /**
     * A drawable resource identifier (in the package's resources) of this
     * match's icon.  From the "icon" attribute or, if not set, 0. It is
     * set only if the icon can be obtained by resource id alone.
     */
    public int icon;

    /**
     * Optional -- if non-null, the {@link #labelRes} and {@link #icon}
     * resources will be loaded from this package, rather than the one
     * containing the resolved component.
     */
    public String resolvePackageName;

    /**
     * If not equal to UserHandle.USER_CURRENT, then the intent will be forwarded to this user.
     * @hide
     */
    public int targetUserId;

    /**
     * Set to true if the icon cannot be obtained by resource ids alone.
     * It is set to true for ResolveInfos from the managed profile: They need to
     * have their icon badged, so it cannot be obtained by resource ids alone.
     * @hide
     */
    public boolean noResourceId;

    /**
     * Same as {@link #icon} but it will always correspond to "icon" attribute
     * regardless of {@link #noResourceId} value.
     * @hide
     */
    public int iconResourceId;

    /**
     * @hide Target comes from system process?
     */
    public boolean system;

    /**
     * @hide Does the associated IntentFilter comes from a Browser ?
     */
    public boolean handleAllWebDataURI;

    /** {@hide} */
    public ComponentInfo getComponentInfo() {
        if (activityInfo != null) return activityInfo;
        if (serviceInfo != null) return serviceInfo;
        if (providerInfo != null) return providerInfo;
        throw new IllegalStateException("Missing ComponentInfo!");
    }

    /**
     * Retrieve the current textual label associated with this resolution.  This
     * will call back on the given PackageManager to load the label from
     * the application.
     *
     * @param pm A PackageManager from which the label can be loaded; usually
     * the PackageManager from which you originally retrieved this item.
     *
     * @return Returns a CharSequence containing the resolutions's label.  If the
     * item does not have a label, its name is returned.
     */
    public CharSequence loadLabel(PackageManager pm) {
        if (nonLocalizedLabel != null) {
            return nonLocalizedLabel;
        }
        CharSequence label;
        if (resolvePackageName != null && labelRes != 0) {
            label = pm.getText(resolvePackageName, labelRes, null);
            if (label != null) {
                return label.toString().trim();
            }
        }
        ComponentInfo ci = getComponentInfo();
        ApplicationInfo ai = ci.applicationInfo;
        if (labelRes != 0) {
            label = pm.getText(ci.packageName, labelRes, ai);
            if (label != null) {
                return label.toString().trim();
            }
        }

        CharSequence data = ci.loadLabel(pm);
        // Make the data safe
        if (data != null) data = data.toString().trim();
        return data;
    }

    /**
     * Retrieve the current graphical icon associated with this resolution.  This
     * will call back on the given PackageManager to load the icon from
     * the application.
     *
     * @param pm A PackageManager from which the icon can be loaded; usually
     * the PackageManager from which you originally retrieved this item.
     *
     * @return Returns a Drawable containing the resolution's icon.  If the
     * item does not have an icon, the default activity icon is returned.
     */
    public Drawable loadIcon(PackageManager pm) {
        Drawable dr = null;
        if (resolvePackageName != null && iconResourceId != 0) {
            dr = pm.getDrawable(resolvePackageName, iconResourceId, null);
        }
        ComponentInfo ci = getComponentInfo();
        if (dr == null && iconResourceId != 0) {
            ApplicationInfo ai = ci.applicationInfo;
            dr = pm.getDrawable(ci.packageName, iconResourceId, ai);
        }
        if (dr != null) {
            return pm.getUserBadgedIcon(dr, new UserHandle(UserHandle.myUserId()));
        }
        return ci.loadIcon(pm);
    }

    /**
     * Return the icon resource identifier to use for this match.  If the
     * match defines an icon, that is used; else if the activity defines
     * an icon, that is used; else, the application icon is used.
     * This function does not check noResourceId flag.
     *
     * @return The icon associated with this match.
     */
    final int getIconResourceInternal() {
        if (iconResourceId != 0) return iconResourceId;
        final ComponentInfo ci = getComponentInfo();
        if (ci != null) {
            return ci.getIconResource();
        }
        return 0;
    }

    /**
     * Return the icon resource identifier to use for this match.  If the
     * match defines an icon, that is used; else if the activity defines
     * an icon, that is used; else, the application icon is used.
     *
     * @return The icon associated with this match.
     */
    public final int getIconResource() {
        if (noResourceId) return 0;
        return getIconResourceInternal();
    }

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, PackageItemInfo.DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int dumpFlags) {
        if (filter != null) {
            pw.println(prefix + "Filter:");
            filter.dump(pw, prefix + "  ");
        }
        pw.println(prefix + "priority=" + priority
                + " preferredOrder=" + preferredOrder
                + " match=0x" + Integer.toHexString(match)
                + " specificIndex=" + specificIndex
                + " isDefault=" + isDefault);
        if (resolvePackageName != null) {
            pw.println(prefix + "resolvePackageName=" + resolvePackageName);
        }
        if (labelRes != 0 || nonLocalizedLabel != null || icon != 0) {
            pw.println(prefix + "labelRes=0x" + Integer.toHexString(labelRes)
                    + " nonLocalizedLabel=" + nonLocalizedLabel
                    + " icon=0x" + Integer.toHexString(icon));
        }
        if (activityInfo != null) {
            pw.println(prefix + "ActivityInfo:");
            activityInfo.dump(pw, prefix + "  ", dumpFlags);
        } else if (serviceInfo != null) {
            pw.println(prefix + "ServiceInfo:");
            serviceInfo.dump(pw, prefix + "  ", dumpFlags);
        } else if (providerInfo != null) {
            pw.println(prefix + "ProviderInfo:");
            providerInfo.dump(pw, prefix + "  ", dumpFlags);
        }
    }

    public ResolveInfo() {
        targetUserId = UserHandle.USER_CURRENT;
    }

    public ResolveInfo(ResolveInfo orig) {
        activityInfo = orig.activityInfo;
        serviceInfo = orig.serviceInfo;
        providerInfo = orig.providerInfo;
        filter = orig.filter;
        priority = orig.priority;
        preferredOrder = orig.preferredOrder;
        match = orig.match;
        specificIndex = orig.specificIndex;
        labelRes = orig.labelRes;
        nonLocalizedLabel = orig.nonLocalizedLabel;
        icon = orig.icon;
        resolvePackageName = orig.resolvePackageName;
        noResourceId = orig.noResourceId;
        iconResourceId = orig.iconResourceId;
        system = orig.system;
        targetUserId = orig.targetUserId;
        handleAllWebDataURI = orig.handleAllWebDataURI;
        isInstantAppAvailable = orig.isInstantAppAvailable;
        instantAppAvailable = isInstantAppAvailable;
    }

    public String toString() {
        final ComponentInfo ci = getComponentInfo();
        StringBuilder sb = new StringBuilder(128);
        sb.append("ResolveInfo{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        ComponentName.appendShortString(sb, ci.packageName, ci.name);
        if (priority != 0) {
            sb.append(" p=");
            sb.append(priority);
        }
        if (preferredOrder != 0) {
            sb.append(" o=");
            sb.append(preferredOrder);
        }
        sb.append(" m=0x");
        sb.append(Integer.toHexString(match));
        if (targetUserId != UserHandle.USER_CURRENT) {
            sb.append(" targetUserId=");
            sb.append(targetUserId);
        }
        sb.append('}');
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        if (activityInfo != null) {
            dest.writeInt(1);
            activityInfo.writeToParcel(dest, parcelableFlags);
        } else if (serviceInfo != null) {
            dest.writeInt(2);
            serviceInfo.writeToParcel(dest, parcelableFlags);
        } else if (providerInfo != null) {
            dest.writeInt(3);
            providerInfo.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        if (filter != null) {
            dest.writeInt(1);
            filter.writeToParcel(dest, parcelableFlags);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(priority);
        dest.writeInt(preferredOrder);
        dest.writeInt(match);
        dest.writeInt(specificIndex);
        dest.writeInt(labelRes);
        TextUtils.writeToParcel(nonLocalizedLabel, dest, parcelableFlags);
        dest.writeInt(icon);
        dest.writeString(resolvePackageName);
        dest.writeInt(targetUserId);
        dest.writeInt(system ? 1 : 0);
        dest.writeInt(noResourceId ? 1 : 0);
        dest.writeInt(iconResourceId);
        dest.writeInt(handleAllWebDataURI ? 1 : 0);
        dest.writeInt(isInstantAppAvailable ? 1 : 0);
    }

    public static final Creator<ResolveInfo> CREATOR
            = new Creator<ResolveInfo>() {
        public ResolveInfo createFromParcel(Parcel source) {
            return new ResolveInfo(source);
        }
        public ResolveInfo[] newArray(int size) {
            return new ResolveInfo[size];
        }
    };

    private ResolveInfo(Parcel source) {
        activityInfo = null;
        serviceInfo = null;
        providerInfo = null;
        switch (source.readInt()) {
            case 1:
                activityInfo = ActivityInfo.CREATOR.createFromParcel(source);
                break;
            case 2:
                serviceInfo = ServiceInfo.CREATOR.createFromParcel(source);
                break;
            case 3:
                providerInfo = ProviderInfo.CREATOR.createFromParcel(source);
                break;
            default:
                Slog.w(TAG, "Missing ComponentInfo!");
                break;
        }
        if (source.readInt() != 0) {
            filter = IntentFilter.CREATOR.createFromParcel(source);
        }
        priority = source.readInt();
        preferredOrder = source.readInt();
        match = source.readInt();
        specificIndex = source.readInt();
        labelRes = source.readInt();
        nonLocalizedLabel
                = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        icon = source.readInt();
        resolvePackageName = source.readString();
        targetUserId = source.readInt();
        system = source.readInt() != 0;
        noResourceId = source.readInt() != 0;
        iconResourceId = source.readInt();
        handleAllWebDataURI = source.readInt() != 0;
        instantAppAvailable = isInstantAppAvailable = source.readInt() != 0;
    }

    public static class DisplayNameComparator
            implements Comparator<ResolveInfo> {
        public DisplayNameComparator(PackageManager pm) {
            mPM = pm;
            mCollator.setStrength(Collator.PRIMARY);
        }

        public final int compare(ResolveInfo a, ResolveInfo b) {
            // We want to put the one targeted to another user at the end of the dialog.
            if (a.targetUserId != UserHandle.USER_CURRENT) {
                return 1;
            }
            if (b.targetUserId != UserHandle.USER_CURRENT) {
                return -1;
            }
            CharSequence  sa = a.loadLabel(mPM);
            if (sa == null) sa = a.activityInfo.name;
            CharSequence  sb = b.loadLabel(mPM);
            if (sb == null) sb = b.activityInfo.name;
            
            return mCollator.compare(sa.toString(), sb.toString());
        }

        private final Collator   mCollator = Collator.getInstance();
        private PackageManager   mPM;
    }
}
