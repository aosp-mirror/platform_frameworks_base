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

package com.android.unit_tests.vcard;

import android.content.ContentValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import junit.framework.TestCase;

/**
 * Utility class which verifies input VNode.
 *
 * This class first checks whether each propertyNode in the VNode is in the
 * "ordered expected property list".
 * If the node does not exist in the "ordered list", the class refers to
 * "unorderd expected property set" and checks the node is expected somewhere.
 */
public class PropertyNodesVerifier {
    public static class TypeSet extends HashSet<String> {
        public TypeSet(String ... array) {
            super(Arrays.asList(array));
        }
    }

    public static class GroupSet extends HashSet<String> {
        public GroupSet(String ... array) {
            super(Arrays.asList(array));
        }
    }

    private final HashMap<String, List<PropertyNode>> mOrderedNodeMap;
    // Intentionally use ArrayList instead of Set, assuming there may be more than one
    // exactly same objects.
    private final ArrayList<PropertyNode> mUnorderedNodeList;
    private final TestCase mTestCase;

    public PropertyNodesVerifier(TestCase testCase) {
        mOrderedNodeMap = new HashMap<String, List<PropertyNode>>();
        mUnorderedNodeList = new ArrayList<PropertyNode>();
        mTestCase = testCase;
    }

    // WithOrder

    public PropertyNodesVerifier addNodeWithOrder(String propName, String propValue) {
        return addNodeWithOrder(propName, propValue, null, null, null, null, null);
    }

    public PropertyNodesVerifier addNodeWithOrder(String propName, String propValue,
            List<String> propValueList) {
        return addNodeWithOrder(propName, propValue, propValueList, null, null, null, null);
    }

    public PropertyNodesVerifier addNodeWithOrder(String propName, String propValue,
            TypeSet paramMap_TYPE) {
        return addNodeWithOrder(propName, propValue, null, null, null, paramMap_TYPE, null);
    }

    public PropertyNodesVerifier addNodeWithOrder(String propName, String propValue,
            List<String> propValueList, TypeSet paramMap_TYPE) {
        return addNodeWithOrder(propName, propValue, propValueList, null, null,
                paramMap_TYPE, null);
    }

    public PropertyNodesVerifier addNodeWithOrder(String propName, String propValue,
            List<String> propValueList, byte[] propValue_bytes,
            ContentValues paramMap, TypeSet paramMap_TYPE, GroupSet propGroupSet) {
        PropertyNode propertyNode = new PropertyNode(propName,
                propValue, propValueList, propValue_bytes,
                paramMap, paramMap_TYPE, propGroupSet);
        List<PropertyNode> expectedNodeList = mOrderedNodeMap.get(propName);
        if (expectedNodeList == null) {
            expectedNodeList = new ArrayList<PropertyNode>();
            mOrderedNodeMap.put(propName, expectedNodeList);
        }
        expectedNodeList.add(propertyNode);
        return this;
    }

    // WithoutOrder

    public PropertyNodesVerifier addNodeWithoutOrder(String propName, String propValue) {
        return addNodeWithoutOrder(propName, propValue, null, null, null, null, null);
    }

    public PropertyNodesVerifier addNodeWithoutOrder(String propName, String propValue,
            List<String> propValueList) {
        return addNodeWithoutOrder(propName, propValue, propValueList, null, null, null, null);
    }

    public PropertyNodesVerifier addNodeWithoutOrder(String propName, String propValue,
            TypeSet paramMap_TYPE) {
        return addNodeWithoutOrder(propName, propValue, null, null, null, paramMap_TYPE, null);
    }

    public PropertyNodesVerifier addNodeWithoutOrder(String propName, String propValue,
            List<String> propValueList, TypeSet paramMap_TYPE) {
        return addNodeWithoutOrder(propName, propValue, propValueList, null, null,
                paramMap_TYPE, null);
    }

    public PropertyNodesVerifier addNodeWithoutOrder(String propName, String propValue,
            List<String> propValueList, byte[] propValue_bytes,
            ContentValues paramMap, TypeSet paramMap_TYPE, GroupSet propGroupSet) {
        mUnorderedNodeList.add(new PropertyNode(propName, propValue,
                propValueList, propValue_bytes, paramMap, paramMap_TYPE, propGroupSet));
        return this;
    }

    public void verify(VNode vnode) {
        for (PropertyNode actualNode : vnode.propList) {
            verifyNode(actualNode.propName, actualNode);
        }
        if (!mOrderedNodeMap.isEmpty() || !mUnorderedNodeList.isEmpty()) {
            List<String> expectedProps = new ArrayList<String>();
            for (List<PropertyNode> nodes : mOrderedNodeMap.values()) {
                for (PropertyNode node : nodes) {
                    if (!expectedProps.contains(node.propName)) {
                        expectedProps.add(node.propName);
                    }
                }
            }
            for (PropertyNode node : mUnorderedNodeList) {
                if (!expectedProps.contains(node.propName)) {
                    expectedProps.add(node.propName);
                }
            }
            mTestCase.fail("Expected property " + Arrays.toString(expectedProps.toArray())
                    + " was not found.");
        }
    }

    private void verifyNode(final String propName, final PropertyNode actualNode) {
        List<PropertyNode> expectedNodeList = mOrderedNodeMap.get(propName);
        final int size = (expectedNodeList != null ? expectedNodeList.size() : 0);
        if (size > 0) {
            for (int i = 0; i < size; i++) {
                PropertyNode expectedNode = expectedNodeList.get(i);
                List<PropertyNode> expectedButDifferentValueList =
                    new ArrayList<PropertyNode>();
                if (expectedNode.propName.equals(propName)) {
                    if (expectedNode.equals(actualNode)) {
                        expectedNodeList.remove(i);
                        if (expectedNodeList.size() == 0) {
                            mOrderedNodeMap.remove(propName);
                        }
                        return;
                    } else {
                        expectedButDifferentValueList.add(expectedNode);
                    }
                }

                // "actualNode" is not in ordered expected list.
                // Try looking over unordered expected list.
                if (tryFoundExpectedNodeFromUnorderedList(actualNode,
                        expectedButDifferentValueList)) {
                    return;
                }

                if (!expectedButDifferentValueList.isEmpty()) {
                    // Same propName exists but with different value(s).
                    failWithExpectedNodeList(propName, actualNode,
                            expectedButDifferentValueList);
                } else {
                    // There's no expected node with same propName.
                    mTestCase.fail("Unexpected property \"" + propName + "\" exists.");
                }
            }
        } else {
            List<PropertyNode> expectedButDifferentValueList =
                new ArrayList<PropertyNode>();
            if (tryFoundExpectedNodeFromUnorderedList(actualNode, expectedButDifferentValueList)) {
                return;
            } else {
                if (!expectedButDifferentValueList.isEmpty()) {
                    // Same propName exists but with different value(s).
                    failWithExpectedNodeList(propName, actualNode,
                            expectedButDifferentValueList);
                } else {
                    // There's no expected node with same propName.
                    mTestCase.fail("Unexpected property \"" + propName + "\" exists.");
                }
            }
        }
    }

    private boolean tryFoundExpectedNodeFromUnorderedList(PropertyNode actualNode,
            List<PropertyNode> expectedButDifferentValueList) {
        final String propName = actualNode.propName;
        int unorderedListSize = mUnorderedNodeList.size();
        for (int i = 0; i < unorderedListSize; i++) {
            PropertyNode unorderedExpectedNode = mUnorderedNodeList.get(i);
            if (unorderedExpectedNode.propName.equals(propName)) {
                if (unorderedExpectedNode.equals(actualNode)) {
                    mUnorderedNodeList.remove(i);
                    return true;
                }
                expectedButDifferentValueList.add(unorderedExpectedNode);
            }
        }
        return false;
    }

    private void failWithExpectedNodeList(String propName, PropertyNode actualNode,
            List<PropertyNode> expectedNodeList) {
        StringBuilder builder = new StringBuilder();
        for (PropertyNode expectedNode : expectedNodeList) {
            builder.append("expected: ");
            builder.append(expectedNode.toString());
            builder.append("\n");
        }
        mTestCase.fail("Property \"" + propName + "\" has wrong value.\n"
                + builder.toString()
                + "  actual: " + actualNode.toString());
    }
}
