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

import android.graphics.drawable.Drawable;
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
     * A string resource identifier (in the package's resources) containing
     * a user-readable description of the component.  From the "description"
     * attribute or, if not set, 0.
     */
    public int descriptionRes;
    
    /**
     * Indicates whether or not this component may be instantiated.  Note that this value can be
     * overriden by the one in its parent {@link ApplicationInfo}.
     */
    public boolean enabled = true;

    /**
     * Set to true if this component is available for use by other applications.
     * Comes from {@link android.R.attr#exported android:exported} of the
     * &lt;activity&gt;, &lt;receiver&gt;, &lt;service&gt;, or
     * &lt;provider&gt; tag.
     */
    public boolean exported = false;
    
    public ComponentInfo() {
    }

    public ComponentInfo(ComponentInfo orig) {
        super(orig);
        applicationInfo = orig.applicationInfo;
        processName = orig.processName;
        descriptionRes = orig.descriptionRes;
        enabled = orig.enabled;
        exported = orig.exported;
    }

    @Override public CharSequence loadLabel(PackageManager pm) {
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
     * Return the icon resource identifier to use for this component.  If
     * the component defines an icon, that is used; else, the application
     * icon is used.
     * 
     * @return The icon associated with this component.
     */
    public final int getIconResource() {
        return icon != 0 ? icon : applicationInfo.icon;
    }
    
    protected void dumpFront(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        pw.println(prefix + "enabled=" + enabled + " exported=" + exported
                + " processName=" + processName);
        if (descriptionRes != 0) {
            pw.println(prefix + "description=" + descriptionRes);
        }
    }
    
    protected void dumpBack(Printer pw, String prefix) {
        if (applicationInfo != null) {
            pw.println(prefix + "ApplicationInfo:");
            applicationInfo.dump(pw, prefix + "  ");
        } else {
            pw.println(prefix + "ApplicationInfo: null");
        }
        super.dumpBack(pw, prefix);
    }
    
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        applicationInfo.writeToParcel(dest, parcelableFlags);
        dest.writeString(processName);
        dest.writeInt(descriptionRes);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(exported ? 1 : 0);
    }
    
    protected ComponentInfo(Parcel source) {
        super(source);
        applicationInfo = ApplicationInfo.CREATOR.createFromParcel(source);
        processName = source.readString();
        descriptionRes = source.readInt();
        enabled = (source.readInt() != 0);
        exported = (source.readInt() != 0);
    }
    
    /**
     * @hide
     */
    @Override protected Drawable loadDefaultIcon(PackageManager pm) {
        return applicationInfo.loadIcon(pm);
    }
    
    /**
     * @hide
     */
    @Override protected ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }
}
