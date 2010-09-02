/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard.test_utils;

import android.content.ContentValues;
import android.pim.vcard.VCardEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * The class representing one property (e.g. "N;ENCODING=UTF-8:family:given:middle:prefix:suffix").
 * </p>
 * <p>
 * Previously used in main vCard handling code but now exists only for testing.
 * </p>
 * <p>
 * Especially useful for testing parser code (VCardParser), since all properties can be
 * checked via this class unlike {@link VCardEntry}, which only emits the result of
 * interpretation of the content of each vCard. We cannot know whether vCard parser or
 * {@link VCardEntry} is wrong without this class.
 * </p>
 */
public class PropertyNode {
    public String propName;
    public String propValue;
    public List<String> propValue_vector;

    /** Store value as byte[],after decode.
     * Used when propValue is encoded by something like BASE64, QUOTED-PRINTABLE, etc.
     */
    public byte[] propValue_bytes;

    /**
     * param store: key=paramType, value=paramValue
     * Note that currently PropertyNode class does not support multiple param-values
     * defined in vCard 3.0 (See also RFC 2426). multiple-values are stored as
     * one String value like "A,B", not ["A", "B"]...
     * TODO: fix this. 
     */
    public ContentValues paramMap;

    /** Only for TYPE=??? param store. */
    public Set<String> paramMap_TYPE;

    /** Store group values. Used only in VCard. */
    public Set<String> propGroupSet;
    
    public PropertyNode() {
        propName = "";
        propValue = "";
        propValue_vector = new ArrayList<String>();
        paramMap = new ContentValues();
        paramMap_TYPE = new HashSet<String>();
        propGroupSet = new HashSet<String>();
    }
    
    public PropertyNode(
            String propName, String propValue, List<String> propValue_vector,
            byte[] propValue_bytes, ContentValues paramMap, Set<String> paramMap_TYPE,
            Set<String> propGroupSet) {
        if (propName != null) {
            this.propName = propName;
        } else {
            this.propName = "";
        }
        if (propValue != null) {
            this.propValue = propValue;
        } else {
            this.propValue = "";
        }
        if (propValue_vector != null) {
            this.propValue_vector = propValue_vector;
        } else {
            this.propValue_vector = new ArrayList<String>();
        }
        this.propValue_bytes = propValue_bytes;
        if (paramMap != null) {
            this.paramMap = paramMap;
        } else {
            this.paramMap = new ContentValues();
        }
        if (paramMap_TYPE != null) {
            this.paramMap_TYPE = paramMap_TYPE;
        } else {
            this.paramMap_TYPE = new HashSet<String>();
        }
        if (propGroupSet != null) {
            this.propGroupSet = propGroupSet;
        } else {
            this.propGroupSet = new HashSet<String>();
        }
    }
    
    @Override
    public int hashCode() {
        // vCard may contain more than one same line in one entry, while HashSet or any other
        // library which utilize hashCode() does not honor that, so intentionally throw an
        // Exception.
        throw new UnsupportedOperationException(
                "PropertyNode does not provide hashCode() implementation intentionally.");
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PropertyNode)) {
            return false;
        }
        
        PropertyNode node = (PropertyNode)obj;
        
        if (propName == null || !propName.equals(node.propName)) {
            return false;
        } else if (!paramMap_TYPE.equals(node.paramMap_TYPE)) {
            return false;
        } else if (!paramMap_TYPE.equals(node.paramMap_TYPE)) {
            return false;
        } else if (!propGroupSet.equals(node.propGroupSet)) {
            return false;
        }

        if (propValue_bytes != null && Arrays.equals(propValue_bytes, node.propValue_bytes)) {
            return true;
        } else {
            if (!propValue.equals(node.propValue)) {
                return false;
            }

            // The value in propValue_vector is not decoded even if it should be
            // decoded by BASE64 or QUOTED-PRINTABLE. When the size of propValue_vector
            // is 1, the encoded value is stored in propValue, so we do not have to
            // check it.
            return (propValue_vector.equals(node.propValue_vector) ||
                    propValue_vector.size() == 1 ||
                    node.propValue_vector.size() == 1);
        }
    }
    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("propName: ");
        builder.append(propName);
        builder.append(", paramMap: ");
        builder.append(paramMap.toString());
        builder.append(", paramMap_TYPE: [");
        boolean first = true;
        for (String elem : paramMap_TYPE) {
            if (first) {
                first = false;
            } else {
                builder.append(", ");
            }
            builder.append('"');
            builder.append(elem);
            builder.append('"');
        }
        builder.append("]");
        if (!propGroupSet.isEmpty()) {
            builder.append(", propGroupSet: [");
            first = true;
            for (String elem : propGroupSet) {
                if (first) {
                    first = false;
                } else {
                    builder.append(", ");
                }
                builder.append('"');
                builder.append(elem);
                builder.append('"');
            }
            builder.append("]");
        }
        if (propValue_vector != null && propValue_vector.size() > 1) {
            builder.append(", propValue_vector size: ");
            builder.append(propValue_vector.size());
        }
        if (propValue_bytes != null) {
            builder.append(", propValue_bytes size: ");
            builder.append(propValue_bytes.length);
        }
        builder.append(", propValue: \"");
        builder.append(propValue);
        builder.append("\"");
        return builder.toString();
    }
}
