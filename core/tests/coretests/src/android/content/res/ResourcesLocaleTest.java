/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.content.res;

import android.os.FileUtils;
import android.os.LocaleList;
import android.test.AndroidTestCase;
import android.util.DisplayMetrics;

import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

public class ResourcesLocaleTest extends AndroidTestCase {

    private String extractApkAndGetPath(int id) throws Exception {
        final Resources resources = getContext().getResources();
        try (InputStream is = resources.openRawResource(id)) {
            File path = new File(getContext().getFilesDir(), resources.getResourceEntryName(id));
            FileUtils.copyToFileOrThrow(is, path);
            return path.getAbsolutePath();
        }
    }

    private Resources createResourcesWithApk(int rawApkId) throws Exception {
        final AssetManager assets = new AssetManager();
        assertTrue(assets.addAssetPath(extractApkAndGetPath(rawApkId)) != 0);

        final DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        return new Resources(assets, dm, new Configuration());
    }

    private static void ensureNoLanguage(Resources resources, String language) {
        final String[] supportedLocales = resources.getAssets().getNonSystemLocales();
        for (String languageTag : supportedLocales) {
            if ("en-XA".equals(languageTag)) {
                continue;
            }
            assertFalse(
                    "supported locales: " + Arrays.toString(supportedLocales),
                    language.equals(Locale.forLanguageTag(languageTag).getLanguage()));
        }
    }

    @SmallTest
    public void testEnglishIsAlwaysConsideredSupported() throws Exception {
        final Resources resources = createResourcesWithApk(R.raw.locales);
        ensureNoLanguage(resources, "en");

        final LocaleList preferredLocales = LocaleList.forLanguageTags("en-US,pl-PL");
        final Configuration config = new Configuration();
        config.setLocales(preferredLocales);

        resources.updateConfiguration(config, null);

        // The APK we loaded has default and Polish languages. If English is first in the list,
        // always take it the default (assumed to be English).
        assertEquals(Locale.forLanguageTag("en-US"),
                resources.getConfiguration().getLocales().get(0));
    }

    @SmallTest
    public void testSelectFirstSupportedLanguage() throws Exception {
        final Resources resources = createResourcesWithApk(R.raw.locales);
        ensureNoLanguage(resources, "fr");

        final LocaleList preferredLocales = LocaleList.forLanguageTags("fr-FR,pl-PL");
        final Configuration config = new Configuration();
        config.setLocales(preferredLocales);

        resources.updateConfiguration(config, null);

        // The APK we loaded has default and Polish languages. We expect the Polish language to
        // therefore be chosen.
        assertEquals(Locale.forLanguageTag("pl-PL"),
                resources.getConfiguration().getLocales().get(0));
    }
}
