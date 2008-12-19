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

package com.android.unit_tests.content;

import android.content.IntentFilter;
import android.test.suitebuilder.annotation.SmallTest;
import static android.os.PatternMatcher.PATTERN_LITERAL;
import static android.os.PatternMatcher.PATTERN_PREFIX;
import static android.os.PatternMatcher.PATTERN_SIMPLE_GLOB;
import android.net.Uri;
import android.util.StringBuilderPrinter;
import junit.framework.TestCase;

import java.util.HashSet;

public class IntentFilterTest extends TestCase {

    public static class Match extends IntentFilter {
        Match(String[] actions, String[] categories, String[] mimeTypes,
                String[] schemes, String[] authorities, String[] ports) {
            if (actions != null) {
                for (int i = 0; i < actions.length; i++) {
                    addAction(actions[i]);
                }
            }
            if (categories != null) {
                for (int i = 0; i < categories.length; i++) {
                    addCategory(categories[i]);
                }
            }
            if (mimeTypes != null) {
                for (int i = 0; i < mimeTypes.length; i++) {
                    try {
                        addDataType(mimeTypes[i]);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        throw new RuntimeException("Bad mime type", e);
                    }
                }
            }
            if (schemes != null) {
                for (int i = 0; i < schemes.length; i++) {
                    addDataScheme(schemes[i]);
                }
            }
            if (authorities != null) {
                for (int i = 0; i < authorities.length; i++) {
                    addDataAuthority(authorities[i],
                            ports != null ? ports[i] : null);
                }
            }
        }

        Match(String[] actions, String[] categories, String[] mimeTypes,
                String[] schemes, String[] authorities, String[] ports,
                String[] paths, int[] pathTypes) {
            this(actions, categories, mimeTypes, schemes, authorities, ports);
            if (paths != null) {
                for (int i = 0; i < paths.length; i++) {
                    addDataPath(paths[i], pathTypes[i]);
                }
            }
        }
    }

    public static class MatchCondition {
        public final int result;
        public final String action;
        public final String mimeType;
        public final Uri data;
        public final String[] categories;

        public MatchCondition(int _result, String _action, String[] _categories,
                String _mimeType, String _data) {
            result = _result;
            action = _action;
            mimeType = _mimeType;
            data = _data != null ? Uri.parse(_data) : null;
            categories = _categories;
        }
    }

    public static void checkMatches(IntentFilter filter,
            MatchCondition[] results) {
        for (int i = 0; i < results.length; i++) {
            MatchCondition mc = results[i];
            HashSet<String> categories = null;
            if (mc.categories != null) {
                for (int j = 0; j < mc.categories.length; j++) {
                    if (categories == null) {
                        categories = new HashSet<String>();
                    }
                    categories.add(mc.categories[j]);
                }
            }
            int result = filter.match(mc.action, mc.mimeType,
                    mc.data != null ? mc.data.getScheme() : null, mc.data,
                    categories, "test");
            if ( (result & IntentFilter.MATCH_CATEGORY_MASK)
                    != (mc.result & IntentFilter.MATCH_CATEGORY_MASK) ) {
                StringBuilder msg = new StringBuilder();
                msg.append("Error matching against IntentFilter:\n");
                filter.dump(new StringBuilderPrinter(msg), "    ");
                msg.append("Match action: ");
                msg.append(mc.action);
                msg.append("\nMatch mimeType: ");
                msg.append(mc.mimeType);
                msg.append("\nMatch data: ");
                msg.append(mc.data);
                msg.append("\nMatch categories: ");
                if (mc.categories != null) {
                    for (int j = 0; j < mc.categories.length; j++) {
                        if (j > 0) msg.append(", ");
                        msg.append(mc.categories[j]);
                    }
                }
                msg.append("\nExpected result: 0x");
                msg.append(Integer.toHexString(mc.result));
                msg.append(", got result: 0x");
                msg.append(Integer.toHexString(result));
                throw new RuntimeException(msg.toString());
            }
        }
    }

    @SmallTest
    public void testActions() throws Exception {
        IntentFilter filter = new Match(
                new String[]{"action1"}, null, null, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, "action1",
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_ACTION, "action2",
                        null, null, null),
        });

        filter = new Match(
                new String[]{"action1", "action2"},
                null, null, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, "action1",
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, "action2",
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_ACTION, "action3",
                        null, null, null),
        });
    }

    @SmallTest
    public void testCategories() throws Exception {
        IntentFilter filter = new Match(
                null, new String[]{"category1"}, null, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        new String[]{"category1"}, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_CATEGORY, null,
                        new String[]{"category2"}, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_CATEGORY, null,
                        new String[]{"category1", "category2"}, null, null),
        });

        filter = new Match(
                null, new String[]{"category1", "category2"}, null, null,
                null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        new String[]{"category1"}, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        new String[]{"category2"}, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_EMPTY, null,
                        new String[]{"category1", "category2"}, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_CATEGORY, null,
                        new String[]{"category3"}, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_CATEGORY, null,
                        new String[]{"category1", "category2", "category3"},
                        null, null),
        });
    }

    @SmallTest
    public void testMimeTypes() throws Exception {
        IntentFilter filter = new Match(
                null, null, new String[]{"which1/what1"}, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what1", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "*/*", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which2/what2", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which2/*", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which1/what2", null),
        });

        filter = new Match(null, null,
                new String[]{"which1/what1", "which2/what2"}, null, null,
                null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what1", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "*/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which2/what2", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which2/*", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which1/what2", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which3/what3", null),
        });

        filter = new Match(null, null,
                new String[]{"which1/*"}, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what1", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "*/*", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which2/what2", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which2/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what2", null),
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null, null,
                        "which3/what3", null),
        });

        filter = new Match(null, null,
                new String[]{"*/*"}, null, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_TYPE, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what1", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "*/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which2/what2", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which2/*", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which1/what2", null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_TYPE, null, null,
                        "which3/what3", null),
        });
    }

    @SmallTest
    public void testSchemes() throws Exception {
        IntentFilter filter = new Match(null, null, null,
                new String[]{"scheme1"}, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_SCHEME, null,
                        null, null, "scheme1:foo"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme2:foo"),
        });

        filter = new Match(null, null, null,
                new String[]{"scheme1", "scheme2"}, null, null);
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_SCHEME, null,
                        null, null, "scheme1:foo"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_SCHEME, null,
                        null, null, "scheme2:foo"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme3:foo"),
        });
    }

    @SmallTest
    public void testAuthorities() throws Exception {
        IntentFilter filter = new Match(null, null, null,
                new String[]{"scheme1"},
                new String[]{"authority1"}, new String[]{null});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1:foo"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_HOST, null,
                        null, null, "scheme1://authority1/"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority2/"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_HOST, null,
                        null, null, "scheme1://authority1:100/"),
        });

        filter = new Match(null, null, null, new String[]{"scheme1"},
                new String[]{"authority1"}, new String[]{"100"});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1:foo"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority1/"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority2/"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PORT, null,
                        null, null, "scheme1://authority1:100/"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority1:200/"),
        });

        filter = new Match(null, null, null, new String[]{"scheme1"},
                new String[]{"authority1", "authority2"},
                new String[]{"100", null});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1:foo"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority1/"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_HOST, null,
                        null, null, "scheme1://authority2/"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PORT, null,
                        null, null, "scheme1://authority1:100/"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme1://authority1:200/"),
        });
    }

    @SmallTest
    public void testPaths() throws Exception {
        IntentFilter filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/literal1", "/2literal"},
                new int[]{PATTERN_LITERAL, PATTERN_LITERAL});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/literal1"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/2literal"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/literal"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/literal12"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/literal1", "/2literal"},
                new int[]{PATTERN_PREFIX, PATTERN_PREFIX});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/literal1"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/2literal"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/literal"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/literal12"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/.*"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/literal1"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{".*"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/literal1"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a1*b"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a1b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a11b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a2b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a1bc"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a1*"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a1"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a11"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a1b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a11"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a2"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a\\.*b"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a..b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a2b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a.bc"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a.*b"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.1b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a2b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a.bc"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a.*"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.1b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a2b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.bc"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a.\\*b"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.*b"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a1*b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a2b"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a.bc"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/"),
        });
        filter = new Match(null, null, null,
                new String[]{"scheme"}, new String[]{"authority"}, null,
                new String[]{"/a.\\*"},
                new int[]{PATTERN_SIMPLE_GLOB});
        checkMatches(filter, new MatchCondition[]{
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, null),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/ab"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a.*"),
                new MatchCondition(IntentFilter.MATCH_CATEGORY_PATH, null,
                        null, null, "scheme://authority/a1*"),
                new MatchCondition(IntentFilter.NO_MATCH_DATA, null,
                        null, null, "scheme://authority/a1b"),
        });
    }

}

