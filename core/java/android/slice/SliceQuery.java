/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.slice;

import static android.slice.SliceItem.TYPE_ACTION;
import static android.slice.SliceItem.TYPE_SLICE;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A bunch of utilities for searching the contents of a slice.
 * @hide
 */
public class SliceQuery {
    private static final String TAG = "SliceQuery";

    /**
     * @hide
     */
    public static SliceItem findNotContaining(SliceItem container, List<SliceItem> list) {
        SliceItem ret = null;
        while (ret == null && list.size() != 0) {
            SliceItem remove = list.remove(0);
            if (!contains(container, remove)) {
                ret = remove;
            }
        }
        return ret;
    }

    /**
     * @hide
     */
    private static boolean contains(SliceItem container, SliceItem item) {
        if (container == null || item == null) return false;
        return stream(container).filter(s -> (s == item)).findAny().isPresent();
    }

    /**
     * @hide
     */
    public static List<SliceItem> findAll(SliceItem s, int type) {
        return findAll(s, type, (String[]) null, null);
    }

    /**
     * @hide
     */
    public static List<SliceItem> findAll(SliceItem s, int type, String hints, String nonHints) {
        return findAll(s, type, new String[]{ hints }, new String[]{ nonHints });
    }

    /**
     * @hide
     */
    public static List<SliceItem> findAll(SliceItem s, int type, String[] hints,
            String[] nonHints) {
        return stream(s).filter(item -> (type == -1 || item.getType() == type)
                && (item.hasHints(hints) && !item.hasAnyHints(nonHints)))
                .collect(Collectors.toList());
    }

    /**
     * @hide
     */
    public static SliceItem find(Slice s, int type, String hints, String nonHints) {
        return find(s, type, new String[]{ hints }, new String[]{ nonHints });
    }

    /**
     * @hide
     */
    public static SliceItem find(Slice s, int type) {
        return find(s, type, (String[]) null, null);
    }

    /**
     * @hide
     */
    public static SliceItem find(SliceItem s, int type) {
        return find(s, type, (String[]) null, null);
    }

    /**
     * @hide
     */
    public static SliceItem find(SliceItem s, int type, String hints, String nonHints) {
        return find(s, type, new String[]{ hints }, new String[]{ nonHints });
    }

    /**
     * @hide
     */
    public static SliceItem find(Slice s, int type, String[] hints, String[] nonHints) {
        return find(new SliceItem(s, TYPE_SLICE, s.getHints()), type, hints, nonHints);
    }

    /**
     * @hide
     */
    public static SliceItem find(SliceItem s, int type, String[] hints, String[] nonHints) {
        return stream(s).filter(item -> (item.getType() == type || type == -1)
                && (item.hasHints(hints) && !item.hasAnyHints(nonHints))).findFirst().orElse(null);
    }

    /**
     * @hide
     */
    public static Stream<SliceItem> stream(SliceItem slice) {
        Queue<SliceItem> items = new LinkedList();
        items.add(slice);
        Iterator<SliceItem> iterator = new Iterator<SliceItem>() {
            @Override
            public boolean hasNext() {
                return items.size() != 0;
            }

            @Override
            public SliceItem next() {
                SliceItem item = items.poll();
                if (item.getType() == TYPE_SLICE || item.getType() == TYPE_ACTION) {
                    items.addAll(Arrays.asList(item.getSlice().getItems()));
                }
                return item;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }
}
