/*
 * Copyright (C) 2007 The Android Open Source Project
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
#include <util/xml/DomExpatAgent.h>
#include <util/xml/XMLElementImpl.h>
#include <ustring.h>
#include <uios.h>
using namespace ustl;

/** see DomExpatAgent.h */
DomExpatAgent::DomExpatAgent(XMLDocumentImpl* xmlDocPtr)
{
    mXMLDocumentPtr = xmlDocPtr;
    mTopElementPtr = NULL;
}

/** see DomExpatAgent.h */
DomExpatAgent::~DomExpatAgent()
{

}

/** see DomExpatAgent.h */
bool DomExpatAgent::generateDocumentFromXML(istringstream *xmlStream)
{
    char ch;
    string content;

    if (NULL == mXMLDocumentPtr || NULL == xmlStream)
    {
        return false;
    }

    while ((ch = xmlStream->get()) != '\0')
    {
        content += ch;
    }

    if (ExpatWrapper::decode(content.c_str(), content.length(), 1) == 0)

    {
        return false;
    }
    return true;
}

/** see DomExpatAgent.h */
void DomExpatAgent::pushTag(const DOMString *name, const XML_Char **atts)
{
    ElementImpl *elementNode = mXMLDocumentPtr->createElement(name);

    if (NULL == elementNode)
    {
        return;
    }

    if (NULL != atts)
    {
        while (NULL != *atts)
        {
            //set attributes into element node.
            DOMString key(atts[0]), value(atts[1]);
            elementNode->setAttribute(&key, &value);
            atts += 2;
        }
    }

   if (!mStack.empty())
   {
       mTopElementPtr->appendChild(elementNode);
   }
   else
   {
       mXMLDocumentPtr->setFirstChild(elementNode);
   }

   mTopElementPtr = (XMLElementImpl *)elementNode;
   mStack.push_back(elementNode);
}

/** see DomExpatAgent.h */
void DomExpatAgent::popTag(const DOMString *name)
{
    if (NULL == name)
    {
        return;
    }

    if (mTopElementPtr != NULL)
    {
        if (!name->compare(mTopElementPtr->getTagName()->c_str()))
        {
            mStack.pop_back();
            if (!mStack.empty())
            {
                mTopElementPtr =(XMLElementImpl *) mStack.back();
            }
            else
            {
                mTopElementPtr = NULL;
            }
        }
    }
}

/** see DomExpatAgent.h */
void DomExpatAgent::appendText(const DOMString *text)
{
    if ((mTopElementPtr != NULL) && (text != NULL))
    {
        TextImpl *textNode = mXMLDocumentPtr->createTextNode(text);

        if (NULL == textNode)
        {
            return;
        }

       mTopElementPtr->appendChild(textNode);
    }
}

/** see DomExpatAgent.h */
void DomExpatAgent::startElement(const XML_Char *name, const XML_Char **atts)
{
    if (name)
    {
        DOMString tagName(name);

        pushTag(&tagName, atts);
    }
}

/** see DomExpatAgent.h */
void DomExpatAgent::dataHandler(const XML_Char *s, int len)
{
    if (s != NULL && len >= 1 && *s != '\n')
    {
        DOMString text;
        text.assign((char*)s, len);
        appendText(&text);
    }
}

/** see DomExpatAgent.h */
void DomExpatAgent::endElement(const XML_Char *name)
{
   if (name)
   {
       DOMString tagName(name);
       popTag(&tagName);
   }
}

/** see DomExpatAgent.h */
ostringstream* DomExpatAgent::generateXMLFromDocument()
{
    if (NULL == mXMLDocumentPtr)
    {
        return NULL;
    }

    ElementImpl *root = mXMLDocumentPtr->getDocumentElement();

    traverse(root);

    return &mXMLostream;
}

/** see DomExpatAgent.h */
void DomExpatAgent::traverse(ElementImpl *root)
{
    if (NULL == root)
    {
        return;
    }

    mXMLostream << "<" << *(root->getNodeName());

    if (root->hasAttributes())
    {
        mXMLostream << endl;
        const DOMStringMap* attrMapPtr = (static_cast<XMLElementImpl*>(root))->getAttributeMap();
        DOMStringMap::const_reverse_iterator pos;

        for (pos=attrMapPtr->rbegin(); pos != attrMapPtr->rend(); pos++)
        {
            mXMLostream << pos->first << "=" << "\"" << pos->second << "\"";

            if (pos + 1 != attrMapPtr->rend())
            {
                mXMLostream << endl;
            }
        }
    }

    mXMLostream << ">" << endl;

    NodeImpl *child = root->getFirstChild();

    while (child != NULL)
    {
        NodeType what = child->getNodeType();

        if (what == ELEMENT_NODE)
        {
            traverse(static_cast<ElementImpl*>(child));
        } else if (what == TEXT_NODE)
        {
            mXMLostream << *(static_cast<TextImpl*>(child)->getData()) << endl;
        }

        child = child->getNextSibling();
    }

    mXMLostream << "</" << *(root->getNodeName()) << ">" << endl;
}
