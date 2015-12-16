/*
* Copyright (C) 2015 The Android Open Source Project
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

package android.content.res;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.DisplayMetrics;
import android.util.LocaleList;

import java.util.Arrays;
import java.util.Locale;

public class ResourcesLocaleResolutionTest extends AndroidTestCase {
    @SmallTest
    public void testGetResolvedLocale_englishIsAlwaysConsideredSupported() {
        // First make sure English has no explicit assets other than the default assets
        final AssetManager assets = getContext().getAssets();
        final String supportedLocales[] = assets.getNonSystemLocales();
        for (String languageTag : supportedLocales) {
            if ("en-XA".equals(languageTag)) {
                continue;
            }
            assertFalse(
                    "supported locales: " + Arrays.toString(supportedLocales),
                    "en".equals(Locale.forLanguageTag(languageTag).getLanguage()));
        }

        final DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        final Configuration cfg = new Configuration();
        cfg.setToDefaults();
        // Avestan and English have no assets, but Persian does.
        cfg.setLocales(LocaleList.forLanguageTags("ae,en,fa"));
        Resources res = new Resources(assets, dm, cfg);
        // We should get English, because it is always considered supported.
        assertEquals("en", res.getResolvedLocale().toLanguageTag());
    }
}

