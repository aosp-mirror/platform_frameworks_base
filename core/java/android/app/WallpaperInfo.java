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

package android.app;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources.NotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.wallpaper.WallpaperService;
import android.util.AttributeSet;
import android.util.Printer;
import android.util.Xml;

import java.io.IOException;

/**
 * This class is used to specify meta information of a wallpaper service.
 */
public final class WallpaperInfo implements Parcelable {
    static final String TAG = "WallpaperInfo";
    
    /**
     * The Service that implements this wallpaper component.
     */
    final ResolveInfo mService;
    
    /**
     * The wallpaper setting activity's name, to
     * launch the setting activity of this wallpaper.
     */
    final String mSettingsActivityName;

    /**
     * Resource identifier for this wallpaper's thumbnail image.
     */
    final int mThumbnailResource;

    /**
     * Resource identifier for a string indicating the author of the wallpaper.
     */
    final int mAuthorResource;

    /**
     * Resource identifier for a string containing a short description of the wallpaper.
     */
    final int mDescriptionResource;

    /**
     * Constructor.
     * 
     * @param context The Context in which we are parsing the wallpaper.
     * @param service The ResolveInfo returned from the package manager about
     * this wallpaper's component.
     */
    public WallpaperInfo(Context context, ResolveInfo service)
            throws XmlPullParserException, IOException {
        mService = service;
        ServiceInfo si = service.serviceInfo;
        
        PackageManager pm = context.getPackageManager();
        String settingsActivityComponent = null;
        int thumbnailRes = -1;
        int authorRes = -1;
        int descriptionRes = -1;
        
        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, WallpaperService.SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No "
                        + WallpaperService.SERVICE_META_DATA + " meta-data");
            }
        
            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            
            AttributeSet attrs = Xml.asAttributeSet(parser);
            
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            
            String nodeName = parser.getName();
            if (!"wallpaper".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with wallpaper tag");
            }
            
            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.Wallpaper);
            settingsActivityComponent = sa.getString(
                    com.android.internal.R.styleable.Wallpaper_settingsActivity);
            
            thumbnailRes = sa.getResourceId(
                    com.android.internal.R.styleable.Wallpaper_thumbnail,
                    -1);
            authorRes = sa.getResourceId(
                    com.android.internal.R.styleable.Wallpaper_author,
                    -1);
            descriptionRes = sa.getResourceId(
                    com.android.internal.R.styleable.Wallpaper_description,
                    -1);

            sa.recycle();
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException(
                    "Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
        
        mSettingsActivityName = settingsActivityComponent;
        mThumbnailResource = thumbnailRes;
        mAuthorResource = authorRes;
        mDescriptionResource = descriptionRes;
    }

    WallpaperInfo(Parcel source) {
        mSettingsActivityName = source.readString();
        mThumbnailResource = source.readInt();
        mAuthorResource = source.readInt();
        mDescriptionResource = source.readInt();
        mService = ResolveInfo.CREATOR.createFromParcel(source);
    }
    
    /**
     * Return the .apk package that implements this wallpaper.
     */
    public String getPackageName() {
        return mService.serviceInfo.packageName;
    }
    
    /**
     * Return the class name of the service component that implements
     * this wallpaper.
     */
    public String getServiceName() {
        return mService.serviceInfo.name;
    }

    /**
     * Return the raw information about the Service implementing this
     * wallpaper.  Do not modify the returned object.
     */
    public ServiceInfo getServiceInfo() {
        return mService.serviceInfo;
    }

    /**
     * Return the component of the service that implements this wallpaper.
     */
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }
    
    /**
     * Load the user-displayed label for this wallpaper.
     * 
     * @param pm Supply a PackageManager used to load the wallpaper's
     * resources.
     */
    public CharSequence loadLabel(PackageManager pm) {
        return mService.loadLabel(pm);
    }
    
    /**
     * Load the user-displayed icon for this wallpaper.
     * 
     * @param pm Supply a PackageManager used to load the wallpaper's
     * resources.
     */
    public Drawable loadIcon(PackageManager pm) {
        return mService.loadIcon(pm);
    }
    
    /**
     * Load the thumbnail image for this wallpaper.
     * 
     * @param pm Supply a PackageManager used to load the wallpaper's
     * resources.
     */
    public Drawable loadThumbnail(PackageManager pm) {
        if (mThumbnailResource < 0) return null;

        return pm.getDrawable(mService.serviceInfo.packageName,
                              mThumbnailResource,
                              mService.serviceInfo.applicationInfo);
    }

    /**
     * Return a string indicating the author(s) of this wallpaper.
     */
    public CharSequence loadAuthor(PackageManager pm) throws NotFoundException {
        if (mAuthorResource <= 0) throw new NotFoundException();
        String packageName = mService.resolvePackageName;
        ApplicationInfo applicationInfo = null;
        if (packageName == null) {
            packageName = mService.serviceInfo.packageName;
            applicationInfo = mService.serviceInfo.applicationInfo;
        }
        return pm.getText(packageName, mAuthorResource, applicationInfo);
    }

    /**
     * Return a brief summary of this wallpaper's behavior.
     */
    public CharSequence loadDescription(PackageManager pm) throws NotFoundException {
        String packageName = mService.resolvePackageName;
        ApplicationInfo applicationInfo = null;
        if (packageName == null) {
            packageName = mService.serviceInfo.packageName;
            applicationInfo = mService.serviceInfo.applicationInfo;
        }
        if (mService.serviceInfo.descriptionRes != 0) {
            return pm.getText(packageName, mService.serviceInfo.descriptionRes,
                    applicationInfo);
            
        }
        if (mDescriptionResource <= 0) throw new NotFoundException();
        return pm.getText(packageName, mDescriptionResource,
                mService.serviceInfo.applicationInfo);
    }
    
    /**
     * Return the class name of an activity that provides a settings UI for
     * the wallpaper.  You can launch this activity be starting it with
     * an {@link android.content.Intent} whose action is MAIN and with an
     * explicit {@link android.content.ComponentName}
     * composed of {@link #getPackageName} and the class name returned here.
     * 
     * <p>A null will be returned if there is no settings activity associated
     * with the wallpaper.
     */
    public String getSettingsActivity() {
        return mSettingsActivityName;
    }
    
    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "Service:");
        mService.dump(pw, prefix + "  ");
        pw.println(prefix + "mSettingsActivityName=" + mSettingsActivityName);
    }
    
    @Override
    public String toString() {
        return "WallpaperInfo{" + mService.serviceInfo.name
                + ", settings: "
                + mSettingsActivityName + "}";
    }

    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mSettingsActivityName);
        dest.writeInt(mThumbnailResource);
        dest.writeInt(mAuthorResource);
        dest.writeInt(mDescriptionResource);
        mService.writeToParcel(dest, flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<WallpaperInfo> CREATOR = new Parcelable.Creator<WallpaperInfo>() {
        public WallpaperInfo createFromParcel(Parcel source) {
            return new WallpaperInfo(source);
        }

        public WallpaperInfo[] newArray(int size) {
            return new WallpaperInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
