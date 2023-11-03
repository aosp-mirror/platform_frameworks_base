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

#include <iostream>
#include <string>

#include "util/Util.h"
#include "xml/XmlPullParser.h"
#include "xml/XmlUtil.h"

using ::android::InputStream;
using ::android::StringPiece;

namespace aapt {
namespace xml {

constexpr char kXmlNamespaceSep = 1;

XmlPullParser::XmlPullParser(InputStream* in) : in_(in), empty_(), depth_(0) {
  parser_ = XML_ParserCreateNS(nullptr, kXmlNamespaceSep);
  XML_SetUserData(parser_, this);
  XML_SetElementHandler(parser_, StartElementHandler, EndElementHandler);
  XML_SetNamespaceDeclHandler(parser_, StartNamespaceHandler,
                              EndNamespaceHandler);
  XML_SetCharacterDataHandler(parser_, CharacterDataHandler);
  XML_SetCommentHandler(parser_, CommentDataHandler);
  XML_SetCdataSectionHandler(parser_, StartCdataSectionHandler, EndCdataSectionHandler);
  event_queue_.push(EventData{Event::kStartDocument, 0, depth_++});
}

XmlPullParser::~XmlPullParser() {
  XML_ParserFree(parser_);
}

XmlPullParser::Event XmlPullParser::Next() {
  const Event currentEvent = event();
  if (currentEvent == Event::kBadDocument || currentEvent == Event::kEndDocument) {
    return currentEvent;
  }

  event_queue_.pop();
  while (event_queue_.empty()) {
    const char* buffer = nullptr;
    size_t buffer_size = 0;
    bool done = false;
    if (!in_->Next(reinterpret_cast<const void**>(&buffer), &buffer_size)) {
      if (in_->HadError()) {
        error_ = in_->GetError();
        event_queue_.push(EventData{Event::kBadDocument});
        break;
      }

      done = true;
    }

    if (XML_Parse(parser_, buffer, buffer_size, done) == XML_STATUS_ERROR) {
      error_ = XML_ErrorString(XML_GetErrorCode(parser_));
      event_queue_.push(EventData{Event::kBadDocument});
      break;
    }

    if (done) {
      event_queue_.push(EventData{Event::kEndDocument, 0, 0});
    }
  }

  Event next_event = event();

  // Record namespace prefixes and package names so that we can do our own
  // handling of references that use namespace aliases.
  if (next_event == Event::kStartNamespace ||
      next_event == Event::kEndNamespace) {
    std::optional<ExtractedPackage> result = ExtractPackageFromNamespace(namespace_uri());
    if (next_event == Event::kStartNamespace) {
      if (result) {
        package_aliases_.emplace_back(
            PackageDecl{namespace_prefix(), std::move(result.value())});
      }
    } else {
      if (result) {
        package_aliases_.pop_back();
      }
    }
  }

  return next_event;
}

XmlPullParser::Event XmlPullParser::event() const {
  return event_queue_.front().event;
}

const std::string& XmlPullParser::error() const { return error_; }

const std::string& XmlPullParser::comment() const {
  return event_queue_.front().data1;
}

size_t XmlPullParser::line_number() const {
  return event_queue_.front().line_number;
}

size_t XmlPullParser::depth() const { return event_queue_.front().depth; }

const std::string& XmlPullParser::text() const {
  if (event() != Event::kText) {
    return empty_;
  }
  return event_queue_.front().data1;
}

const std::string& XmlPullParser::namespace_prefix() const {
  const Event current_event = event();
  if (current_event != Event::kStartNamespace &&
      current_event != Event::kEndNamespace) {
    return empty_;
  }
  return event_queue_.front().data1;
}

const std::string& XmlPullParser::namespace_uri() const {
  const Event current_event = event();
  if (current_event != Event::kStartNamespace &&
      current_event != Event::kEndNamespace) {
    return empty_;
  }
  return event_queue_.front().data2;
}

std::optional<ExtractedPackage> XmlPullParser::TransformPackageAlias(StringPiece alias) const {
  if (alias.empty()) {
    return ExtractedPackage{{}, false /*private*/};
  }

  const auto end_iter = package_aliases_.rend();
  for (auto iter = package_aliases_.rbegin(); iter != end_iter; ++iter) {
    if (alias == iter->prefix) {
      if (iter->package.package.empty()) {
        return ExtractedPackage{{}, iter->package.private_namespace};
      }
      return iter->package;
    }
  }
  return {};
}

const std::string& XmlPullParser::element_namespace() const {
  const Event current_event = event();
  if (current_event != Event::kStartElement &&
      current_event != Event::kEndElement) {
    return empty_;
  }
  return event_queue_.front().data1;
}

const std::string& XmlPullParser::element_name() const {
  const Event current_event = event();
  if (current_event != Event::kStartElement &&
      current_event != Event::kEndElement) {
    return empty_;
  }
  return event_queue_.front().data2;
}

const std::vector<XmlPullParser::PackageDecl>& XmlPullParser::package_decls() const {
  return package_aliases_;
}

XmlPullParser::const_iterator XmlPullParser::begin_attributes() const {
  return event_queue_.front().attributes.begin();
}

XmlPullParser::const_iterator XmlPullParser::end_attributes() const {
  return event_queue_.front().attributes.end();
}

size_t XmlPullParser::attribute_count() const {
  if (event() != Event::kStartElement) {
    return 0;
  }
  return event_queue_.front().attributes.size();
}

/**
 * Extracts the namespace and name of an expanded element or attribute name.
 */
static void SplitName(const char* name, std::string* out_ns, std::string* out_name) {
  const char* p = name;
  while (*p != 0 && *p != kXmlNamespaceSep) {
    p++;
  }

  if (*p == 0) {
    out_ns->clear();
    out_name->assign(name);
  } else {
    out_ns->assign(name, (p - name));
    out_name->assign(p + 1);
  }
}

void XMLCALL XmlPullParser::StartNamespaceHandler(void* user_data,
                                                  const char* prefix,
                                                  const char* uri) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);
  std::string namespace_uri = uri != nullptr ? uri : std::string();
  parser->namespace_uris_.push(namespace_uri);
  parser->event_queue_.push(
      EventData{Event::kStartNamespace,
                XML_GetCurrentLineNumber(parser->parser_), parser->depth_++,
                prefix != nullptr ? prefix : std::string(), namespace_uri});
}

void XMLCALL XmlPullParser::StartElementHandler(void* user_data,
                                                const char* name,
                                                const char** attrs) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  EventData data = {Event::kStartElement,
                    XML_GetCurrentLineNumber(parser->parser_),
                    parser->depth_++};
  SplitName(name, &data.data1, &data.data2);

  while (*attrs) {
    Attribute attribute;
    SplitName(*attrs++, &attribute.namespace_uri, &attribute.name);
    attribute.value = *attrs++;

    // Insert in sorted order.
    auto iter = std::lower_bound(data.attributes.begin(), data.attributes.end(),
                                 attribute);
    data.attributes.insert(iter, std::move(attribute));
  }

  // Move the structure into the queue (no copy).
  parser->event_queue_.push(std::move(data));
}

void XMLCALL XmlPullParser::CharacterDataHandler(void* user_data, const char* s,
                                                 int len) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  parser->event_queue_.push(EventData{Event::kText, XML_GetCurrentLineNumber(parser->parser_),
                                      parser->depth_, std::string(s, len)});
}

void XMLCALL XmlPullParser::EndElementHandler(void* user_data,
                                              const char* name) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  EventData data = {Event::kEndElement,
                    XML_GetCurrentLineNumber(parser->parser_),
                    --(parser->depth_)};
  SplitName(name, &data.data1, &data.data2);

  // Move the data into the queue (no copy).
  parser->event_queue_.push(std::move(data));
}

void XMLCALL XmlPullParser::EndNamespaceHandler(void* user_data,
                                                const char* prefix) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  parser->event_queue_.push(
      EventData{Event::kEndNamespace, XML_GetCurrentLineNumber(parser->parser_),
                --(parser->depth_), prefix != nullptr ? prefix : std::string(),
                parser->namespace_uris_.top()});
  parser->namespace_uris_.pop();
}

void XMLCALL XmlPullParser::CommentDataHandler(void* user_data,
                                               const char* comment) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  parser->event_queue_.push(EventData{Event::kComment,
                                      XML_GetCurrentLineNumber(parser->parser_),
                                      parser->depth_, comment});
}

void XMLCALL XmlPullParser::StartCdataSectionHandler(void* user_data) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  parser->event_queue_.push(EventData{Event::kCdataStart,
                                      XML_GetCurrentLineNumber(parser->parser_),
                                      parser->depth_ });
}

void XMLCALL XmlPullParser::EndCdataSectionHandler(void* user_data) {
  XmlPullParser* parser = reinterpret_cast<XmlPullParser*>(user_data);

  parser->event_queue_.push(EventData{Event::kCdataEnd,
                                      XML_GetCurrentLineNumber(parser->parser_),
                                      parser->depth_ });
}

std::optional<StringPiece> FindAttribute(const XmlPullParser* parser, StringPiece name) {
  auto iter = parser->FindAttribute("", name);
  if (iter != parser->end_attributes()) {
    return StringPiece(util::TrimWhitespace(iter->value));
  }
  return {};
}

std::optional<StringPiece> FindNonEmptyAttribute(const XmlPullParser* parser, StringPiece name) {
  auto iter = parser->FindAttribute("", name);
  if (iter != parser->end_attributes()) {
    StringPiece trimmed = util::TrimWhitespace(iter->value);
    if (!trimmed.empty()) {
      return trimmed;
    }
  }
  return {};
}

}  // namespace xml
}  // namespace aapt
