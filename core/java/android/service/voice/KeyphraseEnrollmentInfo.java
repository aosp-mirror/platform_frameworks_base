/*
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

package android.service.voice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/** @hide */
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
     * Activity Action: Show activity for managing the keyphrases for hotword detection.
     * This needs to be defined by an activity that supports enrolling users for hotword/keyphrase
     * detection.
     */
    public static final String ACTION_MANAGE_VOICE_KEYPHRASES =
            "com.android.intent.action.MANAGE_VOICE_KEYPHRASES";
    /**
     * Intent extra: The intent extra for un-enrolling a user for a particular keyphrase.
     */
    public static final String EXTRA_VOICE_KEYPHRASE_UNENROLL =
            "com.android.intent.extra.VOICE_KEYPHRASE_UNENROLL";
    /**
     * Intent extra: The hint text to be shown on the voice keyphrase management UI.
     */
    public static final String EXTRA_VOICE_KEYPHRASE_HINT_TEXT =
            "com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT";
    /**
     * Intent extra: The voice locale to use while managing the keyphrase.
     */
    public static final String EXTRA_VOICE_KEYPHRASE_LOCALE =
            "com.android.intent.extra.VOICE_KEYPHRASE_LOCALE";

    private KeyphraseInfo[] mKeyphrases;
    private String mEnrollmentPackage;
    private String mParseError;

    public KeyphraseEnrollmentInfo(PackageManager pm) {
        // Find the apps that supports enrollment for hotword keyhphrases,
        // Pick a privileged app and obtain the information about the supported keyphrases
        // from its metadata.
        List<ResolveInfo> ris = pm.queryIntentActivities(
                new Intent(ACTION_MANAGE_VOICE_KEYPHRASES), PackageManager.MATCH_DEFAULT_ONLY);
        if (ris == null || ris.isEmpty()) {
            // No application capable of enrolling for voice keyphrases is present.
            mParseError = "No enrollment application found";
            return;
        }

        boolean found = false;
        ApplicationInfo ai = null;
        for (ResolveInfo ri : ris) {
            try {
                ai = pm.getApplicationInfo(
                        ri.activityInfo.packageName, PackageManager.GET_META_DATA);
                if ((ai.flags & ApplicationInfo.FLAG_PRIVILEGED) == 0) {
                    // The application isn't privileged (/system/priv-app).
                    // The enrollment application needs to be a privileged system app.
                    Slog.w(TAG, ai.packageName + "is not a privileged system app");
                    continue;
                }
                if (!Manifest.permission.MANAGE_VOICE_KEYPHRASES.equals(ai.permission)) {
                    // The application trying to manage keyphrases doesn't
                    // require the MANAGE_VOICE_KEYPHRASES permission.
                    Slog.w(TAG, ai.packageName + " does not require MANAGE_VOICE_KEYPHRASES");
                    continue;
                }
                mEnrollmentPackage = ai.packageName;
                found = true;
                break;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "error parsing voice enrollment meta-data", e);
            }
        }

        if (!found) {
            mKeyphrases = null;
            mParseError = "No suitable enrollment application found";
            return;
        }

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, VOICE_KEYPHRASE_META_DATA);
            if (parser == null) {
                mParseError = "No " + VOICE_KEYPHRASE_META_DATA + " meta-data for "
                        + ai.packageName;
                return;
            }

            Resources res = pm.getResourcesForApplication(ai);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"voice-enrollment-application".equals(nodeName)) {
                mParseError = "Meta-data does not start with voice-enrollment-application tag";
                return;
            }

            TypedArray array = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.VoiceEnrollmentApplication);
            int searchKeyphraseId = array.getInt(
                    com.android.internal.R.styleable.VoiceEnrollmentApplication_searchKeyphraseId,
                    -1);
            if (searchKeyphraseId != -1) {
                String searchKeyphrase = array.getString(com.android.internal.R.styleable
                        .VoiceEnrollmentApplication_searchKeyphrase);
                String searchKeyphraseSupportedLocales =
                        array.getString(com.android.internal.R.styleable
                                .VoiceEnrollmentApplication_searchKeyphraseSupportedLocales);
                String[] supportedLocales = new String[0];
                // Get all the supported locales from the comma-delimted string.
                if (searchKeyphraseSupportedLocales != null
                        && !searchKeyphraseSupportedLocales.isEmpty()) {
                    supportedLocales = searchKeyphraseSupportedLocales.split(",");
                }
                mKeyphrases = new KeyphraseInfo[1];
                mKeyphrases[0] = new KeyphraseInfo(
                        searchKeyphraseId, searchKeyphrase, supportedLocales);
            } else {
                mParseError = "searchKeyphraseId not specified in meta-data";
                return;
            }
        } catch (XmlPullParserException e) {
            mParseError = "Error parsing keyphrase enrollment meta-data: " + e;
            Slog.w(TAG, "error parsing keyphrase enrollment meta-data", e);
            return;
        } catch (IOException e) {
            mParseError = "Error parsing keyphrase enrollment meta-data: " + e;
            Slog.w(TAG, "error parsing keyphrase enrollment meta-data", e);
            return;
        } catch (PackageManager.NameNotFoundException e) {
            mParseError = "Error parsing keyphrase enrollment meta-data: " + e;
            Slog.w(TAG, "error parsing keyphrase enrollment meta-data", e);
            return;
        } finally {
            if (parser != null) parser.close();
        }
    }

    public String getParseError() {
        return mParseError;
    }

    /**
     * @return An array of available keyphrases that can be enrolled on the system.
     *         It may be null if no keyphrases can be enrolled.
     */
    public KeyphraseInfo[] getKeyphrases() {
        return mKeyphrases;
    }

    /**
     * Returns an intent to launch an activity that manages the given keyphrase
     * for the locale.
     *
     * @param enroll Indicates if the intent should enroll the user or un-enroll them.
     * @param keyphrase The keyphrase that the user needs to be enrolled to.
     * @param locale The locale for which the enrollment needs to be performed.
     * @return An {@link Intent} to manage the keyphrase. This can be null if managing the
     *         given keyphrase/locale combination isn't possible.
     */
    public Intent getManageKeyphraseIntent(boolean enroll, String keyphrase, String locale) {
        if (mEnrollmentPackage == null || mEnrollmentPackage.isEmpty()) {
            Slog.w(TAG, "No enrollment application exists");
            return null;
        }

        if (isKeyphraseEnrollmentSupported(keyphrase, locale)) {
            Intent intent = new Intent(ACTION_MANAGE_VOICE_KEYPHRASES)
                    .setPackage(mEnrollmentPackage)
                    .putExtra(EXTRA_VOICE_KEYPHRASE_HINT_TEXT, keyphrase)
                    .putExtra(EXTRA_VOICE_KEYPHRASE_LOCALE, locale);
            if (!enroll) intent.putExtra(EXTRA_VOICE_KEYPHRASE_UNENROLL, true);
            return intent;
        }
        return null;
    }

    /**
     * Indicates if enrollment is supported for the given keyphrase & locale.
     *
     * @param keyphrase The keyphrase that the user needs to be enrolled to.
     * @param locale The locale for which the enrollment needs to be performed.
     * @return true, if an enrollment client supports the given keyphrase and the given locale.
     */
    public boolean isKeyphraseEnrollmentSupported(String keyphrase, String locale) {
        if (mKeyphrases == null || mKeyphrases.length == 0) {
            Slog.w(TAG, "Enrollment application doesn't support keyphrases");
            return false;
        }
        for (KeyphraseInfo keyphraseInfo : mKeyphrases) {
            // Check if the given keyphrase is supported in the locale provided by
            // the enrollment application.
            String supportedKeyphrase = keyphraseInfo.keyphrase;
            if (supportedKeyphrase.equalsIgnoreCase(keyphrase)
                    && keyphraseInfo.supportedLocales.contains(locale)) {
                return true;
            }
        }
        Slog.w(TAG, "Enrollment application doesn't support the given keyphrase");
        return false;
    }
}
