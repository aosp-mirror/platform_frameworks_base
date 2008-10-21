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
#ifndef __DOM_NODE_TYPE__
#define __DOM_NODE_TYPE__
enum NodeType {
    /**
     * The node is an <code>Element</code>.
     */
    ELEMENT_NODE = 1,
    /**
     * The node is an <code>Attr</code>.
     */
    ATTRIBUTE_NODE = 2,
    /**
     * The node is a <code>Text</code> node.
     */
    TEXT_NODE                 = 3,
    /**
     * The node type is CDATASection.
     */
    CDATA_SECTION_NODE        = 4,
    /**
     * The node type is an EntityReference.
     */
    ENTITY_REFERENCE_NODE     = 5,
    /**
     * The node type is an <code>Entity</code>.
     */
    ENTITY_NODE               = 6,
    /**
     * The node type is a ProcessingInstruction.
     */
    PROCESSING_INSTRUCTION_NODE = 7,
    /**
     * The node is a Comment
     */
    COMMENT_NODE              = 8,
    /**
     * The node is a Document.
     */
    DOCUMENT_NODE             = 9,
    /**
     * The node is a DocumentType.
     */
    DOCUMENT_TYPE_NODE        = 10,
    /**
     * The node is a DocumentFragment.
     */
    DOCUMENT_FRAGMENT_NODE    = 11,
    /**
     * The node is a Notation.
     */
    NOTATION_NODE             = 12,
};
#endif
