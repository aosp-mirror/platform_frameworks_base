/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.soundtrigger;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Enrollment information about the different available keyphrases.
 *
 * @hide
 */
@TestApi
public class KeyphraseEnrollmentInfo {
    private static final String TAG = "KeyphraseEnrollmentInfo";
    /**
     * Name under which a Hotword enrollment component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#VoiceEnrollmentApplication
     * voice-enrollment-application}&gt;</code> tag.
     */
    private static final String VOICE_KEYPHRASE_META_DATA = "android.voice_enrollment";
    /**
     * Intent Action: for managing the keyphrases for hotword detection.
     * This needs to be defined by a service that supports enrolling users for hotword/keyphrase
     * detection.
     * @hide
     */
    public static final String ACTION_MANAGE_VOICE_KEYPHRASES =
            "com.android.intent.action.MANAGE_VOICE_KEYPHRASES";
    /**
     * Intent extra: The intent extra for the specific manage action that needs to be performed.
     *
     * @see #MANAGE_ACTION_ENROLL
     * @see #MANAGE_ACTION_RE_ENROLL
     * @see #MANAGE_ACTION_UN_ENROLL
     * @hide
     */
    public static final String EXTRA_VOICE_KEYPHRASE_ACTION =
            "com.android.intent.extra.VOICE_KEYPHRASE_ACTION";

    /**
     * Intent extra: The hint text to be shown on the voice keyphrase management UI.
     * @hide
     */
    public static final String EXTRA_VOICE_KEYPHRASE_HINT_TEXT =
            "com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT";
    /**
     * Intent extra: The voice locale to use while managing the keyphrase.
     * This is a BCP-47 language tag.
     * @hide
     */
    public static final String EXTRA_VOICE_KEYPHRASE_LOCALE =
            "com.android.intent.extra.VOICE_KEYPHRASE_LOCALE";

    /**
     * Keyphrase management actions used with the {@link #EXTRA_VOICE_KEYPHRASE_ACTION} intent extra
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "MANAGE_ACTION_" }, value = {
            MANAGE_ACTION_ENROLL,
            MANAGE_ACTION_RE_ENROLL,
            MANAGE_ACTION_UN_ENROLL
    })
    public @interface ManageActions {}

    /**
     * Indicates desired action to enroll keyphrase model
     */
    public static final int MANAGE_ACTION_ENROLL = 0;
    /**
     * Indicates desired action to re-enroll keyphrase model
     */
    public static final int MANAGE_ACTION_RE_ENROLL = 1;
    /**
     * Indicates desired action to un-enroll keyphrase model
     */
    public static final int MANAGE_ACTION_UN_ENROLL = 2;

    /**
     * List of available keyphrases.
     */
    private final KeyphraseMetadata[] mKeyphrases;

    /**
     * Map between KeyphraseMetadata and the package name of the enrollment app that provides it.
     */
    final private Map<KeyphraseMetadata, String> mKeyphrasePackageMap;

    private String mParseError;

    public KeyphraseEnrollmentInfo(@NonNull PackageManager pm) {
        Objects.requireNonNull(pm);
        // Find the apps that supports enrollment for hotword keyhphrases,
        // Pick a privileged app and obtain the information about the supported keyphrases
        // from its metadata.
        List<ResolveInfo> ris = pm.queryIntentServices(
                new Intent(ACTION_MANAGE_VOICE_KEYPHRASES), PackageManager.MATCH_DEFAULT_ONLY);
        if (ris == null || ris.isEmpty()) {
            // No application capable of enrolling for voice keyphrases is present.
            mParseError = "No enrollment applications found";
            mKeyphrasePackageMap = Collections.emptyMap();
            mKeyphrases = null;
            return;
        }

        List<String> parseErrors = new LinkedList<>();
        mKeyphrasePackageMap = new HashMap<>();
        for (ResolveInfo ri : ris) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(
                        ri.serviceInfo.packageName, PackageManager.GET_META_DATA);
                if ((ai.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) == 0) {
                    // The application isn't privileged (/system/priv-app).
                    // The enrollment application needs to be a privileged system app.
                    Slog.w(TAG, ai.packageName + " is not a privileged system app");
                    continue;
                }
                if (!Manifest.permission.MANAGE_VOICE_KEYPHRASES.equals(ai.permission)) {
                    // The application trying to manage keyphrases doesn't
                    // require the MANAGE_VOICE_KEYPHRASES permission.
                    Slog.w(TAG, ai.packageName + " does not require MANAGE_VOICE_KEYPHRASES");
                    continue;
                }

                KeyphraseMetadata metadata =
                        getKeyphraseMetadataFromApplicationInfo(pm, ai, parseErrors);
                if (metadata != null) {
                    mKeyphrasePackageMap.put(metadata, ai.packageName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                String error = "error parsing voice enrollment meta-data for "
                        + ri.serviceInfo.packageName;
                parseErrors.add(error + ": " + e);
                Slog.w(TAG, error, e);
            }
        }

        if (mKeyphrasePackageMap.isEmpty()) {
            String error = "No suitable enrollment application found";
            parseErrors.add(error);
            Slog.w(TAG, error);
            mKeyphrases = null;
        } else {
            mKeyphrases = mKeyphrasePackageMap.keySet().toArray(
                    new KeyphraseMetadata[0]);
        }

        if (!parseErrors.isEmpty()) {
            mParseError = TextUtils.join("\n", parseErrors);
        }
    }

    private KeyphraseMetadata getKeyphraseMetadataFromApplicationInfo(PackageManager pm,
            ApplicationInfo ai, List<String> parseErrors) {
        XmlResourceParser parser = null;
        String packageName = ai.packageName;
        KeyphraseMetadata keyphraseMetadata = null;
        try {
            parser = ai.loadXmlMetaData(pm, VOICE_KEYPHRASE_META_DATA);
            if (parser == null) {
                String error = "No " + VOICE_KEYPHRASE_META_DATA + " meta-data for " + packageName;
                parseErrors.add(error);
                Slog.w(TAG, error);
                return null;
            }

            Resources res = pm.getResourcesForApplication(ai);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"voice-enrollment-application".equals(nodeName)) {
                String error = "Meta-data does not start with voice-enrollment-application tag for "
                        + packageName;
                parseErrors.add(error);
                Slog.w(TAG, error);
                return null;
            }

            TypedArray array = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.VoiceEnrollmentApplication);
            keyphraseMetadata = getKeyphraseFromTypedArray(array, packageName, parseErrors);
            array.recycle();
        } catch (XmlPullParserException | PackageManager.NameNotFoundException | IOException e) {
            String error = "Error parsing keyphrase enrollment meta-data for " + packageName;
            parseErrors.add(error + ": " + e);
            Slog.w(TAG, error, e);
        } finally {
            if (parser != null) parser.close();
        }
        return keyphraseMetadata;
    }

    private KeyphraseMetadata getKeyphraseFromTypedArray(TypedArray array, String packageName,
            List<String> parseErrors) {
        // Get the keyphrase ID.
        int searchKeyphraseId = array.getInt(
                com.android.internal.R.styleable.VoiceEnrollmentApplication_searchKeyphraseId, -1);
        if (searchKeyphraseId <= 0) {
            String error = "No valid searchKeyphraseId specified in meta-data for " + packageName;
            parseErrors.add(error);
            Slog.w(TAG, error);
            return null;
        }

        // Get the keyphrase text.
        String searchKeyphrase = array.getString(
                com.android.internal.R.styleable.VoiceEnrollmentApplication_searchKeyphrase);
        if (searchKeyphrase == null) {
            String error = "No valid searchKeyphrase specified in meta-data for " + packageName;
            parseErrors.add(error);
            Slog.w(TAG, error);
            return null;
        }

        // Get the supported locales.
        String searchKeyphraseSupportedLocales = array.getString(
                com.android.internal.R.styleable
                        .VoiceEnrollmentApplication_searchKeyphraseSupportedLocales);
        if (searchKeyphraseSupportedLocales == null) {
            String error = "No valid searchKeyphraseSupportedLocales specified in meta-data for "
                    + packageName;
            parseErrors.add(error);
            Slog.w(TAG, error);
            return null;
        }
        ArraySet<Locale> locales = new ArraySet<>();
        // Try adding locales if the locale string is non-empty.
        if (!TextUtils.isEmpty(searchKeyphraseSupportedLocales)) {
            try {
                String[] supportedLocalesDelimited = searchKeyphraseSupportedLocales.split(",");
                for (String s : supportedLocalesDelimited) {
                    locales.add(Locale.forLanguageTag(s));
                }
            } catch (Exception ex) {
                // We catch a generic exception here because we don't want the system service
                // to be affected by a malformed metadata because invalid locales were specified
                // by the system application.
                String error = "Error reading searchKeyphraseSupportedLocales from meta-data for "
                        + packageName;
                parseErrors.add(error);
                Slog.w(TAG, error);
                return null;
            }
        }

        // Get the supported recognition modes.
        int recognitionModes = array.getInt(com.android.internal.R.styleable
                .VoiceEnrollmentApplication_searchKeyphraseRecognitionFlags, -1);
        if (recognitionModes < 0) {
            String error = "No valid searchKeyphraseRecognitionFlags specified in meta-data for "
                    + packageName;
            parseErrors.add(error);
            Slog.w(TAG, error);
            return null;
        }
        return new KeyphraseMetadata(searchKeyphraseId, searchKeyphrase, locales, recognitionModes);
    }

    @NonNull
    public String getParseError() {
        return mParseError;
    }

    /**
     * @return An array of available keyphrases that can be enrolled on the system.
     *         It may be null if no keyphrases can be enrolled.
     */
    @NonNull
    public Collection<KeyphraseMetadata> listKeyphraseMetadata() {
        return Arrays.asList(mKeyphrases);
    }

    /**
     * Returns an intent to launch an service that manages the given keyphrase
     * for the locale.
     *
     * @param action The enrollment related action that this intent is supposed to perform.
     * @param keyphrase The keyphrase that the user needs to be enrolled to.
     * @param locale The locale for which the enrollment needs to be performed.
     * @return An {@link Intent} to manage the keyphrase. This can be null if managing the
     *         given keyphrase/locale combination isn't possible.
     */
    @Nullable
    public Intent getManageKeyphraseIntent(@ManageActions int action, @NonNull String keyphrase,
            @NonNull Locale locale) {
        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        if (mKeyphrasePackageMap == null || mKeyphrasePackageMap.isEmpty()) {
            Slog.w(TAG, "No enrollment application exists");
            return null;
        }

        KeyphraseMetadata keyphraseMetadata = getKeyphraseMetadata(keyphrase, locale);
        if (keyphraseMetadata != null) {
            return new Intent(ACTION_MANAGE_VOICE_KEYPHRASES)
                    .setPackage(mKeyphrasePackageMap.get(keyphraseMetadata))
                    .putExtra(EXTRA_VOICE_KEYPHRASE_HINT_TEXT, keyphrase)
                    .putExtra(EXTRA_VOICE_KEYPHRASE_LOCALE, locale.toLanguageTag())
                    .putExtra(EXTRA_VOICE_KEYPHRASE_ACTION, action);
        }
        return null;
    }

    /**
     * Gets the {@link KeyphraseMetadata} for the given keyphrase and locale, null if any metadata
     * isn't available for the given combination.
     *
     * @param keyphrase The keyphrase that the user needs to be enrolled to.
     * @param locale The locale for which the enrollment needs to be performed.
     *        This is a Java locale, for example "en_US".
     * @return The metadata, if the enrollment client supports the given keyphrase
     *         and locale, null otherwise.
     */
    @Nullable
    public KeyphraseMetadata getKeyphraseMetadata(@NonNull String keyphrase,
            @NonNull Locale locale) {
        Objects.requireNonNull(keyphrase);
        Objects.requireNonNull(locale);
        if (mKeyphrases != null && mKeyphrases.length > 0) {
          for (KeyphraseMetadata keyphraseMetadata : mKeyphrases) {
              // Check if the given keyphrase is supported in the locale provided by
              // the enrollment application.
              if (keyphraseMetadata.supportsPhrase(keyphrase)
                      && keyphraseMetadata.supportsLocale(locale)) {
                  return keyphraseMetadata;
              }
          }
        }
        Slog.w(TAG, "No enrollment application supports the given keyphrase/locale: '"
                + keyphrase + "'/" + locale);
        return null;
    }

    @Override
    public String toString() {
        return "KeyphraseEnrollmentInfo [KeyphrasePackageMap=" + mKeyphrasePackageMap.toString()
                + ", ParseError=" + mParseError + "]";
    }
}
