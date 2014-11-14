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

#ifndef AAPT_MANIFEST_PARSER_H
#define AAPT_MANIFEST_PARSER_H

#include "AppInfo.h"
#include "Logger.h"
#include "Source.h"
#include "XmlPullParser.h"

namespace aapt {

/*
 * Parses an AndroidManifest.xml file and fills in an AppInfo structure with
 * app data.
 */
class ManifestParser {
public:
    ManifestParser() = default;
    ManifestParser(const ManifestParser&) = delete;

    bool parse(const Source& source, std::shared_ptr<XmlPullParser> parser, AppInfo* outInfo);

private:
    bool parseManifest(SourceLogger& logger, std::shared_ptr<XmlPullParser> parser,
                       AppInfo* outInfo);
};

} // namespace aapt

#endif // AAPT_MANIFEST_PARSER_H
