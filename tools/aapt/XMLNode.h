//
// Copyright 2006 The Android Open Source Project
//
// Build resource files from raw assets.
//

#ifndef XML_NODE_H
#define XML_NODE_H

#include "StringPool.h"
#include "ResourceTable.h"

#include <expat.h>

class XMLNode;

extern const char* const RESOURCES_ROOT_NAMESPACE;
extern const char* const RESOURCES_ANDROID_NAMESPACE;

bool isWhitespace(const char16_t* str);

String16 getNamespaceResourcePackage(String16 namespaceUri, bool* outIsPublic = NULL);

status_t parseStyledString(Bundle* bundle,
                           const char* fileName,
                           ResXMLTree* inXml,
                           const String16& endTag,
                           String16* outString,
                           Vector<StringPool::entry_style_span>* outSpans,
                           bool isFormatted,
                           PseudolocalizationMethod isPseudolocalizable);

void printXMLBlock(ResXMLTree* block);

status_t parseXMLResource(const sp<AaptFile>& file, ResXMLTree* outTree,
                          bool stripAll=true, bool keepComments=false,
                          const char** cDataTags=NULL);

class XMLNode : public RefBase
{
public:
    static sp<XMLNode> parse(const sp<AaptFile>& file);

    static inline
    sp<XMLNode> newNamespace(const String8& filename, const String16& prefix, const String16& uri) {
        return new XMLNode(filename, prefix, uri, true);
    }
    
    static inline
    sp<XMLNode> newElement(const String8& filename, const String16& ns, const String16& name) {
        return new XMLNode(filename, ns, name, false);
    }
    
    static inline
    sp<XMLNode> newCData(const String8& filename) {
        return new XMLNode(filename);
    }

    enum type {
        TYPE_NAMESPACE,
        TYPE_ELEMENT,
        TYPE_CDATA
    };
    
    type getType() const;
    
    const String16& getNamespacePrefix() const;
    const String16& getNamespaceUri() const;
    
    const String16& getElementNamespace() const;
    const String16& getElementName() const;
    const Vector<sp<XMLNode> >& getChildren() const;
    Vector<sp<XMLNode> >& getChildren();

    const String8& getFilename() const;
    
    struct attribute_entry {
        attribute_entry() : index(~(uint32_t)0), nameResId(0)
        {
            value.dataType = Res_value::TYPE_NULL;
        }

        bool needStringValue() const {
            return nameResId == 0
                || value.dataType == Res_value::TYPE_NULL
                || value.dataType == Res_value::TYPE_STRING;
        }
        
        String16 ns;
        String16 name;
        String16 string;
        Res_value value;
        uint32_t index;
        uint32_t nameResId;
        mutable uint32_t namePoolIdx;
    };

    const Vector<attribute_entry>& getAttributes() const;

    const attribute_entry* getAttribute(const String16& ns, const String16& name) const;
    bool removeAttribute(const String16& ns, const String16& name);
    
    attribute_entry* editAttribute(const String16& ns, const String16& name);

    const String16& getCData() const;

    const String16& getComment() const;

    int32_t getStartLineNumber() const;
    int32_t getEndLineNumber() const;

    sp<XMLNode> searchElement(const String16& tagNamespace, const String16& tagName);
    
    sp<XMLNode> getChildElement(const String16& tagNamespace, const String16& tagName);
    
    status_t addChild(const sp<XMLNode>& child);

    status_t insertChildAt(const sp<XMLNode>& child, size_t index);

    status_t addAttribute(const String16& ns, const String16& name,
                          const String16& value);

    status_t removeAttribute(size_t index);

    void setAttributeResID(size_t attrIdx, uint32_t resId);

    status_t appendChars(const String16& chars);

    status_t appendComment(const String16& comment);

    void setStartLineNumber(int32_t line);
    void setEndLineNumber(int32_t line);

    void removeWhitespace(bool stripAll=true, const char** cDataTags=NULL);

    void setUTF8(bool val) { mUTF8 = val; }

    status_t parseValues(const sp<AaptAssets>& assets, ResourceTable* table);

    status_t assignResourceIds(const sp<AaptAssets>& assets,
                               const ResourceTable* table = NULL);

    status_t flatten(const sp<AaptFile>& dest, bool stripComments,
            bool stripRawValues) const;

    sp<XMLNode> clone() const;

    void print(int indent=0);

private:
    struct ParseState
    {
        String8 filename;
        XML_Parser parser;
        sp<XMLNode> root;
        Vector<sp<XMLNode> > stack;
        String16 pendingComment;
    };

    static void XMLCALL
    startNamespace(void *userData, const char *prefix, const char *uri);
    static void XMLCALL
    startElement(void *userData, const char *name, const char **atts);
    static void XMLCALL
    characterData(void *userData, const XML_Char *s, int len);
    static void XMLCALL
    endElement(void *userData, const char *name);
    static void XMLCALL
    endNamespace(void *userData, const char *prefix);
    
    static void XMLCALL
    commentData(void *userData, const char *comment);
    
    // For cloning
    XMLNode();

    // Creating an element node.
    XMLNode(const String8& filename, const String16& s1, const String16& s2, bool isNamespace);
    
    // Creating a CDATA node.
    explicit XMLNode(const String8& filename);
    
    status_t collect_strings(StringPool* dest, Vector<uint32_t>* outResIds,
            bool stripComments, bool stripRawValues) const;

    status_t collect_attr_strings(StringPool* outPool,
        Vector<uint32_t>* outResIds, bool allAttrs) const;
        
    status_t collect_resid_strings(StringPool* outPool,
            Vector<uint32_t>* outResIds) const;

    status_t flatten_node(const StringPool& strings, const sp<AaptFile>& dest,
            bool stripComments, bool stripRawValues) const;

    String16 mNamespacePrefix;
    String16 mNamespaceUri;
    String16 mElementName;
    Vector<sp<XMLNode> > mChildren;
    Vector<attribute_entry> mAttributes;
    KeyedVector<uint32_t, uint32_t> mAttributeOrder;
    uint32_t mNextAttributeIndex;
    String16 mChars;
    Res_value mCharsValue;
    String16 mComment;
    String8 mFilename;
    int32_t mStartLineNumber;
    int32_t mEndLineNumber;

    // Encode compiled XML with UTF-8 StringPools?
    bool mUTF8;
};

#endif
