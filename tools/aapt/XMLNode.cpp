//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#include "XMLNode.h"
#include "ResourceTable.h"
#include "pseudolocalize.h"

#include <utils/ByteOrder.h>
#include <errno.h>
#include <string.h>

#ifndef HAVE_MS_C_RUNTIME
#define O_BINARY 0
#endif

#define NOISY(x) //x
#define NOISY_PARSE(x) //x

const char* const RESOURCES_ROOT_NAMESPACE = "http://schemas.android.com/apk/res/";
const char* const RESOURCES_ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
const char* const RESOURCES_AUTO_PACKAGE_NAMESPACE = "http://schemas.android.com/apk/res-auto";
const char* const RESOURCES_ROOT_PRV_NAMESPACE = "http://schemas.android.com/apk/prv/res/";

const char* const XLIFF_XMLNS = "urn:oasis:names:tc:xliff:document:1.2";
const char* const ALLOWED_XLIFF_ELEMENTS[] = {
        "bpt",
        "ept",
        "it",
        "ph",
        "g",
        "bx",
        "ex",
        "x"
    };

bool isWhitespace(const char16_t* str)
{
    while (*str != 0 && *str < 128 && isspace(*str)) {
        str++;
    }
    return *str == 0;
}

static const String16 RESOURCES_PREFIX(RESOURCES_ROOT_NAMESPACE);
static const String16 RESOURCES_PREFIX_AUTO_PACKAGE(RESOURCES_AUTO_PACKAGE_NAMESPACE);
static const String16 RESOURCES_PRV_PREFIX(RESOURCES_ROOT_PRV_NAMESPACE);
static const String16 RESOURCES_TOOLS_NAMESPACE("http://schemas.android.com/tools");

String16 getNamespaceResourcePackage(String16 appPackage, String16 namespaceUri, bool* outIsPublic)
{
    //printf("%s starts with %s?\n", String8(namespaceUri).string(),
    //       String8(RESOURCES_PREFIX).string());
    size_t prefixSize;
    bool isPublic = true;
    if(namespaceUri.startsWith(RESOURCES_PREFIX_AUTO_PACKAGE)) {
        NOISY(printf("Using default application package: %s -> %s\n", String8(namespaceUri).string(), String8(appPackage).string()));
        isPublic = true;
        return appPackage;
    } else if (namespaceUri.startsWith(RESOURCES_PREFIX)) {
        prefixSize = RESOURCES_PREFIX.size();
    } else if (namespaceUri.startsWith(RESOURCES_PRV_PREFIX)) {
        isPublic = false;
        prefixSize = RESOURCES_PRV_PREFIX.size();
    } else {
        if (outIsPublic) *outIsPublic = isPublic; // = true
        return String16();
    }

    //printf("YES!\n");
    //printf("namespace: %s\n", String8(String16(namespaceUri, namespaceUri.size()-prefixSize, prefixSize)).string());
    if (outIsPublic) *outIsPublic = isPublic;
    return String16(namespaceUri, namespaceUri.size()-prefixSize, prefixSize);
}

status_t hasSubstitutionErrors(const char* fileName,
                               ResXMLTree* inXml,
                               String16 str16)
{
    const char16_t* str = str16.string();
    const char16_t* p = str;
    const char16_t* end = str + str16.size();

    bool nonpositional = false;
    int argCount = 0;

    while (p < end) {
        /*
         * Look for the start of a Java-style substitution sequence.
         */
        if (*p == '%' && p + 1 < end) {
            p++;

            // A literal percent sign represented by %%
            if (*p == '%') {
                p++;
                continue;
            }

            argCount++;

            if (*p >= '0' && *p <= '9') {
                do {
                    p++;
                } while (*p >= '0' && *p <= '9');
                if (*p != '$') {
                    // This must be a size specification instead of position.
                    nonpositional = true;
                }
            } else if (*p == '<') {
                // Reusing last argument; bad idea since it can be re-arranged.
                nonpositional = true;
                p++;

                // Optionally '$' can be specified at the end.
                if (p < end && *p == '$') {
                    p++;
                }
            } else {
                nonpositional = true;
            }

            // Ignore flags and widths
            while (p < end && (*p == '-' ||
                    *p == '#' ||
                    *p == '+' ||
                    *p == ' ' ||
                    *p == ',' ||
                    *p == '(' ||
                    (*p >= '0' && *p <= '9'))) {
                p++;
            }

            /*
             * This is a shortcut to detect strings that are going to Time.format()
             * instead of String.format()
             *
             * Comparison of String.format() and Time.format() args:
             *
             * String: ABC E GH  ST X abcdefgh  nost x
             *   Time:    DEFGHKMS W Za  d   hkm  s w yz
             *
             * Therefore we know it's definitely Time if we have:
             *     DFKMWZkmwyz
             */
            if (p < end) {
                switch (*p) {
                case 'D':
                case 'F':
                case 'K':
                case 'M':
                case 'W':
                case 'Z':
                case 'k':
                case 'm':
                case 'w':
                case 'y':
                case 'z':
                    return NO_ERROR;
                }
            }
        }

        p++;
    }

    /*
     * If we have more than one substitution in this string and any of them
     * are not in positional form, give the user an error.
     */
    if (argCount > 1 && nonpositional) {
        SourcePos(String8(fileName), inXml->getLineNumber()).error(
                "Multiple substitutions specified in non-positional format; "
                "did you mean to add the formatted=\"false\" attribute?\n");
        return NOT_ENOUGH_DATA;
    }

    return NO_ERROR;
}

status_t parseStyledString(Bundle* bundle,
                           const char* fileName,
                           ResXMLTree* inXml,
                           const String16& endTag,
                           String16* outString,
                           Vector<StringPool::entry_style_span>* outSpans,
                           bool isFormatted,
                           bool pseudolocalize)
{
    Vector<StringPool::entry_style_span> spanStack;
    String16 curString;
    String16 rawString;
    const char* errorMsg;
    int xliffDepth = 0;
    bool firstTime = true;

    size_t len;
    ResXMLTree::event_code_t code;
    while ((code=inXml->next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {

        if (code == ResXMLTree::TEXT) {
            String16 text(inXml->getText(&len));
            if (firstTime && text.size() > 0) {
                firstTime = false;
                if (text.string()[0] == '@') {
                    // If this is a resource reference, don't do the pseudoloc.
                    pseudolocalize = false;
                }
            }
            if (xliffDepth == 0 && pseudolocalize) {
                std::string orig(String8(text).string());
                std::string pseudo = pseudolocalize_string(orig);
                curString.append(String16(String8(pseudo.c_str())));
            } else {
                if (isFormatted && hasSubstitutionErrors(fileName, inXml, text) != NO_ERROR) {
                    return UNKNOWN_ERROR;
                } else {
                    curString.append(text);
                }
            }
        } else if (code == ResXMLTree::START_TAG) {
            const String16 element16(inXml->getElementName(&len));
            const String8 element8(element16);

            size_t nslen;
            const uint16_t* ns = inXml->getElementNamespace(&nslen);
            if (ns == NULL) {
                ns = (const uint16_t*)"\0\0";
                nslen = 0;
            }
            const String8 nspace(String16(ns, nslen));
            if (nspace == XLIFF_XMLNS) {
                const int N = sizeof(ALLOWED_XLIFF_ELEMENTS)/sizeof(ALLOWED_XLIFF_ELEMENTS[0]);
                for (int i=0; i<N; i++) {
                    if (element8 == ALLOWED_XLIFF_ELEMENTS[i]) {
                        xliffDepth++;
                        // in this case, treat it like it was just text, in other words, do nothing
                        // here and silently drop this element
                        goto moveon;
                    }
                }
                {
                    SourcePos(String8(fileName), inXml->getLineNumber()).error(
                            "Found unsupported XLIFF tag <%s>\n",
                            element8.string());
                    return UNKNOWN_ERROR;
                }
moveon:
                continue;
            }

            if (outSpans == NULL) {
                SourcePos(String8(fileName), inXml->getLineNumber()).error(
                        "Found style tag <%s> where styles are not allowed\n", element8.string());
                return UNKNOWN_ERROR;
            }

            if (!ResTable::collectString(outString, curString.string(),
                                         curString.size(), false, &errorMsg, true)) {
                SourcePos(String8(fileName), inXml->getLineNumber()).error("%s (in %s)\n",
                        errorMsg, String8(curString).string());
                return UNKNOWN_ERROR;
            }
            rawString.append(curString);
            curString = String16();

            StringPool::entry_style_span span;
            span.name = element16;
            for (size_t ai=0; ai<inXml->getAttributeCount(); ai++) {
                span.name.append(String16(";"));
                const char16_t* str = inXml->getAttributeName(ai, &len);
                span.name.append(str, len);
                span.name.append(String16("="));
                str = inXml->getAttributeStringValue(ai, &len);
                span.name.append(str, len);
            }
            //printf("Span: %s\n", String8(span.name).string());
            span.span.firstChar = span.span.lastChar = outString->size();
            spanStack.push(span);

        } else if (code == ResXMLTree::END_TAG) {
            size_t nslen;
            const uint16_t* ns = inXml->getElementNamespace(&nslen);
            if (ns == NULL) {
                ns = (const uint16_t*)"\0\0";
                nslen = 0;
            }
            const String8 nspace(String16(ns, nslen));
            if (nspace == XLIFF_XMLNS) {
                xliffDepth--;
                continue;
            }
            if (!ResTable::collectString(outString, curString.string(),
                                         curString.size(), false, &errorMsg, true)) {
                SourcePos(String8(fileName), inXml->getLineNumber()).error("%s (in %s)\n",
                        errorMsg, String8(curString).string());
                return UNKNOWN_ERROR;
            }
            rawString.append(curString);
            curString = String16();

            if (spanStack.size() == 0) {
                if (strcmp16(inXml->getElementName(&len), endTag.string()) != 0) {
                    SourcePos(String8(fileName), inXml->getLineNumber()).error(
                            "Found tag %s where <%s> close is expected\n",
                            String8(inXml->getElementName(&len)).string(),
                            String8(endTag).string());
                    return UNKNOWN_ERROR;
                }
                break;
            }
            StringPool::entry_style_span span = spanStack.top();
            String16 spanTag;
            ssize_t semi = span.name.findFirst(';');
            if (semi >= 0) {
                spanTag.setTo(span.name.string(), semi);
            } else {
                spanTag.setTo(span.name);
            }
            if (strcmp16(inXml->getElementName(&len), spanTag.string()) != 0) {
                SourcePos(String8(fileName), inXml->getLineNumber()).error(
                        "Found close tag %s where close tag %s is expected\n",
                        String8(inXml->getElementName(&len)).string(),
                        String8(spanTag).string());
                return UNKNOWN_ERROR;
            }
            bool empty = true;
            if (outString->size() > 0) {
                span.span.lastChar = outString->size()-1;
                if (span.span.lastChar >= span.span.firstChar) {
                    empty = false;
                    outSpans->add(span);
                }
            }
            spanStack.pop();

            /*
             * This warning seems to be just an irritation to most people,
             * since it is typically introduced by translators who then never
             * see the warning.
             */
            if (0 && empty) {
                fprintf(stderr, "%s:%d: warning: empty '%s' span found in text '%s'\n",
                        fileName, inXml->getLineNumber(),
                        String8(spanTag).string(), String8(*outString).string());

            }
        } else if (code == ResXMLTree::START_NAMESPACE) {
            // nothing
        }
    }

    if (code == ResXMLTree::BAD_DOCUMENT) {
            SourcePos(String8(fileName), inXml->getLineNumber()).error(
                    "Error parsing XML\n");
    }

    if (outSpans != NULL && outSpans->size() > 0) {
        if (curString.size() > 0) {
            if (!ResTable::collectString(outString, curString.string(),
                                         curString.size(), false, &errorMsg, true)) {
                SourcePos(String8(fileName), inXml->getLineNumber()).error(
                        "%s (in %s)\n",
                        errorMsg, String8(curString).string());
                return UNKNOWN_ERROR;
            }
        }
    } else {
        // There is no style information, so string processing will happen
        // later as part of the overall type conversion.  Return to the
        // client the raw unprocessed text.
        rawString.append(curString);
        outString->setTo(rawString);
    }

    return NO_ERROR;
}

struct namespace_entry {
    String8 prefix;
    String8 uri;
};

static String8 make_prefix(int depth)
{
    String8 prefix;
    int i;
    for (i=0; i<depth; i++) {
        prefix.append("  ");
    }
    return prefix;
}

static String8 build_namespace(const Vector<namespace_entry>& namespaces,
        const uint16_t* ns)
{
    String8 str;
    if (ns != NULL) {
        str = String8(ns);
        const size_t N = namespaces.size();
        for (size_t i=0; i<N; i++) {
            const namespace_entry& ne = namespaces.itemAt(i);
            if (ne.uri == str) {
                str = ne.prefix;
                break;
            }
        }
        str.append(":");
    }
    return str;
}

void printXMLBlock(ResXMLTree* block)
{
    block->restart();

    Vector<namespace_entry> namespaces;
    
    ResXMLTree::event_code_t code;
    int depth = 0;
    while ((code=block->next()) != ResXMLTree::END_DOCUMENT && code != ResXMLTree::BAD_DOCUMENT) {
        String8 prefix = make_prefix(depth);
        int i;
        if (code == ResXMLTree::START_TAG) {
            size_t len;
            const uint16_t* ns16 = block->getElementNamespace(&len);
            String8 elemNs = build_namespace(namespaces, ns16);
            const uint16_t* com16 = block->getComment(&len);
            if (com16) {
                printf("%s <!-- %s -->\n", prefix.string(), String8(com16).string());
            }
            printf("%sE: %s%s (line=%d)\n", prefix.string(), elemNs.string(),
                   String8(block->getElementName(&len)).string(),
                   block->getLineNumber());
            int N = block->getAttributeCount();
            depth++;
            prefix = make_prefix(depth);
            for (i=0; i<N; i++) {
                uint32_t res = block->getAttributeNameResID(i);
                ns16 = block->getAttributeNamespace(i, &len);
                String8 ns = build_namespace(namespaces, ns16);
                String8 name(block->getAttributeName(i, &len));
                printf("%sA: ", prefix.string());
                if (res) {
                    printf("%s%s(0x%08x)", ns.string(), name.string(), res);
                } else {
                    printf("%s%s", ns.string(), name.string());
                }
                Res_value value;
                block->getAttributeValue(i, &value);
                if (value.dataType == Res_value::TYPE_NULL) {
                    printf("=(null)");
                } else if (value.dataType == Res_value::TYPE_REFERENCE) {
                    printf("=@0x%x", (int)value.data);
                } else if (value.dataType == Res_value::TYPE_ATTRIBUTE) {
                    printf("=?0x%x", (int)value.data);
                } else if (value.dataType == Res_value::TYPE_STRING) {
                    printf("=\"%s\"",
                            ResTable::normalizeForOutput(String8(block->getAttributeStringValue(i,
                                        &len)).string()).string());
                } else {
                    printf("=(type 0x%x)0x%x", (int)value.dataType, (int)value.data);
                }
                const char16_t* val = block->getAttributeStringValue(i, &len);
                if (val != NULL) {
                    printf(" (Raw: \"%s\")", ResTable::normalizeForOutput(String8(val).string()).
                            string());
                }
                printf("\n");
            }
        } else if (code == ResXMLTree::END_TAG) {
            depth--;
        } else if (code == ResXMLTree::START_NAMESPACE) {
            namespace_entry ns;
            size_t len;
            const uint16_t* prefix16 = block->getNamespacePrefix(&len);
            if (prefix16) {
                ns.prefix = String8(prefix16);
            } else {
                ns.prefix = "<DEF>";
            }
            ns.uri = String8(block->getNamespaceUri(&len));
            namespaces.push(ns);
            printf("%sN: %s=%s\n", prefix.string(), ns.prefix.string(),
                    ns.uri.string());
            depth++;
        } else if (code == ResXMLTree::END_NAMESPACE) {
            depth--;
            const namespace_entry& ns = namespaces.top();
            size_t len;
            const uint16_t* prefix16 = block->getNamespacePrefix(&len);
            String8 pr;
            if (prefix16) {
                pr = String8(prefix16);
            } else {
                pr = "<DEF>";
            }
            if (ns.prefix != pr) {
                prefix = make_prefix(depth);
                printf("%s*** BAD END NS PREFIX: found=%s, expected=%s\n",
                        prefix.string(), pr.string(), ns.prefix.string());
            }
            String8 uri = String8(block->getNamespaceUri(&len));
            if (ns.uri != uri) {
                prefix = make_prefix(depth);
                printf("%s *** BAD END NS URI: found=%s, expected=%s\n",
                        prefix.string(), uri.string(), ns.uri.string());
            }
            namespaces.pop();
        } else if (code == ResXMLTree::TEXT) {
            size_t len;
            printf("%sC: \"%s\"\n", prefix.string(),
                    ResTable::normalizeForOutput(String8(block->getText(&len)).string()).string());
        }
    }

    block->restart();
}

status_t parseXMLResource(const sp<AaptFile>& file, ResXMLTree* outTree,
                          bool stripAll, bool keepComments,
                          const char** cDataTags)
{
    sp<XMLNode> root = XMLNode::parse(file);
    if (root == NULL) {
        return UNKNOWN_ERROR;
    }
    root->removeWhitespace(stripAll, cDataTags);

    NOISY(printf("Input XML from %s:\n", (const char*)file->getPrintableSource()));
    NOISY(root->print());
    sp<AaptFile> rsc = new AaptFile(String8(), AaptGroupEntry(), String8());
    status_t err = root->flatten(rsc, !keepComments, false);
    if (err != NO_ERROR) {
        return err;
    }
    err = outTree->setTo(rsc->getData(), rsc->getSize(), true);
    if (err != NO_ERROR) {
        return err;
    }

    NOISY(printf("Output XML:\n"));
    NOISY(printXMLBlock(outTree));

    return NO_ERROR;
}

sp<XMLNode> XMLNode::parse(const sp<AaptFile>& file)
{
    char buf[16384];
    int fd = open(file->getSourceFile().string(), O_RDONLY | O_BINARY);
    if (fd < 0) {
        SourcePos(file->getSourceFile(), -1).error("Unable to open file for read: %s",
                strerror(errno));
        return NULL;
    }

    XML_Parser parser = XML_ParserCreateNS(NULL, 1);
    ParseState state;
    state.filename = file->getPrintableSource();
    state.parser = parser;
    XML_SetUserData(parser, &state);
    XML_SetElementHandler(parser, startElement, endElement);
    XML_SetNamespaceDeclHandler(parser, startNamespace, endNamespace);
    XML_SetCharacterDataHandler(parser, characterData);
    XML_SetCommentHandler(parser, commentData);

    ssize_t len;
    bool done;
    do {
        len = read(fd, buf, sizeof(buf));
        done = len < (ssize_t)sizeof(buf);
        if (len < 0) {
            SourcePos(file->getSourceFile(), -1).error("Error reading file: %s\n", strerror(errno));
            close(fd);
            return NULL;
        }
        if (XML_Parse(parser, buf, len, done) == XML_STATUS_ERROR) {
            SourcePos(file->getSourceFile(), (int)XML_GetCurrentLineNumber(parser)).error(
                    "Error parsing XML: %s\n", XML_ErrorString(XML_GetErrorCode(parser)));
            close(fd);
            return NULL;
        }
    } while (!done);

    XML_ParserFree(parser);
    if (state.root == NULL) {
        SourcePos(file->getSourceFile(), -1).error("No XML data generated when parsing");
    }
    close(fd);
    return state.root;
}

XMLNode::XMLNode(const String8& filename, const String16& s1, const String16& s2, bool isNamespace)
    : mNextAttributeIndex(0x80000000)
    , mFilename(filename)
    , mStartLineNumber(0)
    , mEndLineNumber(0)
    , mUTF8(false)
{
    if (isNamespace) {
        mNamespacePrefix = s1;
        mNamespaceUri = s2;
    } else {
        mNamespaceUri = s1;
        mElementName = s2;
    }
}

XMLNode::XMLNode(const String8& filename)
    : mFilename(filename)
{
    memset(&mCharsValue, 0, sizeof(mCharsValue));
}

XMLNode::type XMLNode::getType() const
{
    if (mElementName.size() != 0) {
        return TYPE_ELEMENT;
    }
    if (mNamespaceUri.size() != 0) {
        return TYPE_NAMESPACE;
    }
    return TYPE_CDATA;
}

const String16& XMLNode::getNamespacePrefix() const
{
    return mNamespacePrefix;
}

const String16& XMLNode::getNamespaceUri() const
{
    return mNamespaceUri;
}

const String16& XMLNode::getElementNamespace() const
{
    return mNamespaceUri;
}

const String16& XMLNode::getElementName() const
{
    return mElementName;
}

const Vector<sp<XMLNode> >& XMLNode::getChildren() const
{
    return mChildren;
}

const String8& XMLNode::getFilename() const
{
    return mFilename;
}
    
const Vector<XMLNode::attribute_entry>&
    XMLNode::getAttributes() const
{
    return mAttributes;
}

const XMLNode::attribute_entry* XMLNode::getAttribute(const String16& ns,
        const String16& name) const
{
    for (size_t i=0; i<mAttributes.size(); i++) {
        const attribute_entry& ae(mAttributes.itemAt(i));
        if (ae.ns == ns && ae.name == name) {
            return &ae;
        }
    }
    
    return NULL;
}

XMLNode::attribute_entry* XMLNode::editAttribute(const String16& ns,
        const String16& name)
{
    for (size_t i=0; i<mAttributes.size(); i++) {
        attribute_entry * ae = &mAttributes.editItemAt(i);
        if (ae->ns == ns && ae->name == name) {
            return ae;
        }
    }

    return NULL;
}

const String16& XMLNode::getCData() const
{
    return mChars;
}

const String16& XMLNode::getComment() const
{
    return mComment;
}

int32_t XMLNode::getStartLineNumber() const
{
    return mStartLineNumber;
}

int32_t XMLNode::getEndLineNumber() const
{
    return mEndLineNumber;
}

sp<XMLNode> XMLNode::searchElement(const String16& tagNamespace, const String16& tagName)
{
    if (getType() == XMLNode::TYPE_ELEMENT
            && mNamespaceUri == tagNamespace
            && mElementName == tagName) {
        return this;
    }
    
    for (size_t i=0; i<mChildren.size(); i++) {
        sp<XMLNode> found = mChildren.itemAt(i)->searchElement(tagNamespace, tagName);
        if (found != NULL) {
            return found;
        }
    }
    
    return NULL;
}

sp<XMLNode> XMLNode::getChildElement(const String16& tagNamespace, const String16& tagName)
{
    for (size_t i=0; i<mChildren.size(); i++) {
        sp<XMLNode> child = mChildren.itemAt(i);
        if (child->getType() == XMLNode::TYPE_ELEMENT
                && child->mNamespaceUri == tagNamespace
                && child->mElementName == tagName) {
            return child;
        }
    }
    
    return NULL;
}

status_t XMLNode::addChild(const sp<XMLNode>& child)
{
    if (getType() == TYPE_CDATA) {
        SourcePos(mFilename, child->getStartLineNumber()).error("Child to CDATA node.");
        return UNKNOWN_ERROR;
    }
    //printf("Adding child %p to parent %p\n", child.get(), this);
    mChildren.add(child);
    return NO_ERROR;
}

status_t XMLNode::insertChildAt(const sp<XMLNode>& child, size_t index)
{
    if (getType() == TYPE_CDATA) {
        SourcePos(mFilename, child->getStartLineNumber()).error("Child to CDATA node.");
        return UNKNOWN_ERROR;
    }
    //printf("Adding child %p to parent %p\n", child.get(), this);
    mChildren.insertAt(child, index);
    return NO_ERROR;
}

status_t XMLNode::addAttribute(const String16& ns, const String16& name,
                               const String16& value)
{
    if (getType() == TYPE_CDATA) {
        SourcePos(mFilename, getStartLineNumber()).error("Child to CDATA node.");
        return UNKNOWN_ERROR;
    }

    if (ns != RESOURCES_TOOLS_NAMESPACE) {
        attribute_entry e;
        e.index = mNextAttributeIndex++;
        e.ns = ns;
        e.name = name;
        e.string = value;
        mAttributes.add(e);
        mAttributeOrder.add(e.index, mAttributes.size()-1);
    }
    return NO_ERROR;
}

void XMLNode::setAttributeResID(size_t attrIdx, uint32_t resId)
{
    attribute_entry& e = mAttributes.editItemAt(attrIdx);
    if (e.nameResId) {
        mAttributeOrder.removeItem(e.nameResId);
    } else {
        mAttributeOrder.removeItem(e.index);
    }
    NOISY(printf("Elem %s %s=\"%s\": set res id = 0x%08x\n",
            String8(getElementName()).string(),
            String8(mAttributes.itemAt(attrIdx).name).string(),
            String8(mAttributes.itemAt(attrIdx).string).string(),
            resId));
    mAttributes.editItemAt(attrIdx).nameResId = resId;
    mAttributeOrder.add(resId, attrIdx);
}

status_t XMLNode::appendChars(const String16& chars)
{
    if (getType() != TYPE_CDATA) {
        SourcePos(mFilename, getStartLineNumber()).error("Adding characters to element node.");
        return UNKNOWN_ERROR;
    }
    mChars.append(chars);
    return NO_ERROR;
}

status_t XMLNode::appendComment(const String16& comment)
{
    if (mComment.size() > 0) {
        mComment.append(String16("\n"));
    }
    mComment.append(comment);
    return NO_ERROR;
}

void XMLNode::setStartLineNumber(int32_t line)
{
    mStartLineNumber = line;
}

void XMLNode::setEndLineNumber(int32_t line)
{
    mEndLineNumber = line;
}

void XMLNode::removeWhitespace(bool stripAll, const char** cDataTags)
{
    //printf("Removing whitespace in %s\n", String8(mElementName).string());
    size_t N = mChildren.size();
    if (cDataTags) {
        String8 tag(mElementName);
        const char** p = cDataTags;
        while (*p) {
            if (tag == *p) {
                stripAll = false;
                break;
            }
        }
    }
    for (size_t i=0; i<N; i++) {
        sp<XMLNode> node = mChildren.itemAt(i);
        if (node->getType() == TYPE_CDATA) {
            // This is a CDATA node...
            const char16_t* p = node->mChars.string();
            while (*p != 0 && *p < 128 && isspace(*p)) {
                p++;
            }
            //printf("Space ends at %d in \"%s\"\n",
            //       (int)(p-node->mChars.string()),
            //       String8(node->mChars).string());
            if (*p == 0) {
                if (stripAll) {
                    // Remove this node!
                    mChildren.removeAt(i);
                    N--;
                    i--;
                } else {
                    node->mChars = String16(" ");
                }
            } else {
                // Compact leading/trailing whitespace.
                const char16_t* e = node->mChars.string()+node->mChars.size()-1;
                while (e > p && *e < 128 && isspace(*e)) {
                    e--;
                }
                if (p > node->mChars.string()) {
                    p--;
                }
                if (e < (node->mChars.string()+node->mChars.size()-1)) {
                    e++;
                }
                if (p > node->mChars.string() ||
                    e < (node->mChars.string()+node->mChars.size()-1)) {
                    String16 tmp(p, e-p+1);
                    node->mChars = tmp;
                }
            }
        } else {
            node->removeWhitespace(stripAll, cDataTags);
        }
    }
}

status_t XMLNode::parseValues(const sp<AaptAssets>& assets,
                              ResourceTable* table)
{
    bool hasErrors = false;
    
    if (getType() == TYPE_ELEMENT) {
        const size_t N = mAttributes.size();
        String16 defPackage(assets->getPackage());
        for (size_t i=0; i<N; i++) {
            attribute_entry& e = mAttributes.editItemAt(i);
            AccessorCookie ac(SourcePos(mFilename, getStartLineNumber()), String8(e.name),
                    String8(e.string));
            table->setCurrentXmlPos(SourcePos(mFilename, getStartLineNumber()));
            if (!assets->getIncludedResources()
                    .stringToValue(&e.value, &e.string,
                                  e.string.string(), e.string.size(), true, true,
                                  e.nameResId, NULL, &defPackage, table, &ac)) {
                hasErrors = true;
            }
            NOISY(printf("Attr %s: type=0x%x, str=%s\n",
                   String8(e.name).string(), e.value.dataType,
                   String8(e.string).string()));
        }
    }
    const size_t N = mChildren.size();
    for (size_t i=0; i<N; i++) {
        status_t err = mChildren.itemAt(i)->parseValues(assets, table);
        if (err != NO_ERROR) {
            hasErrors = true;
        }
    }
    return hasErrors ? UNKNOWN_ERROR : NO_ERROR;
}

status_t XMLNode::assignResourceIds(const sp<AaptAssets>& assets,
                                    const ResourceTable* table)
{
    bool hasErrors = false;
    
    if (getType() == TYPE_ELEMENT) {
        String16 attr("attr");
        const char* errorMsg;
        const size_t N = mAttributes.size();
        for (size_t i=0; i<N; i++) {
            const attribute_entry& e = mAttributes.itemAt(i);
            if (e.ns.size() <= 0) continue;
            bool nsIsPublic;
            String16 pkg(getNamespaceResourcePackage(String16(assets->getPackage()), e.ns, &nsIsPublic));
            NOISY(printf("Elem %s %s=\"%s\": namespace(%s) %s ===> %s\n",
                    String8(getElementName()).string(),
                    String8(e.name).string(),
                    String8(e.string).string(),
                    String8(e.ns).string(),
                    (nsIsPublic) ? "public" : "private",
                    String8(pkg).string()));
            if (pkg.size() <= 0) continue;
            uint32_t res = table != NULL
                ? table->getResId(e.name, &attr, &pkg, &errorMsg, nsIsPublic)
                : assets->getIncludedResources().
                    identifierForName(e.name.string(), e.name.size(),
                                      attr.string(), attr.size(),
                                      pkg.string(), pkg.size());
            if (res != 0) {
                NOISY(printf("XML attribute name %s: resid=0x%08x\n",
                             String8(e.name).string(), res));
                setAttributeResID(i, res);
            } else {
                SourcePos(mFilename, getStartLineNumber()).error(
                        "No resource identifier found for attribute '%s' in package '%s'\n",
                        String8(e.name).string(), String8(pkg).string());
                hasErrors = true;
            }
        }
    }
    const size_t N = mChildren.size();
    for (size_t i=0; i<N; i++) {
        status_t err = mChildren.itemAt(i)->assignResourceIds(assets, table);
        if (err < NO_ERROR) {
            hasErrors = true;
        }
    }

    return hasErrors ? UNKNOWN_ERROR : NO_ERROR;
}

status_t XMLNode::flatten(const sp<AaptFile>& dest,
        bool stripComments, bool stripRawValues) const
{
    StringPool strings(mUTF8);
    Vector<uint32_t> resids;
    
    // First collect just the strings for attribute names that have a
    // resource ID assigned to them.  This ensures that the resource ID
    // array is compact, and makes it easier to deal with attribute names
    // in different namespaces (and thus with different resource IDs).
    collect_resid_strings(&strings, &resids);

    // Next collect all remainibng strings.
    collect_strings(&strings, &resids, stripComments, stripRawValues);

#if 0  // No longer compiles
    NOISY(printf("Found strings:\n");
        const size_t N = strings.size();
        for (size_t i=0; i<N; i++) {
            printf("%s\n", String8(strings.entryAt(i).string).string());
        }
    );
#endif    

    sp<AaptFile> stringPool = strings.createStringBlock();
    NOISY(aout << "String pool:"
          << HexDump(stringPool->getData(), stringPool->getSize()) << endl);

    ResXMLTree_header header;
    memset(&header, 0, sizeof(header));
    header.header.type = htods(RES_XML_TYPE);
    header.header.headerSize = htods(sizeof(header));

    const size_t basePos = dest->getSize();
    dest->writeData(&header, sizeof(header));
    dest->writeData(stringPool->getData(), stringPool->getSize());

    // If we have resource IDs, write them.
    if (resids.size() > 0) {
        const size_t resIdsPos = dest->getSize();
        const size_t resIdsSize =
            sizeof(ResChunk_header)+(sizeof(uint32_t)*resids.size());
        ResChunk_header* idsHeader = (ResChunk_header*)
            (((const uint8_t*)dest->editData(resIdsPos+resIdsSize))+resIdsPos);
        idsHeader->type = htods(RES_XML_RESOURCE_MAP_TYPE);
        idsHeader->headerSize = htods(sizeof(*idsHeader));
        idsHeader->size = htodl(resIdsSize);
        uint32_t* ids = (uint32_t*)(idsHeader+1);
        for (size_t i=0; i<resids.size(); i++) {
            *ids++ = htodl(resids[i]);
        }
    }

    flatten_node(strings, dest, stripComments, stripRawValues);

    void* data = dest->editData();
    ResXMLTree_header* hd = (ResXMLTree_header*)(((uint8_t*)data)+basePos);
    size_t size = dest->getSize()-basePos;
    hd->header.size = htodl(dest->getSize()-basePos);

    NOISY(aout << "XML resource:"
          << HexDump(dest->getData(), dest->getSize()) << endl);

    #if PRINT_STRING_METRICS
    fprintf(stderr, "**** total xml size: %d / %d%% strings (in %s)\n",
        dest->getSize(), (stringPool->getSize()*100)/dest->getSize(),
        dest->getPath().string());
    #endif
        
    return NO_ERROR;
}

void XMLNode::print(int indent)
{
    String8 prefix;
    int i;
    for (i=0; i<indent; i++) {
        prefix.append("  ");
    }
    if (getType() == TYPE_ELEMENT) {
        String8 elemNs(getNamespaceUri());
        if (elemNs.size() > 0) {
            elemNs.append(":");
        }
        printf("%s E: %s%s", prefix.string(),
               elemNs.string(), String8(getElementName()).string());
        int N = mAttributes.size();
        for (i=0; i<N; i++) {
            ssize_t idx = mAttributeOrder.valueAt(i);
            if (i == 0) {
                printf(" / ");
            } else {
                printf(", ");
            }
            const attribute_entry& attr = mAttributes.itemAt(idx);
            String8 attrNs(attr.ns);
            if (attrNs.size() > 0) {
                attrNs.append(":");
            }
            if (attr.nameResId) {
                printf("%s%s(0x%08x)", attrNs.string(),
                       String8(attr.name).string(), attr.nameResId);
            } else {
                printf("%s%s", attrNs.string(), String8(attr.name).string());
            }
            printf("=%s", String8(attr.string).string());
        }
        printf("\n");
    } else if (getType() == TYPE_NAMESPACE) {
        printf("%s N: %s=%s\n", prefix.string(),
               getNamespacePrefix().size() > 0
                    ? String8(getNamespacePrefix()).string() : "<DEF>",
               String8(getNamespaceUri()).string());
    } else {
        printf("%s C: \"%s\"\n", prefix.string(), String8(getCData()).string());
    }
    int N = mChildren.size();
    for (i=0; i<N; i++) {
        mChildren.itemAt(i)->print(indent+1);
    }
}

static void splitName(const char* name, String16* outNs, String16* outName)
{
    const char* p = name;
    while (*p != 0 && *p != 1) {
        p++;
    }
    if (*p == 0) {
        *outNs = String16();
        *outName = String16(name);
    } else {
        *outNs = String16(name, (p-name));
        *outName = String16(p+1);
    }
}

void XMLCALL
XMLNode::startNamespace(void *userData, const char *prefix, const char *uri)
{
    NOISY_PARSE(printf("Start Namespace: %s %s\n", prefix, uri));
    ParseState* st = (ParseState*)userData;
    sp<XMLNode> node = XMLNode::newNamespace(st->filename, 
            String16(prefix != NULL ? prefix : ""), String16(uri));
    node->setStartLineNumber(XML_GetCurrentLineNumber(st->parser));
    if (st->stack.size() > 0) {
        st->stack.itemAt(st->stack.size()-1)->addChild(node);
    } else {
        st->root = node;
    }
    st->stack.push(node);
}

void XMLCALL
XMLNode::startElement(void *userData, const char *name, const char **atts)
{
    NOISY_PARSE(printf("Start Element: %s\n", name));
    ParseState* st = (ParseState*)userData;
    String16 ns16, name16;
    splitName(name, &ns16, &name16);
    sp<XMLNode> node = XMLNode::newElement(st->filename, ns16, name16);
    node->setStartLineNumber(XML_GetCurrentLineNumber(st->parser));
    if (st->pendingComment.size() > 0) {
        node->appendComment(st->pendingComment);
        st->pendingComment = String16();
    }
    if (st->stack.size() > 0) {
        st->stack.itemAt(st->stack.size()-1)->addChild(node);
    } else {
        st->root = node;
    }
    st->stack.push(node);

    for (int i = 0; atts[i]; i += 2) {
        splitName(atts[i], &ns16, &name16);
        node->addAttribute(ns16, name16, String16(atts[i+1]));
    }
}

void XMLCALL
XMLNode::characterData(void *userData, const XML_Char *s, int len)
{
    NOISY_PARSE(printf("CDATA: \"%s\"\n", String8(s, len).string()));
    ParseState* st = (ParseState*)userData;
    sp<XMLNode> node = NULL;
    if (st->stack.size() == 0) {
        return;
    }
    sp<XMLNode> parent = st->stack.itemAt(st->stack.size()-1);
    if (parent != NULL && parent->getChildren().size() > 0) {
        node = parent->getChildren()[parent->getChildren().size()-1];
        if (node->getType() != TYPE_CDATA) {
            // Last node is not CDATA, need to make a new node.
            node = NULL;
        }
    }

    if (node == NULL) {
        node = XMLNode::newCData(st->filename);
        node->setStartLineNumber(XML_GetCurrentLineNumber(st->parser));
        parent->addChild(node);
    }

    node->appendChars(String16(s, len));
}

void XMLCALL
XMLNode::endElement(void *userData, const char *name)
{
    NOISY_PARSE(printf("End Element: %s\n", name));
    ParseState* st = (ParseState*)userData;
    sp<XMLNode> node = st->stack.itemAt(st->stack.size()-1);
    node->setEndLineNumber(XML_GetCurrentLineNumber(st->parser));
    if (st->pendingComment.size() > 0) {
        node->appendComment(st->pendingComment);
        st->pendingComment = String16();
    }
    String16 ns16, name16;
    splitName(name, &ns16, &name16);
    LOG_ALWAYS_FATAL_IF(node->getElementNamespace() != ns16
                        || node->getElementName() != name16,
                        "Bad end element %s", name);
    st->stack.pop();
}

void XMLCALL
XMLNode::endNamespace(void *userData, const char *prefix)
{
    const char* nonNullPrefix = prefix != NULL ? prefix : "";
    NOISY_PARSE(printf("End Namespace: %s\n", prefix));
    ParseState* st = (ParseState*)userData;
    sp<XMLNode> node = st->stack.itemAt(st->stack.size()-1);
    node->setEndLineNumber(XML_GetCurrentLineNumber(st->parser));
    LOG_ALWAYS_FATAL_IF(node->getNamespacePrefix() != String16(nonNullPrefix),
                        "Bad end namespace %s", prefix);
    st->stack.pop();
}

void XMLCALL
XMLNode::commentData(void *userData, const char *comment)
{
    NOISY_PARSE(printf("Comment: %s\n", comment));
    ParseState* st = (ParseState*)userData;
    if (st->pendingComment.size() > 0) {
        st->pendingComment.append(String16("\n"));
    }
    st->pendingComment.append(String16(comment));
}

status_t XMLNode::collect_strings(StringPool* dest, Vector<uint32_t>* outResIds,
        bool stripComments, bool stripRawValues) const
{
    collect_attr_strings(dest, outResIds, true);
    
    int i;
    if (RESOURCES_TOOLS_NAMESPACE != mNamespaceUri) {
        if (mNamespacePrefix.size() > 0) {
            dest->add(mNamespacePrefix, true);
        }
        if (mNamespaceUri.size() > 0) {
            dest->add(mNamespaceUri, true);
        }
    }
    if (mElementName.size() > 0) {
        dest->add(mElementName, true);
    }

    if (!stripComments && mComment.size() > 0) {
        dest->add(mComment, true);
    }

    const int NA = mAttributes.size();

    for (i=0; i<NA; i++) {
        const attribute_entry& ae = mAttributes.itemAt(i);
        if (ae.ns.size() > 0) {
            dest->add(ae.ns, true);
        }
        if (!stripRawValues || ae.needStringValue()) {
            dest->add(ae.string, true);
        }
        /*
        if (ae.value.dataType == Res_value::TYPE_NULL
                || ae.value.dataType == Res_value::TYPE_STRING) {
            dest->add(ae.string, true);
        }
        */
    }

    if (mElementName.size() == 0) {
        // If not an element, include the CDATA, even if it is empty.
        dest->add(mChars, true);
    }

    const int NC = mChildren.size();

    for (i=0; i<NC; i++) {
        mChildren.itemAt(i)->collect_strings(dest, outResIds,
                stripComments, stripRawValues);
    }

    return NO_ERROR;
}

status_t XMLNode::collect_attr_strings(StringPool* outPool,
        Vector<uint32_t>* outResIds, bool allAttrs) const {
    const int NA = mAttributes.size();

    for (int i=0; i<NA; i++) {
        const attribute_entry& attr = mAttributes.itemAt(i);
        uint32_t id = attr.nameResId;
        if (id || allAttrs) {
            // See if we have already assigned this resource ID to a pooled
            // string...
            const Vector<size_t>* indices = outPool->offsetsForString(attr.name);
            ssize_t idx = -1;
            if (indices != NULL) {
                const int NJ = indices->size();
                const size_t NR = outResIds->size();
                for (int j=0; j<NJ; j++) {
                    size_t strIdx = indices->itemAt(j);
                    if (strIdx >= NR) {
                        if (id == 0) {
                            // We don't need to assign a resource ID for this one.
                            idx = strIdx;
                            break;
                        }
                        // Just ignore strings that are out of range of
                        // the currently assigned resource IDs...  we add
                        // strings as we assign the first ID.
                    } else if (outResIds->itemAt(strIdx) == id) {
                        idx = strIdx;
                        break;
                    }
                }
            }
            if (idx < 0) {
                idx = outPool->add(attr.name);
                NOISY(printf("Adding attr %s (resid 0x%08x) to pool: idx=%d\n",
                        String8(attr.name).string(), id, idx));
                if (id != 0) {
                    while ((ssize_t)outResIds->size() <= idx) {
                        outResIds->add(0);
                    }
                    outResIds->replaceAt(id, idx);
                }
            }
            attr.namePoolIdx = idx;
            NOISY(printf("String %s offset=0x%08x\n",
                         String8(attr.name).string(), idx));
        }
    }

    return NO_ERROR;
}

status_t XMLNode::collect_resid_strings(StringPool* outPool,
        Vector<uint32_t>* outResIds) const
{
    collect_attr_strings(outPool, outResIds, false);

    const int NC = mChildren.size();

    for (int i=0; i<NC; i++) {
        mChildren.itemAt(i)->collect_resid_strings(outPool, outResIds);
    }

    return NO_ERROR;
}

status_t XMLNode::flatten_node(const StringPool& strings, const sp<AaptFile>& dest,
        bool stripComments, bool stripRawValues) const
{
    ResXMLTree_node node;
    ResXMLTree_cdataExt cdataExt;
    ResXMLTree_namespaceExt namespaceExt;
    ResXMLTree_attrExt attrExt;
    const void* extData = NULL;
    size_t extSize = 0;
    ResXMLTree_attribute attr;
    bool writeCurrentNode = true;

    const size_t NA = mAttributes.size();
    const size_t NC = mChildren.size();
    size_t i;

    LOG_ALWAYS_FATAL_IF(NA != mAttributeOrder.size(), "Attributes messed up!");

    const String16 id16("id");
    const String16 class16("class");
    const String16 style16("style");

    const type type = getType();

    memset(&node, 0, sizeof(node));
    memset(&attr, 0, sizeof(attr));
    node.header.headerSize = htods(sizeof(node));
    node.lineNumber = htodl(getStartLineNumber());
    if (!stripComments) {
        node.comment.index = htodl(
            mComment.size() > 0 ? strings.offsetForString(mComment) : -1);
        //if (mComment.size() > 0) {
        //  printf("Flattening comment: %s\n", String8(mComment).string());
        //}
    } else {
        node.comment.index = htodl((uint32_t)-1);
    }
    if (type == TYPE_ELEMENT) {
        node.header.type = htods(RES_XML_START_ELEMENT_TYPE);
        extData = &attrExt;
        extSize = sizeof(attrExt);
        memset(&attrExt, 0, sizeof(attrExt));
        if (mNamespaceUri.size() > 0) {
            attrExt.ns.index = htodl(strings.offsetForString(mNamespaceUri));
        } else {
            attrExt.ns.index = htodl((uint32_t)-1);
        }
        attrExt.name.index = htodl(strings.offsetForString(mElementName));
        attrExt.attributeStart = htods(sizeof(attrExt));
        attrExt.attributeSize = htods(sizeof(attr));
        attrExt.attributeCount = htods(NA);
        attrExt.idIndex = htods(0);
        attrExt.classIndex = htods(0);
        attrExt.styleIndex = htods(0);
        for (i=0; i<NA; i++) {
            ssize_t idx = mAttributeOrder.valueAt(i);
            const attribute_entry& ae = mAttributes.itemAt(idx);
            if (ae.ns.size() == 0) {
                if (ae.name == id16) {
                    attrExt.idIndex = htods(i+1);
                } else if (ae.name == class16) {
                    attrExt.classIndex = htods(i+1);
                } else if (ae.name == style16) {
                    attrExt.styleIndex = htods(i+1);
                }
            }
        }
    } else if (type == TYPE_NAMESPACE) {
        if (mNamespaceUri == RESOURCES_TOOLS_NAMESPACE) {
            writeCurrentNode = false;
        } else {
            node.header.type = htods(RES_XML_START_NAMESPACE_TYPE);
            extData = &namespaceExt;
            extSize = sizeof(namespaceExt);
            memset(&namespaceExt, 0, sizeof(namespaceExt));
            if (mNamespacePrefix.size() > 0) {
                namespaceExt.prefix.index = htodl(strings.offsetForString(mNamespacePrefix));
            } else {
                namespaceExt.prefix.index = htodl((uint32_t)-1);
            }
            namespaceExt.prefix.index = htodl(strings.offsetForString(mNamespacePrefix));
            namespaceExt.uri.index = htodl(strings.offsetForString(mNamespaceUri));
        }
        LOG_ALWAYS_FATAL_IF(NA != 0, "Namespace nodes can't have attributes!");
    } else if (type == TYPE_CDATA) {
        node.header.type = htods(RES_XML_CDATA_TYPE);
        extData = &cdataExt;
        extSize = sizeof(cdataExt);
        memset(&cdataExt, 0, sizeof(cdataExt));
        cdataExt.data.index = htodl(strings.offsetForString(mChars));
        cdataExt.typedData.size = htods(sizeof(cdataExt.typedData));
        cdataExt.typedData.res0 = 0;
        cdataExt.typedData.dataType = mCharsValue.dataType;
        cdataExt.typedData.data = htodl(mCharsValue.data);
        LOG_ALWAYS_FATAL_IF(NA != 0, "CDATA nodes can't have attributes!");
    }

    node.header.size = htodl(sizeof(node) + extSize + (sizeof(attr)*NA));

    if (writeCurrentNode) {
        dest->writeData(&node, sizeof(node));
        if (extSize > 0) {
            dest->writeData(extData, extSize);
        }
    }

    for (i=0; i<NA; i++) {
        ssize_t idx = mAttributeOrder.valueAt(i);
        const attribute_entry& ae = mAttributes.itemAt(idx);
        if (ae.ns.size() > 0) {
            attr.ns.index = htodl(strings.offsetForString(ae.ns));
        } else {
            attr.ns.index = htodl((uint32_t)-1);
        }
        attr.name.index = htodl(ae.namePoolIdx);

        if (!stripRawValues || ae.needStringValue()) {
            attr.rawValue.index = htodl(strings.offsetForString(ae.string));
        } else {
            attr.rawValue.index = htodl((uint32_t)-1);
        }
        attr.typedValue.size = htods(sizeof(attr.typedValue));
        if (ae.value.dataType == Res_value::TYPE_NULL
                || ae.value.dataType == Res_value::TYPE_STRING) {
            attr.typedValue.res0 = 0;
            attr.typedValue.dataType = Res_value::TYPE_STRING;
            attr.typedValue.data = htodl(strings.offsetForString(ae.string));
        } else {
            attr.typedValue.res0 = 0;
            attr.typedValue.dataType = ae.value.dataType;
            attr.typedValue.data = htodl(ae.value.data);
        }
        dest->writeData(&attr, sizeof(attr));
    }

    for (i=0; i<NC; i++) {
        status_t err = mChildren.itemAt(i)->flatten_node(strings, dest,
                stripComments, stripRawValues);
        if (err != NO_ERROR) {
            return err;
        }
    }

    if (type == TYPE_ELEMENT) {
        ResXMLTree_endElementExt endElementExt;
        memset(&endElementExt, 0, sizeof(endElementExt));
        node.header.type = htods(RES_XML_END_ELEMENT_TYPE);
        node.header.size = htodl(sizeof(node)+sizeof(endElementExt));
        node.lineNumber = htodl(getEndLineNumber());
        node.comment.index = htodl((uint32_t)-1);
        endElementExt.ns.index = attrExt.ns.index;
        endElementExt.name.index = attrExt.name.index;
        dest->writeData(&node, sizeof(node));
        dest->writeData(&endElementExt, sizeof(endElementExt));
    } else if (type == TYPE_NAMESPACE) {
        if (writeCurrentNode) {
            node.header.type = htods(RES_XML_END_NAMESPACE_TYPE);
            node.lineNumber = htodl(getEndLineNumber());
            node.comment.index = htodl((uint32_t)-1);
            node.header.size = htodl(sizeof(node)+extSize);
            dest->writeData(&node, sizeof(node));
            dest->writeData(extData, extSize);
        }
    }

    return NO_ERROR;
}
