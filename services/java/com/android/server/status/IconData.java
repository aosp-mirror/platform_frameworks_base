package com.android.server.status;

import android.util.Log;

public class IconData {
    /**
     * Indicates ths item represents a piece of text.
     */
    public static final int TEXT = 1;
    
    /**
     * Indicates ths item represents an icon.
     */
    public static final int ICON = 2;

    /**
     * The type of this item. One of TEXT, ICON, or LEVEL_ICON.
     */
    public int type;

    /**
     * The slot that this icon will be in if it is not a notification
     */
    public String slot;

    /**
     * The package containting the icon to draw for this item. Valid if this is
     * an ICON type.
     */
    public String iconPackage;
    
    /**
     * The icon to draw for this item. Valid if this is an ICON type.
     */
    public int iconId;
    
    /**
     * The level associated with the icon. Valid if this is a LEVEL_ICON type.
     */
    public int iconLevel;
    
    /**
     * The "count" number.
     */
    public int number;

    /**
     * The text associated with the icon. Valid if this is a TEXT type.
     */
    public CharSequence text;

    private IconData() {
    }

    public static IconData makeIcon(String slot,
            String iconPackage, int iconId, int iconLevel, int number) {
        IconData data = new IconData();
        data.type = ICON;
        data.slot = slot;
        data.iconPackage = iconPackage;
        data.iconId = iconId;
        data.iconLevel = iconLevel;
        data.number = number;
        return data;
    }
    
    public static IconData makeText(String slot, CharSequence text) {
        IconData data = new IconData();
        data.type = TEXT;
        data.slot = slot;
        data.text = text;
        return data;
    }

    public void copyFrom(IconData that) {
        this.type = that.type;
        this.slot = that.slot;
        this.iconPackage = that.iconPackage;
        this.iconId = that.iconId;
        this.iconLevel = that.iconLevel;
        this.number = that.number;
        this.text = that.text; // should we clone this?
    }

    public IconData clone() {
        IconData that = new IconData();
        that.copyFrom(this);
        return that;
    }

    public String toString() {
        if (this.type == TEXT) {
            return "IconData(slot=" + (this.slot != null ? "'" + this.slot + "'" : "null")
                    + " text='" + this.text + "')"; 
        }
        else if (this.type == ICON) {
            return "IconData(slot=" + (this.slot != null ? "'" + this.slot + "'" : "null")
                    + " package=" + this.iconPackage
                    + " iconId=" + Integer.toHexString(this.iconId)
                    + " iconLevel=" + this.iconLevel + ")"; 
        }
        else {
            return "IconData(type=" + type + ")";
        }
    }
}
