/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.util.Printer;

/**
 * Base class containing information common to all application components
 * ({@link ActivityInfo}, {@link ServiceInfo}).  This class is not intended
 * to be used by itself; it is simply here to share common definitions
 * between all application components.  As such, it does not itself
 * implement Parcelable, but does provide convenience methods to assist
 * in the implementation of Parcelable in subclasses.
 */
public class ComponentInfo extends PackageItemInfo {
    /**
     * Global information about the application/package this component is a
     * part of.
     */
    public ApplicationInfo applicationInfo;
    
    /**
     * The name of the process this component should run in.
     * From the "android:process" attribute or, if not set, the same
     * as <var>applicationInfo.processName</var>.
     */
    public String processName;

    /**
     * The name of the split in which this component is declared.
     * Null if the component was declared in the base APK.
     */
    public String splitName;

    /**
     * Set of attribution tags that should be automatically applied to this
     * component.
     * <p>
     * When this component represents an {@link Activity}, {@link Service},
     * {@link ContentResolver} or {@link BroadcastReceiver}, each instance will
     * be automatically configured with {@link Context#createAttributionContext}
     * using the first attribution tag contained here.
     * <p>
     * Additionally, when this component represents a {@link BroadcastReceiver}
     * and the sender of a broadcast requires the receiver to hold one or more
     * specific permissions, those permission checks will be performed using
     * each of the attributions tags contained here.
     *
     * @see Context#createAttributionContext(String)
     */
    @SuppressLint({"MissingNullability", "MutableBareField"})
    public String[] attributionTags;

    /**
     * A string resource identifier (in the package's resources) containing
     * a user-readable description of the component.  From the "description"
     * attribute or, if not set, 0.
     */
    public int descriptionRes;
    
    /**
     * Indicates whether or not this component may be instantiated.  Note that this value can be
     * overridden by the one in its parent {@link ApplicationInfo}.
     */
    public boolean enabled = true;

    /**
     * Set to true if this component is available for use by other applications.
     * Comes from {@link android.R.attr#exported android:exported} of the
     * &lt;activity&gt;, &lt;receiver&gt;, &lt;service&gt;, or
     * &lt;provider&gt; tag.
     */
    public boolean exported = false;

    /**
     * Indicates if this component is aware of direct boot lifecycle, and can be
     * safely run before the user has entered their credentials (such as a lock
     * pattern or PIN).
     */
    public boolean directBootAware = false;

    public ComponentInfo() {
    }

    public ComponentInfo(ComponentInfo orig) {
        super(orig);
        applicationInfo = orig.applicationInfo;
        processName = orig.processName;
        splitName = orig.splitName;
        attributionTags = orig.attributionTags;
        descriptionRes = orig.descriptionRes;
        enabled = orig.enabled;
        exported = orig.exported;
        directBootAware = orig.directBootAware;
    }

    /** @hide */
    @Override public CharSequence loadUnsafeLabel(PackageManager pm) {
        if (nonLocalizedLabel != null) {
            return nonLocalizedLabel;
        }
        ApplicationInfo ai = applicationInfo;
        CharSequence label;
        if (labelRes != 0) {
            label = pm.getText(packageName, labelRes, ai);
            if (label != null) {
                return label;
            }
        }
        if (ai.nonLocalizedLabel != null) {
            return ai.nonLocalizedLabel;
        }
        if (ai.labelRes != 0) {
            label = pm.getText(packageName, ai.labelRes, ai);
            if (label != null) {
                return label;
            }
        }
        return name;
    }

    /**
     * Return whether this component and its enclosing application are enabled.
     */
    public boolean isEnabled() {
        return enabled && applicationInfo.enabled;
    }
    
    /**
     * Return the icon resource identifier to use for this component.  If
     * the component defines an icon, that is used; else, the application
     * icon is used.
     * 
     * @return The icon associated with this component.
     */
    public final int getIconResource() {
        return icon != 0 ? icon : applicationInfo.icon;
    }

    /**
     * Return the logo resource identifier to use for this component.  If
     * the component defines a logo, that is used; else, the application
     * logo is used.
     *
     * @return The logo associated with this component.
     */
    public final int getLogoResource() {
        return logo != 0 ? logo : applicationInfo.logo;
    }
    
    /**
     * Return the banner resource identifier to use for this component. If the
     * component defines a banner, that is used; else, the application banner is
     * used.
     *
     * @return The banner associated with this component.
     */
    public final int getBannerResource() {
        return banner != 0 ? banner : applicationInfo.banner;
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ComponentName getComponentName() {
        return new ComponentName(packageName, name);
    }

    protected void dumpFront(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        if (processName != null && !packageName.equals(processName)) {
            pw.println(prefix + "processName=" + processName);
        }
        if (splitName != null) {
            pw.println(prefix + "splitName=" + splitName);
        }
        if (attributionTags != null && attributionTags.length > 0) {
            StringBuilder tags = new StringBuilder();
            tags.append(attributionTags[0]);
            for (int i = 1; i < attributionTags.length; i++) {
                tags.append(", ");
                tags.append(attributionTags[i]);
            }
            pw.println(prefix + "attributionTags=[" + tags + "]");
        }
        pw.println(prefix + "enabled=" + enabled + " exported=" + exported
                + " directBootAware=" + directBootAware);
        if (descriptionRes != 0) {
            pw.println(prefix + "description=" + descriptionRes);
        }
    }

    protected void dumpBack(Printer pw, String prefix) {
        dumpBack(pw, prefix, DUMP_FLAG_ALL);
    }

    void dumpBack(Printer pw, String prefix, int dumpFlags) {
        if ((dumpFlags & DUMP_FLAG_APPLICATION) != 0) {
            if (applicationInfo != null) {
                pw.println(prefix + "ApplicationInfo:");
                applicationInfo.dump(pw, prefix + "  ", dumpFlags);
            } else {
                pw.println(prefix + "ApplicationInfo: null");
            }
        }
        super.dumpBack(pw, prefix);
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        applicationInfo.writeToParcel(dest, parcelableFlags);
        dest.writeString8(processName);
        dest.writeString8(splitName);
        dest.writeString8Array(attributionTags);
        dest.writeInt(descriptionRes);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(exported ? 1 : 0);
        dest.writeInt(directBootAware ? 1 : 0);
    }
    
    protected ComponentInfo(Parcel source) {
        super(source);
        applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);
        processName = source.readString8();
        splitName = source.readString8();
        attributionTags = source.createString8Array();
        descriptionRes = source.readInt();
        enabled = (source.readInt() != 0);
        exported = (source.readInt() != 0);
        directBootAware = (source.readInt() != 0);
    }

    /**
     * @hide
     */
    @Override
    public Drawable loadDefaultIcon(PackageManager pm) {
        return applicationInfo.loadIcon(pm);
    }
    
    /**
     * @hide
     */
    @Override protected Drawable loadDefaultBanner(PackageManager pm) {
        return applicationInfo.loadBanner(pm);
    }

    /**
     * @hide
     */
    @Override
    protected Drawable loadDefaultLogo(PackageManager pm) {
        return applicationInfo.loadLogo(pm);
    }
    
    /**
     * @hide
     */
    @Override protected ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }
}
