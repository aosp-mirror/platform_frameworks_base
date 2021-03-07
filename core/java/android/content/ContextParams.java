/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * This class represents rules around how a context being created via
 * {@link Context#createContext} should behave.
 *
 * <p>One of the dimensions to customize is how permissions should behave.
 * For example, you can specify how permission accesses from a context should
 * be attributed in the platform's permission tracking system.
 *
 * <p>The two main types of attribution are: against an attribution tag which
 * is an arbitrary string your app specifies for the purposes of tracking permission
 * accesses from a given portion of your app; against another package and optionally
 * its attribution tag if you are accessing the data on behalf of another app and
 * you will be passing that data to this app. Both attributions are not mutually
 * exclusive.
 *
 * <p>For example if you have a feature "foo" in your app which accesses
 * permissions on behalf of app "foo.bar.baz" with feature "bar" you need to
 * create a context like this:
 *
 * <pre class="prettyprint">
 * context.createContext(new ContextParams.Builder()
 *     .setAttributionTag("foo")
 *     .setReceiverPackage("foo.bar.baz", "bar")
 *     .build())
 * </pre>
 *
 * @see Context#createContext(ContextParams)
 */
public final class ContextParams {

    private ContextParams() {
        /* hide ctor */
    }

    /**
     * @return The attribution tag.
     */
    @Nullable
    public String getAttributionTag() {
        return null;
    }

    /**
     * @return The receiving package.
     */
    @Nullable
    public String getReceiverPackage() {
        return null;
    }

    /**
     * @return The receiving package's attribution tag.
     */
    @Nullable
    public String getReceiverAttributionTag() {
        return null;
    }

    /**
     * Builder for creating a {@link ContextParams}.
     */
    public static final class Builder {

        /**
         * Sets an attribution tag against which to track permission accesses.
         *
         * @param attributionTag The attribution tag.
         * @return This builder.
         */
        @NonNull
        public Builder setAttributionTag(@NonNull String attributionTag) {
            return this;
        }

        /**
         * Sets the package and its optional attribution tag that would be receiving
         * the permission protected data.
         *
         * @param packageName The package name receiving the permission protected data.
         * @param attributionTag An attribution tag of the receiving package.
         * @return This builder.
         */
        @NonNull
        public Builder setReceiverPackage(@NonNull String packageName,
                @Nullable String attributionTag) {
            return this;
        }

        /**
         * Creates a new instance. You need to either specify an attribution tag
         * or a receiver package or both.
         *
         * @return The new instance.
         */
        @NonNull
        public ContextParams build() {
            return new ContextParams();
        }
    }
}
