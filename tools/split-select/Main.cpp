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

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <utils/KeyedVector.h>
#include <utils/Vector.h>

using namespace android;

namespace split {

static void usage() {
    fprintf(stderr,
            "split-select --help\n"
            "split-select --target <config> --split <path/to/apk> [--split <path/to/apk> [...]]\n"
            "split-select --generate --split <path/to/apk> [--split <path/to/apk> [...]]\n"
            "\n"
            "  --help                   Displays more information about this program.\n"
            "  --target <config>        Performs the Split APK selection on the given configuration.\n"
            "  --generate               Generates the logic for selecting the Split APK, in JSON format.\n"
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

class SplitSelector {
public:
    SplitSelector() = default;
    SplitSelector(const Vector<SplitDescription>& splits);

    Vector<SplitDescription> getBestSplits(const SplitDescription& target) const;

    template <typename RuleGenerator>
    KeyedVector<SplitDescription, sp<Rule> > getRules() const;

private:
    Vector<SortedVector<SplitDescription> > mGroups;
};

SplitSelector::SplitSelector(const Vector<SplitDescription>& splits)
    : mGroups(groupByMutualExclusivity(splits)) {
}

static void selectBestFromGroup(const SortedVector<SplitDescription>& splits,
        const SplitDescription& target, Vector<SplitDescription>& splitsOut) {
    SplitDescription bestSplit;
    bool isSet = false;
    const size_t splitCount = splits.size();
    for (size_t j = 0; j < splitCount; j++) {
        const SplitDescription& thisSplit = splits[j];
        if (!thisSplit.match(target)) {
            continue;
        }

        if (!isSet || thisSplit.isBetterThan(bestSplit, target)) {
            isSet = true;
            bestSplit = thisSplit;
        }
    }

    if (isSet) {
        splitsOut.add(bestSplit);
    }
}

Vector<SplitDescription> SplitSelector::getBestSplits(const SplitDescription& target) const {
    Vector<SplitDescription> bestSplits;
    const size_t groupCount = mGroups.size();
    for (size_t i = 0; i < groupCount; i++) {
        selectBestFromGroup(mGroups[i], target, bestSplits);
    }
    return bestSplits;
}

template <typename RuleGenerator>
KeyedVector<SplitDescription, sp<Rule> > SplitSelector::getRules() const {
    KeyedVector<SplitDescription, sp<Rule> > rules;

    const size_t groupCount = mGroups.size();
    for (size_t i = 0; i < groupCount; i++) {
        const SortedVector<SplitDescription>& splits = mGroups[i];
        const size_t splitCount = splits.size();
        for (size_t j = 0; j < splitCount; j++) {
            sp<Rule> rule = Rule::simplify(RuleGenerator::generate(splits, j));
            if (rule != NULL) {
                rules.add(splits[j], rule);
            }
        }
    }
    return rules;
}

Vector<SplitDescription> select(const SplitDescription& target, const Vector<SplitDescription>& splits) {
    const SplitSelector selector(splits);
    return selector.getBestSplits(target);
}

void generate(const KeyedVector<String8, Vector<SplitDescription> >& splits) {
    Vector<SplitDescription> allSplits;
    const size_t apkSplitCount = splits.size();
    for (size_t i = 0; i < apkSplitCount; i++) {
        allSplits.appendVector(splits[i]);
    }
    const SplitSelector selector(allSplits);
    KeyedVector<SplitDescription, sp<Rule> > rules(selector.getRules<RuleGenerator>());

    fprintf(stdout, "[\n");
    for (size_t i = 0; i < apkSplitCount; i++) {
        sp<Rule> masterRule = new Rule();
        masterRule->op = Rule::OR_SUBRULES;
        const Vector<SplitDescription>& splitDescriptions = splits[i];
        const size_t splitDescriptionCount = splitDescriptions.size();
        for (size_t j = 0; j < splitDescriptionCount; j++) {
            masterRule->subrules.add(rules.valueFor(splitDescriptions[j]));
        }
        masterRule = Rule::simplify(masterRule);
        fprintf(stdout, "  {\n    \"path\": \"%s\",\n    \"rules\": %s\n  }%s\n",
                splits.keyAt(i).string(),
                masterRule->toJson(2).string(),
                i < apkSplitCount - 1 ? "," : "");
    }
    fprintf(stdout, "]\n");
}

static void removeRuntimeQualifiers(ConfigDescription* outConfig) {
    outConfig->imsi = 0;
    outConfig->orientation = ResTable_config::ORIENTATION_ANY;
    outConfig->screenWidth = ResTable_config::SCREENWIDTH_ANY;
    outConfig->screenHeight = ResTable_config::SCREENHEIGHT_ANY;
    outConfig->uiMode &= ResTable_config::UI_MODE_NIGHT_ANY;
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
        res.getConfigurations(&configs);
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
                fprintf(stderr, "Malformed library %s\n", dir->getFileName(i).string());
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
    while (argc > 0) {
        const String8 arg(*argv);
        if (arg == "--target") {
            argc--;
            argv++;
            if (argc < 1) {
                fprintf(stderr, "Missing parameter for --split.\n");
                usage();
                return 1;
            }
            targetConfigStr.setTo(*argv);
        } else if (arg == "--split") {
            argc--;
            argv++;
            if (argc < 1) {
                fprintf(stderr, "Missing parameter for --split.\n");
                usage();
                return 1;
            }
            splitApkPaths.add(String8(*argv));
        } else if (arg == "--generate") {
            generateFlag = true;
        } else if (arg == "--help") {
            help();
            return 0;
        } else {
            fprintf(stderr, "Unknown argument '%s'\n", arg.string());
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

    if (splitApkPaths.size() == 0) {
        usage();
        return 1;
    }

    SplitDescription targetSplit;
    if (!generateFlag) {
        if (!SplitDescription::parse(targetConfigStr, &targetSplit)) {
            fprintf(stderr, "Invalid --target config: '%s'\n",
                    targetConfigStr.string());
            usage();
            return 1;
        }

        // We don't want to match on things that will change at run-time
        // (orientation, w/h, etc.).
        removeRuntimeQualifiers(&targetSplit.config);
    }

    KeyedVector<String8, Vector<SplitDescription> > apkPathSplitMap;
    KeyedVector<SplitDescription, String8> splitApkPathMap;
    Vector<SplitDescription> splitConfigs;
    const size_t splitCount = splitApkPaths.size();
    for (size_t i = 0; i < splitCount; i++) {
        Vector<SplitDescription> splits = extractSplitDescriptionsFromApk(splitApkPaths[i]);
        if (splits.isEmpty()) {
            fprintf(stderr, "Invalid --split path: '%s'. No splits found.\n",
                    splitApkPaths[i].string());
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
            fprintf(stderr, "%s\n", matchingSplitPaths[i].string());
        }
    } else {
        generate(apkPathSplitMap);
    }
    return 0;
}

} // namespace split

int main(int argc, char** argv) {
    return split::main(argc, argv);
}
