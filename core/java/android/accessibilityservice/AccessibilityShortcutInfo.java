/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.accessibilityservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Activities of interest to users with accessibility needs may request to be targets of the
 * accessibility shortcut. These activities must handle the
 * {@link Intent#ACTION_MAIN} intent with category
 * {@link Intent#CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET}, which will be dispatched by the system
 * when the user activates the shortcut when it is configured to point at this target.
 *
 * @see Intent#CATEGORY_ACCESSIBILITY_SHORTCUT_TARGET
 *
 * @hide
 */
public final class AccessibilityShortcutInfo {
    private static final String TAG_ACCESSIBILITY_SHORTCUT = "accessibility-shortcut-target";

    /**
     * Name under which an activity component of the accessibility shortcut publishes information
     * about itself. This meta-data must reference an XML resource containing an
     * <code>&lt;accessibility-shortcut-target&gt;</code> tag.
     */
    public static final String META_DATA = "android.accessibilityshortcut.target";

    /**
     * The component name of the accessibility shortcut target.
     */
    private final ComponentName mComponentName;

    /**
     * The activity info of the accessibility shortcut target.
     */
    private final ActivityInfo mActivityInfo;

    /**
     * Resource id of the summary of the accessibility shortcut target.
     */
    private final int mSummaryResId;

    /**
     * Resource id of the description of the accessibility shortcut target.
     */
    private final int mDescriptionResId;

    /**
     * Creates a new instance.
     *
     * @param context Context for accessing resources.
     * @param activityInfo The activity info.
     * @throws XmlPullParserException If a XML parsing error occurs.
     * @throws IOException If a XML parsing error occurs.
     */
    public AccessibilityShortcutInfo(@NonNull Context context, @NonNull ActivityInfo activityInfo)
            throws XmlPullParserException, IOException {
        final PackageManager packageManager = context.getPackageManager();
        mComponentName = activityInfo.getComponentName();
        mActivityInfo = activityInfo;

        try (XmlResourceParser parser = mActivityInfo.loadXmlMetaData(
                packageManager, META_DATA)) {
            if (parser == null) {
                throw new XmlPullParserException("Meta-data "
                        + TAG_ACCESSIBILITY_SHORTCUT + " does not exist");
            }

            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            final String nodeName = parser.getName();
            if (!TAG_ACCESSIBILITY_SHORTCUT.equals(nodeName)) {
                throw new XmlPullParserException("Meta-data does not start with"
                        + TAG_ACCESSIBILITY_SHORTCUT + " tag");
            }

            final AttributeSet allAttributes = Xml.asAttributeSet(parser);
            final Resources resources = packageManager.getResourcesForApplication(
                    mActivityInfo.applicationInfo);
            final TypedArray asAttributes = resources.obtainAttributes(allAttributes,
                    com.android.internal.R.styleable.AccessibilityShortcutTarget);

            // Gets description
            mDescriptionResId = asAttributes.getResourceId(
                    com.android.internal.R.styleable.AccessibilityShortcutTarget_description, 0);
            // Gets summary
            mSummaryResId = asAttributes.getResourceId(
                    com.android.internal.R.styleable.AccessibilityShortcutTarget_summary, 0);
            asAttributes.recycle();

            if (mDescriptionResId == 0 || mSummaryResId == 0) {
                throw new XmlPullParserException("No description or summary in meta-data");
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new XmlPullParserException("Unable to create context for: "
                    + mActivityInfo.packageName);
        }
    }

    /**
     * The {@link ActivityInfo} of accessibility shortcut target.
     *
     * @return The activity info.
     */
    @NonNull
    public ActivityInfo getActivityInfo() {
        return mActivityInfo;
    }

    /**
     * The localized summary of the accessibility shortcut target.
     *
     * @return The localized summary if available, and {@code null} if a summary
     * has not been provided.
     */
    @Nullable
    public String loadSummary(@NonNull PackageManager packageManager) {
        return loadResourceString(packageManager, mActivityInfo, mSummaryResId);
    }

    /**
     * The localized description of the accessibility shortcut target.
     *
     * @return The localized description.
     */
    @Nullable
    public String loadDescription(@NonNull PackageManager packageManager) {
        return loadResourceString(packageManager, mActivityInfo, mDescriptionResId);
    }

    /**
     * Gets string resource by the given activity and resource id.
     */
    @Nullable
    private String loadResourceString(@NonNull PackageManager packageManager,
            @NonNull ActivityInfo activityInfo, int resId) {
        if (resId == 0) {
            return null;
        }
        final CharSequence text = packageManager.getText(activityInfo.packageName,
                resId, activityInfo.applicationInfo);
        if (text != null) {
            return text.toString().trim();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return 31 * 1 + ((mComponentName == null) ? 0 : mComponentName.hashCode());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final AccessibilityShortcutInfo other = (AccessibilityShortcutInfo) obj;
        if (mComponentName == null) {
            if (other.mComponentName != null) {
                return false;
            }
        } else if (!mComponentName.equals(other.mComponentName)) {
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("AccessibilityShortcutInfo[");
        stringBuilder.append("activityInfo: ").append(mActivityInfo);
        stringBuilder.append("]");
        return stringBuilder.toString();
    }
}
