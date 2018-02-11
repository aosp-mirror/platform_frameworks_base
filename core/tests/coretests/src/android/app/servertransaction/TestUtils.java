/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.app.ResultInfo;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.MergedConfiguration;

import com.android.internal.content.ReferrerIntent;

import java.util.ArrayList;
import java.util.List;

class TestUtils {

    static Configuration config() {
        Configuration config = new Configuration();
        config.densityDpi = 10;
        config.fontScale = 0.3f;
        config.screenHeightDp = 15;
        config.orientation = ORIENTATION_LANDSCAPE;
        return config;
    }

    static MergedConfiguration mergedConfig() {
        Configuration config = config();
        Configuration overrideConfig = new Configuration();
        overrideConfig.densityDpi = 30;
        overrideConfig.screenWidthDp = 40;
        overrideConfig.smallestScreenWidthDp = 15;
        return new MergedConfiguration(config, overrideConfig);
    }

    static List<ResultInfo> resultInfoList() {
        String resultWho1 = "resultWho1";
        int requestCode1 = 7;
        int resultCode1 = 4;
        Intent data1 = new Intent("action1");
        ResultInfo resultInfo1 = new ResultInfo(resultWho1, requestCode1, resultCode1, data1);

        String resultWho2 = "resultWho2";
        int requestCode2 = 8;
        int resultCode2 = 6;
        Intent data2 = new Intent("action2");
        ResultInfo resultInfo2 = new ResultInfo(resultWho2, requestCode2, resultCode2, data2);

        List<ResultInfo> resultInfoList = new ArrayList<>();
        resultInfoList.add(resultInfo1);
        resultInfoList.add(resultInfo2);

        return resultInfoList;
    }

    static List<ReferrerIntent> referrerIntentList() {
        Intent intent1 = new Intent("action1");
        ReferrerIntent referrerIntent1 = new ReferrerIntent(intent1, "referrer1");

        Intent intent2 = new Intent("action2");
        ReferrerIntent referrerIntent2 = new ReferrerIntent(intent2, "referrer2");

        List<ReferrerIntent> referrerIntents = new ArrayList<>();
        referrerIntents.add(referrerIntent1);
        referrerIntents.add(referrerIntent2);

        return referrerIntents;
    }
}
