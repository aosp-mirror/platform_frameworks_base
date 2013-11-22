/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.firewall;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.PatternMatcher;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.regex.Pattern;

abstract class StringFilter implements Filter {
    private static final String ATTR_EQUALS = "equals";
    private static final String ATTR_STARTS_WITH = "startsWith";
    private static final String ATTR_CONTAINS = "contains";
    private static final String ATTR_PATTERN = "pattern";
    private static final String ATTR_REGEX = "regex";
    private static final String ATTR_IS_NULL = "isNull";

    private final ValueProvider mValueProvider;

    private StringFilter(ValueProvider valueProvider) {
        this.mValueProvider = valueProvider;
    }

    /**
     * Constructs a new StringFilter based on the string filter attribute on the current
     * element, and the given StringValueMatcher.
     *
     * The current node should contain exactly 1 string filter attribute. E.g. equals,
     * contains, etc. Otherwise, an XmlPullParserException will be thrown.
     *
     * @param parser      An XmlPullParser object positioned at an element that should
     *                    contain a string filter attribute
     * @return This StringFilter object
     */
    public static StringFilter readFromXml(ValueProvider valueProvider, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        StringFilter filter = null;

        for (int i=0; i<parser.getAttributeCount(); i++) {
            StringFilter newFilter = getFilter(valueProvider, parser, i);
            if (newFilter != null) {
                if (filter != null) {
                    throw new XmlPullParserException("Multiple string filter attributes found");
                }
                filter = newFilter;
            }
        }

        if (filter == null) {
            // if there are no string filter attributes, we default to isNull="false" so that an
            // empty filter is equivalent to an existence check
            filter = new IsNullFilter(valueProvider, false);
        }

        return filter;
    }

    private static StringFilter getFilter(ValueProvider valueProvider, XmlPullParser parser,
            int attributeIndex) {
        String attributeName = parser.getAttributeName(attributeIndex);

        switch (attributeName.charAt(0)) {
            case 'e':
                if (!attributeName.equals(ATTR_EQUALS)) {
                    return null;
                }
                return new EqualsFilter(valueProvider, parser.getAttributeValue(attributeIndex));
            case 'i':
                if (!attributeName.equals(ATTR_IS_NULL)) {
                    return null;
                }
                return new IsNullFilter(valueProvider, parser.getAttributeValue(attributeIndex));
            case 's':
                if (!attributeName.equals(ATTR_STARTS_WITH)) {
                    return null;
                }
                return new StartsWithFilter(valueProvider,
                        parser.getAttributeValue(attributeIndex));
            case 'c':
                if (!attributeName.equals(ATTR_CONTAINS)) {
                    return null;
                }
                return new ContainsFilter(valueProvider, parser.getAttributeValue(attributeIndex));
            case 'p':
                if (!attributeName.equals(ATTR_PATTERN)) {
                    return null;
                }
                return new PatternStringFilter(valueProvider,
                        parser.getAttributeValue(attributeIndex));
            case 'r':
                if (!attributeName.equals(ATTR_REGEX)) {
                    return null;
                }
                return new RegexFilter(valueProvider, parser.getAttributeValue(attributeIndex));
        }
        return null;
    }

    protected abstract boolean matchesValue(String value);

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        String value = mValueProvider.getValue(resolvedComponent, intent, resolvedType);
        return matchesValue(value);
    }

    private static abstract class ValueProvider extends FilterFactory {
        protected ValueProvider(String tag) {
            super(tag);
        }

        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            return StringFilter.readFromXml(this, parser);
        }

        public abstract String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType);
    }

    private static class EqualsFilter extends StringFilter {
        private final String mFilterValue;

        public EqualsFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            mFilterValue = attrValue;
        }

        @Override
        public boolean matchesValue(String value) {
            return value != null && value.equals(mFilterValue);
        }
    }

    private static class ContainsFilter extends StringFilter {
        private final String mFilterValue;

        public ContainsFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            mFilterValue = attrValue;
        }

        @Override
        public boolean matchesValue(String value) {
            return value != null && value.contains(mFilterValue);
        }
    }

    private static class StartsWithFilter extends StringFilter {
        private final String mFilterValue;

        public StartsWithFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            mFilterValue = attrValue;
        }

        @Override
        public boolean matchesValue(String value) {
            return value != null && value.startsWith(mFilterValue);
        }
    }

    private static class PatternStringFilter extends StringFilter {
        private final PatternMatcher mPattern;

        public PatternStringFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            mPattern = new PatternMatcher(attrValue, PatternMatcher.PATTERN_SIMPLE_GLOB);
        }

        @Override
        public boolean matchesValue(String value) {
            return value != null && mPattern.match(value);
        }
    }

    private static class RegexFilter extends StringFilter {
        private final Pattern mPattern;

        public RegexFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            this.mPattern = Pattern.compile(attrValue);
        }

        @Override
        public boolean matchesValue(String value) {
            return value != null && mPattern.matcher(value).matches();
        }
    }

    private static class IsNullFilter extends StringFilter {
        private final boolean mIsNull;

        public IsNullFilter(ValueProvider valueProvider, String attrValue) {
            super(valueProvider);
            mIsNull = Boolean.parseBoolean(attrValue);
        }

        public IsNullFilter(ValueProvider valueProvider, boolean isNull) {
            super(valueProvider);
            mIsNull = isNull;
        }

        @Override
        public boolean matchesValue(String value) {
            return (value == null) == mIsNull;
        }
    }

    public static final ValueProvider COMPONENT = new ValueProvider("component") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            if (resolvedComponent != null) {
                return resolvedComponent.flattenToString();
            }
            return null;
        }
    };

    public static final ValueProvider COMPONENT_NAME = new ValueProvider("component-name") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            if (resolvedComponent != null) {
                return resolvedComponent.getClassName();
            }
            return null;
        }
    };

    public static final ValueProvider COMPONENT_PACKAGE = new ValueProvider("component-package") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            if (resolvedComponent != null) {
                return resolvedComponent.getPackageName();
            }
            return null;
        }
    };

    public static final FilterFactory ACTION = new ValueProvider("action") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            return intent.getAction();
        }
    };

    public static final ValueProvider DATA = new ValueProvider("data") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            Uri data = intent.getData();
            if (data != null) {
                return data.toString();
            }
            return null;
        }
    };

    public static final ValueProvider MIME_TYPE = new ValueProvider("mime-type") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            return resolvedType;
        }
    };

    public static final ValueProvider SCHEME = new ValueProvider("scheme") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            Uri data = intent.getData();
            if (data != null) {
                return data.getScheme();
            }
            return null;
        }
    };

    public static final ValueProvider SSP = new ValueProvider("scheme-specific-part") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            Uri data = intent.getData();
            if (data != null) {
                return data.getSchemeSpecificPart();
            }
            return null;
        }
    };

    public static final ValueProvider HOST = new ValueProvider("host") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            Uri data = intent.getData();
            if (data != null) {
                return data.getHost();
            }
            return null;
        }
    };

    public static final ValueProvider PATH = new ValueProvider("path") {
        @Override
        public String getValue(ComponentName resolvedComponent, Intent intent,
                String resolvedType) {
            Uri data = intent.getData();
            if (data != null) {
                return data.getPath();
            }
            return null;
        }
    };
}
