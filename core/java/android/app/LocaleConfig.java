/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The LocaleConfig of an application.
 * There are two sources. One is from an XML resource file with an {@code <locale-config>} element
 * and referenced in the manifest via {@code android:localeConfig} on {@code <application>}. The
 * other is that the application dynamically provides an override version which is persisted in
 * {@link LocaleManager#setOverrideLocaleConfig(LocaleConfig)}.
 *
 * <p>For more information about the LocaleConfig from an XML resource file, see
 * <a href="https://developer.android.com/about/versions/13/features/app-languages#use-localeconfig">
 * the section on per-app language preferences</a>.
 *
 * @attr ref android.R.styleable#LocaleConfig_Locale_name
 * @attr ref android.R.styleable#AndroidManifestApplication_localeConfig
 *
 * <p>For more information about the LocaleConfig overridden by the application, see
 * TODO(b/261528306): add link to guide
 */
public class LocaleConfig implements Parcelable {
    private static final String TAG = "LocaleConfig";
    public static final String TAG_LOCALE_CONFIG = "locale-config";
    public static final String TAG_LOCALE = "locale";
    private LocaleList mLocales;
    private int mStatus = STATUS_NOT_SPECIFIED;

    /**
     * succeeded reading the LocaleConfig structure stored in an XML file.
     */
    public static final int STATUS_SUCCESS = 0;
    /**
     * No android:localeConfig tag on <application>.
     */
    public static final int STATUS_NOT_SPECIFIED = 1;
    /**
     * Malformed input in the XML file where the LocaleConfig was stored.
     */
    public static final int STATUS_PARSING_FAILED = 2;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_SUCCESS,
            STATUS_NOT_SPECIFIED,
            STATUS_PARSING_FAILED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status{}

    /**
     * Returns an override LocaleConfig if it has been set via
     * {@link LocaleManager#setOverrideLocaleConfig(LocaleConfig)}. Otherwise, returns the
     * LocaleConfig from the application resources.
     *
     * @param context the context of the application.
     *
     * @see Context#createPackageContext(String, int).
     */
    public LocaleConfig(@NonNull Context context) {
        this(context, true);
    }

    /**
     * Returns a LocaleConfig from the application resources regardless of whether any LocaleConfig
     * is overridden via {@link LocaleManager#setOverrideLocaleConfig(LocaleConfig)}.
     *
     * @param context the context of the application.
     *
     * @see Context#createPackageContext(String, int).
     */
    @NonNull
    public static LocaleConfig fromContextIgnoringOverride(@NonNull Context context) {
        return new LocaleConfig(context, false);
    }

    private LocaleConfig(@NonNull Context context, boolean allowOverride) {
        if (allowOverride) {
            LocaleManager localeManager = context.getSystemService(LocaleManager.class);
            if (localeManager == null) {
                Slog.w(TAG, "LocaleManager is null, cannot get the override LocaleConfig");
                mStatus = STATUS_NOT_SPECIFIED;
                return;
            }
            LocaleConfig localeConfig = localeManager.getOverrideLocaleConfig();
            if (localeConfig != null) {
                Slog.d(TAG, "Has the override LocaleConfig");
                mStatus = localeConfig.getStatus();
                mLocales = localeConfig.getSupportedLocales();
                return;
            }
        }
        int resId = 0;
        Resources res = context.getResources();
        try {
            //Get the resource id
            resId = new ApplicationInfo(context.getApplicationInfo()).getLocaleConfigRes();
            //Get the parser to read XML data
            XmlResourceParser parser = res.getXml(resId);
            parseLocaleConfig(parser, res);
        } catch (Resources.NotFoundException e) {
            Slog.w(TAG, "The resource file pointed to by the given resource ID isn't found.");
            mStatus = STATUS_NOT_SPECIFIED;
        } catch (XmlPullParserException | IOException e) {
            Slog.w(TAG, "Failed to parse XML configuration from "
                    + res.getResourceEntryName(resId), e);
            mStatus = STATUS_PARSING_FAILED;
        }
    }

    /**
     * Return the LocaleConfig with any sequence of locales combined into a {@link LocaleList}.
     *
     * <p><b>Note:</b> Applications seeking to create an override LocaleConfig via
     * {@link LocaleManager#setOverrideLocaleConfig(LocaleConfig)} should use this constructor to
     * first create the LocaleConfig they intend the system to see as the override.
     *
     * <p><b>Note:</b> The creation of this LocaleConfig does not automatically mean it will
     * become the override config for an application. Any LocaleConfig desired to be the override
     * must be passed into the {@link LocaleManager#setOverrideLocaleConfig(LocaleConfig)},
     * otherwise it will not persist or affect the system’s understanding of app-supported
     * resources.
     *
     * @param locales the desired locales for a specified application
     */
    public LocaleConfig(@NonNull LocaleList locales) {
        mStatus = STATUS_SUCCESS;
        mLocales = locales;
    }

    /**
     * Instantiate a new LocaleConfig from the data in a Parcel that was
     * previously written with {@link #writeToParcel(Parcel, int)}.
     *
     * @param in The Parcel containing the previously written LocaleConfig,
     * positioned at the location in the buffer where it was written.
     */
    private LocaleConfig(@NonNull Parcel in) {
        mStatus = in.readInt();
        mLocales = in.readTypedObject(LocaleList.CREATOR);
    }

    /**
     * Parse the XML content and get the locales supported by the application
     */
    private void parseLocaleConfig(XmlResourceParser parser, Resources res)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, TAG_LOCALE_CONFIG);
        int outerDepth = parser.getDepth();
        AttributeSet attrs = Xml.asAttributeSet(parser);
        Set<String> localeNames = new HashSet<String>();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (TAG_LOCALE.equals(parser.getName())) {
                final TypedArray attributes = res.obtainAttributes(
                        attrs, com.android.internal.R.styleable.LocaleConfig_Locale);
                String nameAttr = attributes.getString(
                        com.android.internal.R.styleable.LocaleConfig_Locale_name);
                localeNames.add(nameAttr);
                attributes.recycle();
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        mStatus = STATUS_SUCCESS;
        mLocales = LocaleList.forLanguageTags(String.join(",", localeNames));
    }

    /**
     * Returns the locales supported by the specified application.
     *
     * <p><b>Note:</b> The locale format should follow the
     * <a href="https://www.rfc-editor.org/rfc/bcp/bcp47.txt">IETF BCP47 regular expression</a>
     *
     * @return the {@link LocaleList}
     */
    public @Nullable LocaleList getSupportedLocales() {
        return mLocales;
    }

    /**
     * Get the status of reading the resource file where the LocaleConfig was stored.
     *
     * <p>Distinguish "the application didn't provide the resource file" from "the application
     * provided malformed input" if {@link #getSupportedLocales()} returns {@code null}.
     *
     * @return {@code STATUS_SUCCESS} if the LocaleConfig structure existed in an XML file was
     * successfully read, or {@code STATUS_NOT_SPECIFIED} if no android:localeConfig tag on
     * <application> pointing to an XML file that stores the LocaleConfig, or
     * {@code STATUS_PARSING_FAILED} if the application provided malformed input for the
     * LocaleConfig structure.
     *
     * @see #STATUS_SUCCESS
     * @see #STATUS_NOT_SPECIFIED
     * @see #STATUS_PARSING_FAILED
     *
     */
    public @Status int getStatus() {
        return mStatus;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeTypedObject(mLocales, flags);
    }

    public static final @NonNull Parcelable.Creator<LocaleConfig> CREATOR =
            new Parcelable.Creator<LocaleConfig>() {
                @Override
                public LocaleConfig createFromParcel(Parcel source) {
                    return new LocaleConfig(source);
                }

                @Override
                public LocaleConfig[] newArray(int size) {
                    return new LocaleConfig[size];
                }
            };

    /**
     * Compare whether the locale is existed in the {@code mLocales} of the LocaleConfig.
     *
     * @param locale The {@link Locale} to compare for.
     *
     * @return true if the locale is existed in the {@code mLocales} of the LocaleConfig, false
     * otherwise.
     *
     * @hide
     */
    public boolean containsLocale(Locale locale) {
        if (mLocales == null) {
            return false;
        }

        for (int i = 0; i < mLocales.size(); i++) {
            if (LocaleList.matchesLanguageAndScript(mLocales.get(i), locale)) {
                return true;
            }
        }

        return false;
    }
}
