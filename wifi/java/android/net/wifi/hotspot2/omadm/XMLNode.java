/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2.omadm;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A class represent a node in an XML tree. Each node is an XML element.
 * Used by {@link XMLParser} for parsing/converting each XML element to XMLNode.
 *
 * @hide
 */
public class XMLNode {
    private final String mTag;
    private final List<XMLNode> mChildren;
    private final XMLNode mParent;
    private StringBuilder mTextBuilder;
    private String mText;

    public XMLNode(XMLNode parent, String tag) {
        mTag = tag;
        mParent = parent;
        mChildren = new ArrayList<>();
        mTextBuilder = new StringBuilder();
        mText = null;
    }

    /**
     * Adding a text to this node. Invoked by {@link XMLParser#characters}.
     *
     * @param text String to be added
     */
    public void addText(String text) {
        mTextBuilder.append(text);
    }

    /**
     * Adding a child node to this node. Invoked by {@link XMLParser#startElement}.
     *
     * @param child XMLNode to be added
     */
    public void addChild(XMLNode child) {
        mChildren.add(child);
    }

    /**
     * Invoked when the end of the XML element is detected. Used for further processing
     * of the text enclosed within this XML element. Invoked by {@link XMLParser#endElement}.
     */
    public void close() {
        // Remove the leading and the trailing whitespaces.
        mText = mTextBuilder.toString().trim();
        mTextBuilder = null;
    }

    public String getTag() {
        return mTag;
    }

    public XMLNode getParent() {
        return mParent;
    }

    public String getText() {
        return mText;
    }

    public List<XMLNode> getChildren() {
        return mChildren;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof XMLNode)) {
            return false;
        }
        XMLNode that = (XMLNode) thatObject;

        return TextUtils.equals(mTag, that.mTag) &&
                TextUtils.equals(mText, that.mText) &&
                mChildren.equals(that.mChildren);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTag, mText, mChildren);
    }
}
