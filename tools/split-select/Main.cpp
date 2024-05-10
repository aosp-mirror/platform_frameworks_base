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

#include <algorithm>
#include <cstdio>

#include "aapt/AaptUtil.h"

#include "Grouper.h"
#include "Rule.h"
#include "RuleGenerator.h"
#include "SplitDescription.h"
#include "SplitSelector.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <utils/KeyedVector.h>
#include <utils/Vector.h>

using namespace android;

namespace split {

static void usage() {
    fprintf(stderr,
            "split-select --help\n"
            "split-select --target <config> --base <path/to/apk> [--split <path/to/apk> [...]]\n"
            "split-select --generate --base <path/to/apk> [--split <path/to/apk> [...]]\n"
            "\n"
            "  --help                   Displays more information about this program.\n"
            "  --target <config>        Performs the Split APK selection on the given configuration.\n"
            "  --generate               Generates the logic for selecting the Split APK, in JSON format.\n"
            "  --base <path/to/apk>     Specifies the base APK, from which all Split APKs must be based off.\n"
            "  --split <path/to/apk>    Includes a Split APK in the selection process.\n"
            "\n"
            "  Where <config> is an extended AAPT resource qualifier of the form\n"
            "  'resource-qualifiers:extended-qualifiers', where 'resource-qualifiers' is an AAPT resource\n"
            "  qualifier (ex: en-rUS-sw600dp-xhdpi), and 'extended-qualifiers' is an ordered list of one\n"
            "  qualifier (or none) from each category:\n"
            "    Architecture: armeabi, armeabi-v7a, arm64-v8a, x86, x86_64, mips\n");
}

static void help() {
    usage();
    fprintf(stderr, "\n"
            "  Generates the logic for selecting a Split APK given some target Android device configuration.\n"
            "  Using the flag --generate will emit a JSON encoded tree of rules that must be satisfied in order\n"
            "  to install the given Split APK. Using the flag --target along with the device configuration\n"
            "  will emit the set of Split APKs to install, following the same logic that would have been emitted\n"
            "  via JSON.\n");
}

Vector<SplitDescription> select(const SplitDescription& target, const Vector<SplitDescription>& splits) {
    const SplitSelector selector(splits);
    return selector.getBestSplits(target);
}

void generate(const KeyedVector<String8, Vector<SplitDescription> >& splits, const String8& base) {
    Vector<SplitDescription> allSplits;
    const size_t apkSplitCount = splits.size();
    for (size_t i = 0; i < apkSplitCount; i++) {
        allSplits.appendVector(splits[i]);
    }
    const SplitSelector selector(allSplits);
    KeyedVector<SplitDescription, sp<Rule> > rules(selector.getRules());

    bool first = true;
    fprintf(stdout, "[\n");
    for (size_t i = 0; i < apkSplitCount; i++) {
        if (splits.keyAt(i) == base) {
            // Skip the base.
            continue;
        }

        if (!first) {
            fprintf(stdout, ",\n");
        }
        first = false;

        sp<Rule> masterRule = new Rule();
        masterRule->op = Rule::OR_SUBRULES;
        const Vector<SplitDescription>& splitDescriptions = splits[i];
        const size_t splitDescriptionCount = splitDescriptions.size();
        for (size_t j = 0; j < splitDescriptionCount; j++) {
            masterRule->subrules.add(rules.valueFor(splitDescriptions[j]));
        }
        masterRule = Rule::simplify(masterRule);
        fprintf(stdout, "  {\n    \"path\": \"%s\",\n    \"rules\": %s\n  }",
                splits.keyAt(i).c_str(), masterRule->toJson(2).c_str());
    }
    fprintf(stdout, "\n]\n");
}

static void removeRuntimeQualifiers(ConfigDescription* outConfig) {
    outConfig->imsi = 0;
    outConfig->orientation = ResTable_config::ORIENTATION_ANY;
    outConfig->screenWidth = ResTable_config::SCREENWIDTH_ANY;
    outConfig->screenHeight = ResTable_config::SCREENHEIGHT_ANY;
    outConfig->uiMode &= ResTable_config::UI_MODE_NIGHT_ANY;
}

struct AppInfo {
    int versionCode;
    int minSdkVersion;
    bool multiArch;
};

static bool getAppInfo(const String8& path, AppInfo& outInfo) {
    memset(&outInfo, 0, sizeof(outInfo));

    AssetManager assetManager;
    int32_t cookie = 0;
    if (!assetManager.addAssetPath(path, &cookie)) {
        return false;
    }

    Asset* asset = assetManager.openNonAsset(cookie, "AndroidManifest.xml", Asset::ACCESS_BUFFER);
    if (asset == NULL) {
        return false;
    }

    ResXMLTree xml;
    if (xml.setTo(asset->getBuffer(true), asset->getLength(), false) != NO_ERROR) {
        delete asset;
        return false;
    }

    const String16 kAndroidNamespace("http://schemas.android.com/apk/res/android");
    const String16 kManifestTag("manifest");
    const String16 kApplicationTag("application");
    const String16 kUsesSdkTag("uses-sdk");
    const String16 kVersionCodeAttr("versionCode");
    const String16 kMultiArchAttr("multiArch");
    const String16 kMinSdkVersionAttr("minSdkVersion");

    ResXMLParser::event_code_t event;
    while ((event = xml.next()) != ResXMLParser::BAD_DOCUMENT &&
            event != ResXMLParser::END_DOCUMENT) {
        if (event != ResXMLParser::START_TAG) {
            continue;
        }

        size_t len;
        const char16_t* name = xml.getElementName(&len);
        String16 name16(name, len);
        if (name16 == kManifestTag) {
            ssize_t idx = xml.indexOfAttribute(kAndroidNamespace.c_str(), kAndroidNamespace.size(),
                                               kVersionCodeAttr.c_str(), kVersionCodeAttr.size());
            if (idx >= 0) {
                outInfo.versionCode = xml.getAttributeData(idx);
            }

        } else if (name16 == kApplicationTag) {
            ssize_t idx = xml.indexOfAttribute(kAndroidNamespace.c_str(), kAndroidNamespace.size(),
                                               kMultiArchAttr.c_str(), kMultiArchAttr.size());
            if (idx >= 0) {
                outInfo.multiArch = xml.getAttributeData(idx) != 0;
            }

        } else if (name16 == kUsesSdkTag) {
            ssize_t idx =
                    xml.indexOfAttribute(kAndroidNamespace.c_str(), kAndroidNamespace.size(),
                                         kMinSdkVersionAttr.c_str(), kMinSdkVersionAttr.size());
            if (idx >= 0) {
                uint16_t type = xml.getAttributeDataType(idx);
                if (type >= Res_value::TYPE_FIRST_INT && type <= Res_value::TYPE_LAST_INT) {
                    outInfo.minSdkVersion = xml.getAttributeData(idx);
                } else if (type == Res_value::TYPE_STRING) {
                    auto minSdk8 = xml.getStrings().string8ObjectAt(idx);
                    if (!minSdk8.has_value()) {
                        fprintf(stderr, "warning: failed to retrieve android:minSdkVersion.\n");
                    } else {
                        char *endPtr;
                        int minSdk = strtol(minSdk8->c_str(), &endPtr, 10);
                        if (endPtr != minSdk8->c_str() + minSdk8->size()) {
                            fprintf(stderr, "warning: failed to parse android:minSdkVersion '%s'\n",
                                    minSdk8->c_str());
                        } else {
                            outInfo.minSdkVersion = minSdk;
                        }
                    }
                } else {
                    fprintf(stderr, "warning: unrecognized value for android:minSdkVersion.\n");
                }
            }
        }
    }

    delete asset;
    return true;
}

static Vector<SplitDescription> extractSplitDescriptionsFromApk(const String8& path) {
    AssetManager assetManager;
    Vector<SplitDescription> splits;
    int32_t cookie = 0;
    if (!assetManager.addAssetPath(path, &cookie)) {
        return splits;
    }

    const ResTable& res = assetManager.getResources(false);
    if (res.getError() == NO_ERROR) {
        Vector<ResTable_config> configs;
        res.getConfigurations(&configs, true);
        const size_t configCount = configs.size();
        for (size_t i = 0; i < configCount; i++) {
            splits.add();
            splits.editTop().config = configs[i];
        }
    }

    AssetDir* dir = assetManager.openNonAssetDir(cookie, "lib");
    if (dir != NULL) {
        const size_t fileCount = dir->getFileCount();
        for (size_t i = 0; i < fileCount; i++) {
            splits.add();
            Vector<String8> parts = AaptUtil::splitAndLowerCase(dir->getFileName(i), '-');
            if (parseAbi(parts, 0, &splits.editTop()) < 0) {
                fprintf(stderr, "Malformed library %s\n", dir->getFileName(i).c_str());
                splits.pop();
            }
        }
        delete dir;
    }
    return splits;
}

static int main(int argc, char** argv) {
    // Skip over the first argument.
    argc--;
    argv++;

    bool generateFlag = false;
    String8 targetConfigStr;
    Vector<String8> splitApkPaths;
    String8 baseApkPath;
    while (argc > 0) {
        const String8 arg(*argv);
        if (arg == "--target") {
            argc--;
            argv++;
            if (argc < 1) {
                fprintf(stderr, "error: missing parameter for --target.\n");
                usage();
                return 1;
            }
            targetConfigStr = *argv;
        } else if (arg == "--split") {
            argc--;
            argv++;
            if (argc < 1) {
                fprintf(stderr, "error: missing parameter for --split.\n");
                usage();
                return 1;
            }
            splitApkPaths.add(String8(*argv));
        } else if (arg == "--base") {
            argc--;
            argv++;
            if (argc < 1) {
                fprintf(stderr, "error: missing parameter for --base.\n");
                usage();
                return 1;
            }

            if (baseApkPath.size() > 0) {
                fprintf(stderr, "error: multiple --base flags not allowed.\n");
                usage();
                return 1;
            }
            baseApkPath = *argv;
        } else if (arg == "--generate") {
            generateFlag = true;
        } else if (arg == "--help") {
            help();
            return 0;
        } else {
            fprintf(stderr, "error: unknown argument '%s'.\n", arg.c_str());
            usage();
            return 1;
        }
        argc--;
        argv++;
    }

    if (!generateFlag && targetConfigStr == "") {
        usage();
        return 1;
    }

    if (baseApkPath.size() == 0) {
        fprintf(stderr, "error: missing --base argument.\n");
        usage();
        return 1;
    }

    // Find out some details about the base APK.
    AppInfo baseAppInfo;
    if (!getAppInfo(baseApkPath, baseAppInfo)) {
        fprintf(stderr, "error: unable to read base APK: '%s'.\n", baseApkPath.c_str());
        return 1;
    }

    SplitDescription targetSplit;
    if (!generateFlag) {
        if (!SplitDescription::parse(targetConfigStr, &targetSplit)) {
            fprintf(stderr, "error: invalid --target config: '%s'.\n", targetConfigStr.c_str());
            usage();
            return 1;
        }

        // We don't want to match on things that will change at run-time
        // (orientation, w/h, etc.).
        removeRuntimeQualifiers(&targetSplit.config);
    }

    splitApkPaths.add(baseApkPath);

    KeyedVector<String8, Vector<SplitDescription> > apkPathSplitMap;
    KeyedVector<SplitDescription, String8> splitApkPathMap;
    Vector<SplitDescription> splitConfigs;
    const size_t splitCount = splitApkPaths.size();
    for (size_t i = 0; i < splitCount; i++) {
        Vector<SplitDescription> splits = extractSplitDescriptionsFromApk(splitApkPaths[i]);
        if (splits.isEmpty()) {
            fprintf(stderr, "error: invalid --split path: '%s'. No splits found.\n",
                    splitApkPaths[i].c_str());
            usage();
            return 1;
        }
        apkPathSplitMap.replaceValueFor(splitApkPaths[i], splits);
        const size_t apkSplitDescriptionCount = splits.size();
        for (size_t j = 0; j < apkSplitDescriptionCount; j++) {
            splitApkPathMap.replaceValueFor(splits[j], splitApkPaths[i]);
        }
        splitConfigs.appendVector(splits);
    }

    if (!generateFlag) {
        Vector<SplitDescription> matchingConfigs = select(targetSplit, splitConfigs);
        const size_t matchingConfigCount = matchingConfigs.size();
        SortedVector<String8> matchingSplitPaths;
        for (size_t i = 0; i < matchingConfigCount; i++) {
            matchingSplitPaths.add(splitApkPathMap.valueFor(matchingConfigs[i]));
        }

        const size_t matchingSplitApkPathCount = matchingSplitPaths.size();
        for (size_t i = 0; i < matchingSplitApkPathCount; i++) {
            if (matchingSplitPaths[i] != baseApkPath) {
                fprintf(stdout, "%s\n", matchingSplitPaths[i].c_str());
            }
        }
    } else {
        generate(apkPathSplitMap, baseApkPath);
    }
    return 0;
}

} // namespace split

int main(int argc, char** argv) {
    return split::main(argc, argv);
}
