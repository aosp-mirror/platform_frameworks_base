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

#include "AppInfo.h"
#include "Logger.h"
#include "ManifestParser.h"
#include "Source.h"
#include "XmlPullParser.h"

#include <string>

namespace aapt {

bool ManifestParser::parse(const Source& source, std::shared_ptr<XmlPullParser> parser,
                           AppInfo* outInfo) {
    SourceLogger logger = { source };

    int depth = 0;
    while (XmlPullParser::isGoodEvent(parser->next())) {
        XmlPullParser::Event event = parser->getEvent();
        if (event == XmlPullParser::Event::kEndElement) {
            depth--;
            continue;
        } else if (event != XmlPullParser::Event::kStartElement) {
            continue;
        }

        depth++;

        const std::u16string& element = parser->getElementName();
        if (depth == 1) {
            if (element == u"manifest") {
                if (!parseManifest(logger, parser, outInfo)) {
                    return false;
                }
            } else {
                logger.error()
                        << "unexpected top-level element '"
                        << element
                        << "'."
                        << std::endl;
                return false;
            }
        } else {
            XmlPullParser::skipCurrentElement(parser.get());
        }
    }

    if (parser->getEvent() == XmlPullParser::Event::kBadDocument) {
            logger.error(parser->getLineNumber())
                << "failed to parse manifest: "
                << parser->getLastError()
                << "."
                << std::endl;
        return false;
    }
    return true;
}

bool ManifestParser::parseManifest(SourceLogger& logger, std::shared_ptr<XmlPullParser> parser,
                                   AppInfo* outInfo) {
    auto attrIter = parser->findAttribute(u"", u"package");
    if (attrIter == parser->endAttributes() || attrIter->value.empty()) {
        logger.error() << "no 'package' attribute found for element <manifest>." << std::endl;
        return false;
    }
    outInfo->package = attrIter->value;
    return true;
}

} // namespace aapt
