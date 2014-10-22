/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.utils;

import java.util.List;

/**
 * Various assortment of list utilities.
 *
 * <p>Using a {@code null} list is supported and will almost always return the default value
 * (e.g. {@code false}, or {@code null}).</p>
 */
public class ListUtils {

    /** Return {@code} true if the {@code list} contains the {@code needle}. */
    public static <T> boolean listContains(List<T> list, T needle) {
        if (list == null) {
            return false;
        } else {
            return list.contains(needle);
        }
    }

    /**
     * Return {@code true} if the {@code list} is only a single element equal to
     * {@code single}.
     */
    public static <T> boolean listElementsEqualTo(List<T> list, T single) {
        if (list == null) {
            return false;
        }

        return (list.size() == 1 && list.contains(single));
    }

    /**
     * Return a human-readable representation of a list (non-recursively).
     */
    public static <T> String listToString(List<T> list) {
        if (list == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('[');

        int size = list.size();
        int i = 0;
        for (T elem : list) {
            sb.append(elem);

            if (i != size - 1) {
                sb.append(',');
            }
            i++;
        }
        sb.append(']');

        return sb.toString();
    }

    /**
     * Return the first item from {@code choices} that is contained in the {@code list}.
     *
     * <p>Choices with an index closer to 0 get higher priority. If none of the {@code choices}
     * are in the {@code list}, then {@code null} is returned.
     *
     * @param list a list of objects which may or may not contain one or more of the choices
     * @param choices an array of objects which should be used to select an item from
     *
     * @return the first item from {@code choices} contained in {@code list}, otherwise {@code null}
     */
    public static <T> T listSelectFirstFrom(List<T> list, T[] choices) {
        if (list == null) {
            return null;
        }

        for (T choice : choices) {
            if (list.contains(choice)) {
                return choice;
            }
        }

        return null;
    }

    private ListUtils() {
        throw new AssertionError();
    }
}
