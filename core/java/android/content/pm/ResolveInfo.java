package android.content.pm;

import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Printer;

import java.text.Collator;
import java.util.Comparator;

/**
 * Information that is returned from resolving an intent
 * against an IntentFilter. This partially corresponds to
 * information collected from the AndroidManifest.xml's
 * &lt;intent&gt; tags.
 */
public class ResolveInfo implements Parcelable {
    /**
     * The activity that corresponds to this resolution match, if this
     * resolution is for an activity.  One and only one of this and
     * serviceInfo must be non-null.
     */
    public ActivityInfo activityInfo;
    
    /**
     * The service that corresponds to this resolution match, if this
     * resolution is for a service. One and only one of this and
     * activityInfo must be non-null.
     */
    public ServiceInfo serviceInfo;
    
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
     * match's icon.  From the "icon" attribute or, if not set, 0.
     */
    public int icon;

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
        ComponentInfo ci = activityInfo != null ? activityInfo : serviceInfo;
        ApplicationInfo ai = ci.applicationInfo;
        CharSequence label;
        if (labelRes != 0) {
            label = pm.getText(ci.packageName, labelRes, ai);
            if (label != null) {
                return label;
            }
        }
        return ci.loadLabel(pm);
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
        ComponentInfo ci = activityInfo != null ? activityInfo : serviceInfo;
        ApplicationInfo ai = ci.applicationInfo;
        Drawable dr;
        if (icon != 0) {
            dr = pm.getDrawable(ci.packageName, icon, ai);
            if (dr != null) {
                return dr;
            }
        }
        return ci.loadIcon(pm);
    }
    
    /**
     * Return the icon resource identifier to use for this match.  If the
     * match defines an icon, that is used; else if the activity defines
     * an icon, that is used; else, the application icon is used.
     * 
     * @return The icon associated with this match.
     */
    public final int getIconResource() {
        if (icon != 0) return icon;
        if (activityInfo != null) return activityInfo.getIconResource();
        if (serviceInfo != null) return serviceInfo.getIconResource();
        return 0;
    }

    public void dump(Printer pw, String prefix) {
        if (filter != null) {
            pw.println(prefix + "Filter:");
            filter.dump(pw, prefix + "  ");
        } else {
            pw.println(prefix + "Filter: null");
        }
        pw.println(prefix + "priority=" + priority
                + " preferredOrder=" + preferredOrder
                + " match=0x" + Integer.toHexString(match)
                + " specificIndex=" + specificIndex
                + " isDefault=" + isDefault);
        pw.println(prefix + "labelRes=0x" + Integer.toHexString(labelRes)
                + " nonLocalizedLabel=" + nonLocalizedLabel
                + " icon=0x" + Integer.toHexString(icon));
        if (activityInfo != null) {
            pw.println(prefix + "ActivityInfo:");
            activityInfo.dump(pw, prefix + "  ");
        } else if (serviceInfo != null) {
            pw.println(prefix + "ServiceInfo:");
            // TODO
            //serviceInfo.dump(pw, prefix + "  ");
        }
    }
    
    public ResolveInfo() {
    }

    public String toString() {
        ComponentInfo ci = activityInfo != null ? activityInfo : serviceInfo;
        return "ResolveInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + ci.name + " p=" + priority + " o="
            + preferredOrder + " m=0x" + Integer.toHexString(match) + "}";
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
        switch (source.readInt()) {
            case 1:
                activityInfo = ActivityInfo.CREATOR.createFromParcel(source);
                serviceInfo = null;
                break;
            case 2:
                serviceInfo = ServiceInfo.CREATOR.createFromParcel(source);
                activityInfo = null;
                break;
            default:
                activityInfo = null;
                serviceInfo = null;
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
    }
    
    public static class DisplayNameComparator
            implements Comparator<ResolveInfo> {
        public DisplayNameComparator(PackageManager pm) {
            mPM = pm;
        }

        public final int compare(ResolveInfo a, ResolveInfo b) {
            CharSequence  sa = a.loadLabel(mPM);
            if (sa == null) sa = a.activityInfo.name;
            CharSequence  sb = b.loadLabel(mPM);
            if (sb == null) sb = b.activityInfo.name;
            
            return sCollator.compare(sa.toString(), sb.toString());
        }

        private final Collator   sCollator = Collator.getInstance();
        private PackageManager   mPM;
    }
}
