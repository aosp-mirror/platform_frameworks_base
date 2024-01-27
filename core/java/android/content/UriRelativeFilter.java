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

package android.content;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.Flags;
import android.net.Uri;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.util.proto.ProtoOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A filter for matching Intent URI Data as part of a
 * {@link UriRelativeFilterGroup}. A single filter can only be
 * matched against either a URI path, query or fragment
 */
@FlaggedApi(Flags.FLAG_RELATIVE_REFERENCE_INTENT_FILTERS)
public final class UriRelativeFilter {
    private static final String FILTER_STR = "filter";
    private static final String PART_STR = "part";
    private static final String PATTERN_STR = "pattern";
    static final String URI_RELATIVE_FILTER_STR = "uriRelativeFilter";

    /**
     * Value to indicate that the filter is to be applied to a URI path.
     */
    public static final int PATH = 0;
    /**
     * Value to indicate that the filter is to be applied to a URI query.
     */
    public static final int QUERY = 1;
    /**
     * Value to indicate that the filter is to be applied to a URI fragment.
     */
    public static final int FRAGMENT = 2;

    /** @hide */
    @IntDef(value = {
            PATH,
            QUERY,
            FRAGMENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UriPart {}

    private final @UriPart int mUriPart;
    private final @PatternMatcher.PatternType int mPatternType;
    private final String mFilter;

    /**
     * Creates a new UriRelativeFilter.
     *
     * @param uriPart The URI part this filter operates on. Can be either a
     *                {@link UriRelativeFilter#PATH}, {@link UriRelativeFilter#QUERY},
     *                or {@link UriRelativeFilter#FRAGMENT}.
     * @param patternType The pattern type of the filter. Can be either a
     *                    {@link PatternMatcher#PATTERN_LITERAL},
     *                    {@link PatternMatcher#PATTERN_PREFIX},
*                         {@link PatternMatcher#PATTERN_SUFFIX},
     *                    {@link PatternMatcher#PATTERN_SIMPLE_GLOB},
     *                    or {@link PatternMatcher#PATTERN_ADVANCED_GLOB}.
     * @param filter A literal or pattern string depedning on patterType
     *               used to match a uriPart .
     */
    public UriRelativeFilter(
            @UriPart int uriPart,
            @PatternMatcher.PatternType int patternType,
            @NonNull String filter) {
        mUriPart = uriPart;
        com.android.internal.util.AnnotationValidations.validate(
                UriPart.class, null, mUriPart);
        mPatternType = patternType;
        com.android.internal.util.AnnotationValidations.validate(
                PatternMatcher.PatternType.class, null, mPatternType);
        mFilter = filter;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mFilter);
    }

    /**
     * The URI part this filter operates on.
     */
    public @UriPart int getUriPart() {
        return mUriPart;
    }

    /**
     * The pattern type of the filter.
     */
    public @PatternMatcher.PatternType int getPatternType() {
        return mPatternType;
    }

    /**
     * The string used to filter the URI.
     */
    public @NonNull String getFilter() {
        return mFilter;
    }

    /**
     * Match this URI filter against an Intent's data. QUERY filters can
     * match against any key value pair in the query string. PATH and
     * FRAGMENT filters must match the entire string.
     *
     * @param data The full data string to match against, as supplied in
     *             Intent.data.
     *
     * @return true if there is a match.
     */
    public boolean matchData(@NonNull Uri data) {
        PatternMatcher pe = new PatternMatcher(mFilter, mPatternType);
        switch (getUriPart()) {
            case PATH:
                return pe.match(data.getPath());
            case QUERY:
                return matchQuery(pe, data.getQuery());
            case FRAGMENT:
                return pe.match(data.getFragment());
            default:
                return false;
        }
    }

    private boolean matchQuery(PatternMatcher pe, String query) {
        if (query != null) {
            String[] params = query.split("&");
            if (params.length == 1) {
                params = query.split(";");
            }
            for (int i = 0; i < params.length; i++) {
                if (pe.match(params[i])) return true;
            }
        }
        return false;
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(UriRelativeFilterProto.URI_PART, mUriPart);
        proto.write(UriRelativeFilterProto.PATTERN_TYPE, mPatternType);
        proto.write(UriRelativeFilterProto.FILTER, mFilter);
        proto.end(token);
    }

    /** @hide */
    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, URI_RELATIVE_FILTER_STR);
        serializer.attribute(null, PATTERN_STR, Integer.toString(mPatternType));
        serializer.attribute(null, PART_STR, Integer.toString(mUriPart));
        serializer.attribute(null, FILTER_STR, mFilter);
        serializer.endTag(null, URI_RELATIVE_FILTER_STR);
    }

    private String uriPartToString() {
        switch (mUriPart) {
            case PATH:
                return "PATH";
            case QUERY:
                return "QUERY";
            case FRAGMENT:
                return "FRAGMENT";
            default:
                return "UNKNOWN";
        }
    }

    private String patternTypeToString() {
        switch (mPatternType) {
            case PatternMatcher.PATTERN_LITERAL:
                return "LITERAL";
            case PatternMatcher.PATTERN_PREFIX:
                return "PREFIX";
            case PatternMatcher.PATTERN_SIMPLE_GLOB:
                return "GLOB";
            case PatternMatcher.PATTERN_ADVANCED_GLOB:
                return "ADVANCED_GLOB";
            case PatternMatcher.PATTERN_SUFFIX:
                return "SUFFIX";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return "UriRelativeFilter { "
                + "uriPart = " + uriPartToString() + ", "
                + "patternType = " + patternTypeToString() + ", "
                + "filter = " + mFilter
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        UriRelativeFilter that = (UriRelativeFilter) o;
        return mUriPart == that.mUriPart
                && mPatternType == that.mPatternType
                && java.util.Objects.equals(mFilter, that.mFilter);
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mUriPart;
        _hash = 31 * _hash + mPatternType;
        _hash = 31 * _hash + java.util.Objects.hashCode(mFilter);
        return _hash;
    }

    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mUriPart);
        dest.writeInt(mPatternType);
        dest.writeString(mFilter);
    }

    /** @hide */
    UriRelativeFilter(@NonNull android.os.Parcel in) {
        mUriPart = in.readInt();
        mPatternType = in.readInt();
        mFilter = in.readString();
    }

    /** @hide */
    public UriRelativeFilter(XmlPullParser parser) throws XmlPullParserException, IOException {
        mUriPart = Integer.parseInt(parser.getAttributeValue(null, PART_STR));
        mPatternType = Integer.parseInt(parser.getAttributeValue(null, PATTERN_STR));
        mFilter = parser.getAttributeValue(null, FILTER_STR);
    }
}
