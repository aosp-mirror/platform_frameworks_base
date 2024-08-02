/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.layout.utils;

import java.util.ArrayList;

/**
 * Internal utility debug class
 */
public class DebugLog {

    public static final boolean DEBUG_LAYOUT_ON = false;

    public static class Node {
        public Node parent;
        public String name;
        public String endString;
        public ArrayList<Node> list = new ArrayList<>();

        public Node(Node parent, String name) {
            this.parent = parent;
            this.name = name;
            this.endString = name + " DONE";
            if (parent != null) {
                parent.add(this);
            }
        }

        public void add(Node node) {
            list.add(node);
        }
    }

    public static class LogNode extends Node {
        public LogNode(Node parent, String name) {
            super(parent, name);
        }
    }

    public static Node node = new Node(null, "Root");
    public static Node currentNode = node;

    public static void clear() {
        node = new Node(null, "Root");
        currentNode = node;
    }

    public static void s(StringValueSupplier valueSupplier) {
        if (DEBUG_LAYOUT_ON) {
            currentNode = new Node(currentNode, valueSupplier.getString());
        }
    }

    public static void log(StringValueSupplier valueSupplier) {
        if (DEBUG_LAYOUT_ON) {
            new LogNode(currentNode, valueSupplier.getString());
        }
    }

    public static void e() {
        if (DEBUG_LAYOUT_ON) {
            if (currentNode.parent != null) {
                currentNode = currentNode.parent;
            } else {
                currentNode = node;
            }
        }
    }

    public static void e(StringValueSupplier valueSupplier) {
        if (DEBUG_LAYOUT_ON) {
            currentNode.endString = valueSupplier.getString();
            if (currentNode.parent != null) {
                currentNode = currentNode.parent;
            } else {
                currentNode = node;
            }
        }
    }

    public static void printNode(int indent, Node node, StringBuilder builder) {
        if (DEBUG_LAYOUT_ON) {
            StringBuilder indentationBuilder = new StringBuilder();
            for (int i = 0; i < indent; i++) {
                indentationBuilder.append("| ");
            }
            String indentation = indentationBuilder.toString();

            if (node.list.size() > 0) {
                builder.append(indentation).append(node.name).append("\n");
                for (Node c : node.list) {
                    printNode(indent + 1, c, builder);
                }
                builder.append(indentation).append(node.endString).append("\n");
            } else {
                if (node instanceof LogNode) {
                    builder.append(indentation).append("     ").append(node.name).append("\n");
                } else {
                    builder.append(indentation).append("-- ").append(node.name)
                            .append(" : ").append(node.endString).append("\n");
                }
            }
        }
    }

    public static void display() {
        if (DEBUG_LAYOUT_ON) {
            StringBuilder builder = new StringBuilder();
            printNode(0, node, builder);
            System.out.println("\n" + builder.toString());
        }
    }
}

