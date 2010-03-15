/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.text.TextUtils;

/**
 * A special subclass of Intent that can have a custom label/icon
 * associated with it.  Primarily for use with {@link Intent#ACTION_CHOOSER}.
 */
public class LabeledIntent extends Intent {
    private String mSourcePackage;
    private int mLabelRes;
    private CharSequence mNonLocalizedLabel;
    private int mIcon;
    
    /**
     * Create a labeled intent from the given intent, supplying the label
     * and icon resources for it.
     * 
     * @param origIntent The original Intent to copy.
     * @param sourcePackage The package in which the label and icon live.
     * @param labelRes Resource containing the label, or 0 if none.
     * @param icon Resource containing the icon, or 0 if none.
     */
    public LabeledIntent(Intent origIntent, String sourcePackage,
            int labelRes, int icon) {
        super(origIntent);
        mSourcePackage = sourcePackage;
        mLabelRes = labelRes;
        mNonLocalizedLabel = null;
        mIcon = icon;
    }
    
    /**
     * Create a labeled intent from the given intent, supplying a textual
     * label and icon resource for it.
     * 
     * @param origIntent The original Intent to copy.
     * @param sourcePackage The package in which the label and icon live.
     * @param nonLocalizedLabel Concrete text to use for the label.
     * @param icon Resource containing the icon, or 0 if none.
     */
    public LabeledIntent(Intent origIntent, String sourcePackage,
            CharSequence nonLocalizedLabel, int icon) {
        super(origIntent);
        mSourcePackage = sourcePackage;
        mLabelRes = 0;
        mNonLocalizedLabel = nonLocalizedLabel;
        mIcon = icon;
    }
    
    /**
     * Create a labeled intent with no intent data but supplying the label
     * and icon resources for it.
     * 
     * @param sourcePackage The package in which the label and icon live.
     * @param labelRes Resource containing the label, or 0 if none.
     * @param icon Resource containing the icon, or 0 if none.
     */
    public LabeledIntent(String sourcePackage, int labelRes, int icon) {
        mSourcePackage = sourcePackage;
        mLabelRes = labelRes;
        mNonLocalizedLabel = null;
        mIcon = icon;
    }
    
    /**
     * Create a labeled intent with no intent data but supplying a textual
     * label and icon resource for it.
     * 
     * @param sourcePackage The package in which the label and icon live.
     * @param nonLocalizedLabel Concrete text to use for the label.
     * @param icon Resource containing the icon, or 0 if none.
     */
    public LabeledIntent(String sourcePackage,
            CharSequence nonLocalizedLabel, int icon) {
        mSourcePackage = sourcePackage;
        mLabelRes = 0;
        mNonLocalizedLabel = nonLocalizedLabel;
        mIcon = icon;
    }
    
    /**
     * Return the name of the package holding label and icon resources.
     */
    public String getSourcePackage() {
        return mSourcePackage;
    }
    
    /**
     * Return any resource identifier that has been given for the label text.
     */
    public int getLabelResource() {
        return mLabelRes;
    }
    
    /**
     * Return any concrete text that has been given for the label text.
     */
    public CharSequence getNonLocalizedLabel() {
        return mNonLocalizedLabel;
    }
    
    /**
     * Return any resource identifier that has been given for the label icon.
     */
    public int getIconResource() {
        return mIcon;
    }
    
    /**
     * Retrieve the label associated with this object.  If the object does
     * not have a label, null will be returned, in which case you will probably
     * want to load the label from the underlying resolved info for the Intent.
     */
    public CharSequence loadLabel(PackageManager pm) {
        if (mNonLocalizedLabel != null) {
            return mNonLocalizedLabel;
        }
        if (mLabelRes != 0 && mSourcePackage != null) {
            CharSequence label = pm.getText(mSourcePackage, mLabelRes, null);
            if (label != null) {
                return label;
            }
        }
        return null;
    }
    
    /**
     * Retrieve the icon associated with this object.  If the object does
     * not have a icon, null will be returned, in which case you will probably
     * want to load the icon from the underlying resolved info for the Intent.
     */
    public Drawable loadIcon(PackageManager pm) {
        if (mIcon != 0 && mSourcePackage != null) {
            Drawable icon = pm.getDrawable(mSourcePackage, mIcon, null);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }
    
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString(mSourcePackage);
        dest.writeInt(mLabelRes);
        TextUtils.writeToParcel(mNonLocalizedLabel, dest, parcelableFlags);
        dest.writeInt(mIcon);
    }

    /** @hide */
    protected LabeledIntent(Parcel in) {
        readFromParcel(in);
    }
    
    public void readFromParcel(Parcel in) {
        super.readFromParcel(in);
        mSourcePackage = in.readString();
        mLabelRes = in.readInt();
        mNonLocalizedLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mIcon = in.readInt();
    }
    
    public static final Creator<LabeledIntent> CREATOR
            = new Creator<LabeledIntent>() {
        public LabeledIntent createFromParcel(Parcel source) {
            return new LabeledIntent(source);
        }
        public LabeledIntent[] newArray(int size) {
            return new LabeledIntent[size];
        }
    };

}
