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

#ifndef AAPT_XML_PULL_PARSER_H
#define AAPT_XML_PULL_PARSER_H

#include <expat.h>

#include <algorithm>
#include <istream>
#include <ostream>
#include <queue>
#include <stack>
#include <string>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

#include "Resource.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"
#include "util/Maybe.h"
#include "xml/XmlUtil.h"

namespace aapt {
namespace xml {

class XmlPullParser : public IPackageDeclStack {
 public:
  enum class Event {
    kBadDocument,
    kStartDocument,
    kEndDocument,

    kStartNamespace,
    kEndNamespace,
    kStartElement,
    kEndElement,
    kText,
    kComment,
    kCdataStart,
    kCdataEnd,
  };

  /**
   * Skips to the next direct descendant node of the given start_depth,
   * skipping namespace nodes.
   *
   * When NextChildNode() returns true, you can expect Comments, Text, and
   * StartElement events.
   */
  static bool NextChildNode(XmlPullParser* parser, size_t start_depth);
  static bool SkipCurrentElement(XmlPullParser* parser);
  static bool IsGoodEvent(Event event);

  explicit XmlPullParser(io::InputStream* in);
  ~XmlPullParser();

  /**
   * Returns the current event that is being processed.
   */
  Event event() const;

  const std::string& error() const;

  /**
   * Note, unlike XmlPullParser, the first call to next() will return
   * StartElement of the first element.
   */
  Event Next();

  //
  // These are available for all nodes.
  //

  const std::string& comment() const;
  size_t line_number() const;
  size_t depth() const;

  /**
   * Returns the character data for a Text event.
   */
  const std::string& text() const;

  //
  // Namespace prefix and URI are available for StartNamespace and EndNamespace.
  //

  const std::string& namespace_prefix() const;
  const std::string& namespace_uri() const;

  //
  // These are available for StartElement and EndElement.
  //

  const std::string& element_namespace() const;
  const std::string& element_name() const;

  /*
   * Uses the current stack of namespaces to resolve the package. Eg:
   * xmlns:app = "http://schemas.android.com/apk/res/com.android.app"
   * ...
   * android:text="@app:string/message"
   *
   * In this case, 'app' will be converted to 'com.android.app'.
   *
   * If xmlns:app="http://schemas.android.com/apk/res-auto", then
   * 'package' will be set to 'defaultPackage'.
   */
  Maybe<ExtractedPackage> TransformPackageAlias(const android::StringPiece& alias) const override;

  //
  // Remaining methods are for retrieving information about attributes
  // associated with a StartElement.
  //
  // Attributes must be in sorted order (according to the less than operator
  // of struct Attribute).
  //

  struct Attribute {
    std::string namespace_uri;
    std::string name;
    std::string value;

    int compare(const Attribute& rhs) const;
    bool operator<(const Attribute& rhs) const;
    bool operator==(const Attribute& rhs) const;
    bool operator!=(const Attribute& rhs) const;
  };

  using const_iterator = std::vector<Attribute>::const_iterator;

  const_iterator begin_attributes() const;
  const_iterator end_attributes() const;
  size_t attribute_count() const;
  const_iterator FindAttribute(android::StringPiece namespace_uri, android::StringPiece name) const;

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlPullParser);

  static void XMLCALL StartNamespaceHandler(void* user_data, const char* prefix,
                                            const char* uri);
  static void XMLCALL StartElementHandler(void* user_data, const char* name,
                                          const char** attrs);
  static void XMLCALL CharacterDataHandler(void* user_data, const char* s,
                                           int len);
  static void XMLCALL EndElementHandler(void* user_data, const char* name);
  static void XMLCALL EndNamespaceHandler(void* user_data, const char* prefix);
  static void XMLCALL CommentDataHandler(void* user_data, const char* comment);
  static void XMLCALL StartCdataSectionHandler(void* user_data);
  static void XMLCALL EndCdataSectionHandler(void* user_data);

  struct EventData {
    Event event;
    size_t line_number;
    size_t depth;
    std::string data1;
    std::string data2;
    std::vector<Attribute> attributes;
  };

  io::InputStream* in_;
  XML_Parser parser_;
  std::queue<EventData> event_queue_;
  std::string error_;
  const std::string empty_;
  size_t depth_;
  std::stack<std::string> namespace_uris_;

  struct PackageDecl {
    std::string prefix;
    ExtractedPackage package;
  };
  std::vector<PackageDecl> package_aliases_;
};

/**
 * Finds the attribute in the current element within the global namespace.
 */
Maybe<android::StringPiece> FindAttribute(const XmlPullParser* parser,
                                          const android::StringPiece& name);

/**
 * Finds the attribute in the current element within the global namespace. The
 * attribute's value
 * must not be the empty string.
 */
Maybe<android::StringPiece> FindNonEmptyAttribute(const XmlPullParser* parser,
                                                  const android::StringPiece& name);

//
// Implementation
//

inline ::std::ostream& operator<<(::std::ostream& out,
                                  XmlPullParser::Event event) {
  switch (event) {
    case XmlPullParser::Event::kBadDocument:
      return out << "BadDocument";
    case XmlPullParser::Event::kStartDocument:
      return out << "StartDocument";
    case XmlPullParser::Event::kEndDocument:
      return out << "EndDocument";
    case XmlPullParser::Event::kStartNamespace:
      return out << "StartNamespace";
    case XmlPullParser::Event::kEndNamespace:
      return out << "EndNamespace";
    case XmlPullParser::Event::kStartElement:
      return out << "StartElement";
    case XmlPullParser::Event::kEndElement:
      return out << "EndElement";
    case XmlPullParser::Event::kText:
      return out << "Text";
    case XmlPullParser::Event::kComment:
      return out << "Comment";
    case XmlPullParser::Event::kCdataStart:
      return out << "CdataStart";
    case XmlPullParser::Event::kCdataEnd:
      return out << "CdataEnd";
  }
  return out;
}

inline bool XmlPullParser::NextChildNode(XmlPullParser* parser, size_t start_depth) {
  Event event;

  // First get back to the start depth.
  while (IsGoodEvent(event = parser->Next()) && parser->depth() > start_depth + 1) {
  }

  // Now look for the first good node.
  while ((event != Event::kEndElement || parser->depth() > start_depth) && IsGoodEvent(event)) {
    switch (event) {
      case Event::kText:
      case Event::kComment:
      case Event::kStartElement:
      case Event::kCdataStart:
      case Event::kCdataEnd:
        return true;
      default:
        break;
    }
    event = parser->Next();
  }
  return false;
}

inline bool XmlPullParser::SkipCurrentElement(XmlPullParser* parser) {
  int depth = 1;
  while (depth > 0) {
    switch (parser->Next()) {
      case Event::kEndDocument:
        return true;
      case Event::kBadDocument:
        return false;
      case Event::kStartElement:
        depth++;
        break;
      case Event::kEndElement:
        depth--;
        break;
      default:
        break;
    }
  }
  return true;
}

inline bool XmlPullParser::IsGoodEvent(XmlPullParser::Event event) {
  return event != Event::kBadDocument && event != Event::kEndDocument;
}

inline int XmlPullParser::Attribute::compare(const Attribute& rhs) const {
  int cmp = namespace_uri.compare(rhs.namespace_uri);
  if (cmp != 0) return cmp;
  return name.compare(rhs.name);
}

inline bool XmlPullParser::Attribute::operator<(const Attribute& rhs) const {
  return compare(rhs) < 0;
}

inline bool XmlPullParser::Attribute::operator==(const Attribute& rhs) const {
  return compare(rhs) == 0;
}

inline bool XmlPullParser::Attribute::operator!=(const Attribute& rhs) const {
  return compare(rhs) != 0;
}

inline XmlPullParser::const_iterator XmlPullParser::FindAttribute(
    android::StringPiece namespace_uri, android::StringPiece name) const {
  const auto end_iter = end_attributes();
  const auto iter = std::lower_bound(
      begin_attributes(), end_iter,
      std::pair<android::StringPiece, android::StringPiece>(namespace_uri, name),
      [](const Attribute& attr,
         const std::pair<android::StringPiece, android::StringPiece>& rhs) -> bool {
        int cmp = attr.namespace_uri.compare(
            0, attr.namespace_uri.size(), rhs.first.data(), rhs.first.size());
        if (cmp < 0) return true;
        if (cmp > 0) return false;
        cmp = attr.name.compare(0, attr.name.size(), rhs.second.data(),
                                rhs.second.size());
        if (cmp < 0) return true;
        return false;
      });

  if (iter != end_iter && namespace_uri == iter->namespace_uri &&
      name == iter->name) {
    return iter;
  }
  return end_iter;
}

}  // namespace xml
}  // namespace aapt

#endif  // AAPT_XML_PULL_PARSER_H
