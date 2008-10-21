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

package android.sax;

/**
 * Contains element children. Using this class instead of HashMap results in
 * measurably better performance.
 */
class Children {

    Child[] children = new Child[16];

    /**
     * Looks up a child by name and creates a new one if necessary.
     */
    Element getOrCreate(Element parent, String uri, String localName) {
        int hash = uri.hashCode() * 31 + localName.hashCode();
        int index = hash & 15;

        Child current = children[index];
        if (current == null) {
            // We have no children in this bucket yet.
            current = new Child(parent, uri, localName, parent.depth + 1, hash);
            children[index] = current;
            return current;
        } else {
            // Search this bucket.
            Child previous;
            do {
                if (current.hash == hash
                        && current.uri.compareTo(uri) == 0
                        && current.localName.compareTo(localName) == 0) {
                    // We already have a child with that name.
                    return current;
                }

                previous = current;
                current = current.next;
            } while (current != null);

            // Add a new child to the bucket.
            current = new Child(parent, uri, localName, parent.depth + 1, hash);
            previous.next = current;
            return current;         
        }
    }

    /**
     * Looks up a child by name.
     */
    Element get(String uri, String localName) {
        int hash = uri.hashCode() * 31 + localName.hashCode();
        int index = hash & 15;

        Child current = children[index];
        if (current == null) {
            return null;
        } else {
            do {
                if (current.hash == hash
                        && current.uri.compareTo(uri) == 0
                        && current.localName.compareTo(localName) == 0) {
                    return current;
                }
                current = current.next;
            } while (current != null);

            return null;
        }
    }

    static class Child extends Element {

        final int hash;
        Child next;

        Child(Element parent, String uri, String localName, int depth,
                int hash) {
            super(parent, uri, localName, depth);
            this.hash = hash;
        }
    }
}
