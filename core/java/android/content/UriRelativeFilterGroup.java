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
import android.content.pm.Flags;
import android.net.Uri;
import android.os.Parcel;
import android.util.ArraySet;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.CollectionUtils;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;

/**
 * An intent data matching group based on a URI's relative reference which
 * includes the path, query and fragment.  The group is only considered as
 * matching if <em>all</em> UriRelativeFilters in the group match.  Each
 * UriRelativeFilter defines a matching rule for a URI path, query or fragment.
 * A group must contain one or more UriRelativeFilters to match but does not need to
 * contain UriRelativeFilters for all existing parts of a URI to match.
 *
 * <p>For example, given a URI that contains path, query and fragment parts,
 * a group containing only a path filter will match the URI if the path
 * filter matches the URI path.  If the group contains a path and query
 * filter, then the group will only match if both path and query filters
 * match.  If a URI contains only a path with no query or fragment then a
 * group can only match if it contains only a matching path filter. If the
 * group also contained additional query or fragment filters then it will
 * not match.</p>
 */
@FlaggedApi(Flags.FLAG_RELATIVE_REFERENCE_INTENT_FILTERS)
public final class UriRelativeFilterGroup {
    private static final String ALLOW_STR = "allow";
    private static final String URI_RELATIVE_FILTER_GROUP_STR = "uriRelativeFilterGroup";

    /**
     * Value to indicate that the group match is allowed.
     */
    public static final int ACTION_ALLOW = 0;
    /**
     * Value to indicate that the group match is blocked.
     */
    public static final int ACTION_BLOCK = 1;

    /** @hide */
    @IntDef(value = {
            ACTION_ALLOW,
            ACTION_BLOCK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {}

    private final @Action int mAction;
    private final ArraySet<UriRelativeFilter> mUriRelativeFilters = new ArraySet<>();

    /**
     * New UriRelativeFilterGroup that matches a Intent data.
     *
     * @param action Whether this matching group should be allowed or disallowed.
     */
    public UriRelativeFilterGroup(@Action int action) {
        mAction = action;
    }

    /** @hide */
    public UriRelativeFilterGroup(XmlPullParser parser) throws XmlPullParserException, IOException {
        mAction = Integer.parseInt(parser.getAttributeValue(null, ALLOW_STR));

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(UriRelativeFilter.URI_RELATIVE_FILTER_STR)) {
                addUriRelativeFilter(new UriRelativeFilter(parser));
            } else {
                Log.w("IntentFilter", "Unknown tag parsing IntentFilter: " + tagName);
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    /**
     * Return {@link UriRelativeFilterGroup#ACTION_ALLOW} if a URI is allowed when matched
     * and {@link UriRelativeFilterGroup#ACTION_BLOCK} if a URI is blacked when matched.
     */
    public @Action int getAction() {
        return mAction;
    }

    /**
     * Add a filter to the group.
     */
    public void addUriRelativeFilter(@NonNull UriRelativeFilter uriRelativeFilter) {
        Objects.requireNonNull(uriRelativeFilter);
        if (!CollectionUtils.contains(mUriRelativeFilters, uriRelativeFilter)) {
            mUriRelativeFilters.add(uriRelativeFilter);
        }
    }

    /**
     * Returns a unmodifiable view of the UriRelativeFilters list in this group.
     */
    @NonNull
    public Collection<UriRelativeFilter> getUriRelativeFilters() {
        return Collections.unmodifiableCollection(mUriRelativeFilters);
    }

    /**
     * Match all URI filter in this group against {@link Intent#getData()}.
     *
     * @param data The full data string to match against, as supplied in
     *             Intent.data.
     * @return true if all filters match.
     */
    public boolean matchData(@NonNull Uri data) {
        if (mUriRelativeFilters.size() == 0) {
            return false;
        }
        for (UriRelativeFilter filter : mUriRelativeFilters) {
            if (!filter.matchData(data)) {
                return false;
            }
        }
        return true;
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(UriRelativeFilterGroupProto.ACTION, mAction);
        Iterator<UriRelativeFilter> it = mUriRelativeFilters.iterator();
        while (it.hasNext()) {
            it.next().dumpDebug(proto, UriRelativeFilterGroupProto.URI_RELATIVE_FILTERS);
        }
        proto.end(token);
    }

    /** @hide */
    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, URI_RELATIVE_FILTER_GROUP_STR);
        serializer.attribute(null, ALLOW_STR, Integer.toString(mAction));
        Iterator<UriRelativeFilter> it = mUriRelativeFilters.iterator();
        while (it.hasNext()) {
            UriRelativeFilter filter = it.next();
            filter.writeToXml(serializer);
        }
        serializer.endTag(null, URI_RELATIVE_FILTER_GROUP_STR);
    }

    @Override
    public String toString() {
        return "UriRelativeFilterGroup { allow = " + mAction
                + ", uri_filters = " + mUriRelativeFilters + ",  }";
    }

    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAction);
        final int n = mUriRelativeFilters.size();
        if (n > 0) {
            dest.writeInt(n);
            Iterator<UriRelativeFilter> it = mUriRelativeFilters.iterator();
            while (it.hasNext()) {
                it.next().writeToParcel(dest, flags);
            }
        } else {
            dest.writeInt(0);
        }
    }

    /** @hide */
    UriRelativeFilterGroup(@NonNull Parcel src) {
        mAction = src.readInt();
        final int n = src.readInt();
        for (int i = 0; i < n; i++) {
            mUriRelativeFilters.add(new UriRelativeFilter(src));
        }
    }
}
