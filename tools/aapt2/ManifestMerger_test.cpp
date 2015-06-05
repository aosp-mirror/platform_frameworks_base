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

#include "ManifestMerger.h"
#include "SourceXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

constexpr const char* kAppManifest = R"EOF(<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-sdk android:minSdkVersion="7" android:targetSdkVersion="21" />
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-feature android:name="android.hardware.GPS" android:required="false" />
    <application android:name="com.android.library.Application">
        <activity android:name="com.android.example.MainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        <service android:name="com.android.library.Service">
            <intent-filter>
                <action android:name="com.android.library.intent.action.SYNC" />
            </intent-filter>
        </service>
    </application>
</manifest>
)EOF";

constexpr const char* kLibManifest = R"EOF(<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-sdk android:minSdkVersion="4" android:targetSdkVersion="21" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.hardware.GPS" />
    <uses-permission android:name="android.permission.GPS" />
    <application android:name="com.android.library.Application">
        <service android:name="com.android.library.Service">
            <intent-filter>
                <action android:name="com.android.library.intent.action.SYNC" />
            </intent-filter>
        </service>
        <provider android:name="com.android.library.DocumentProvider"
                  android:authorities="com.android.library.documents"
                  android:grantUriPermission="true"
                  android:exported="true"
                  android:permission="android.permission.MANAGE_DOCUMENTS"
                  android:enabled="@bool/atLeastKitKat">
            <intent-filter>
                <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
            </intent-filter>
        </provider>
    </application>
</manifest>
)EOF";

constexpr const char* kBadLibManifest = R"EOF(<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-sdk android:minSdkVersion="17" android:targetSdkVersion="22" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.hardware.GPS" />
    <uses-permission android:name="android.permission.GPS" />
    <application android:name="com.android.library.Application2">
        <service android:name="com.android.library.Service">
            <intent-filter>
                <action android:name="com.android.library.intent.action.SYNC_ACTION" />
            </intent-filter>
        </service>
    </application>
</manifest>
)EOF";

TEST(ManifestMergerTest, MergeManifestsSuccess) {
    std::stringstream inA(kAppManifest);
    std::stringstream inB(kLibManifest);

    const Source sourceA = { "AndroidManifest.xml" };
    const Source sourceB = { "lib.apk/AndroidManifest.xml" };
    SourceLogger loggerA(sourceA);
    SourceLogger loggerB(sourceB);

    ManifestMerger merger({});
    EXPECT_TRUE(merger.setAppManifest(sourceA, u"com.android.example",
                xml::inflate(&inA, &loggerA)));
    EXPECT_TRUE(merger.mergeLibraryManifest(sourceB, u"com.android.library",
                xml::inflate(&inB, &loggerB)));
}

TEST(ManifestMergerTest, MergeManifestFail) {
    std::stringstream inA(kAppManifest);
    std::stringstream inB(kBadLibManifest);

    const Source sourceA = { "AndroidManifest.xml" };
    const Source sourceB = { "lib.apk/AndroidManifest.xml" };
    SourceLogger loggerA(sourceA);
    SourceLogger loggerB(sourceB);

    ManifestMerger merger({});
    EXPECT_TRUE(merger.setAppManifest(sourceA, u"com.android.example",
                xml::inflate(&inA, &loggerA)));
    EXPECT_FALSE(merger.mergeLibraryManifest(sourceB, u"com.android.library",
                xml::inflate(&inB, &loggerB)));
}

} // namespace aapt
