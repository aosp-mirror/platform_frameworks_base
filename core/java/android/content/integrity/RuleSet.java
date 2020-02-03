/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.integrity;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable data class encapsulating all parameters of a rule set.
 *
 * @hide
 */
@TestApi
@SystemApi
public class RuleSet {
    private final String mVersion;
    private final List<Rule> mRules;

    private RuleSet(String version, List<Rule> rules) {
        mVersion = version;
        mRules = Collections.unmodifiableList(rules);
    }

    /** @see Builder#setVersion(String). */
    @NonNull
    public String getVersion() {
        return mVersion;
    }

    /** @see Builder#addRules(List). */
    @NonNull
    public List<Rule> getRules() {
        return mRules;
    }

    /** Builder class for RuleSetUpdateRequest. */
    public static class Builder {
        private String mVersion;
        private List<Rule> mRules;

        public Builder() {
            mRules = new ArrayList<>();
        }

        /**
         * Set a version string to identify this rule set. This can be retrieved by {@link
         * AppIntegrityManager#getCurrentRuleSetVersion()}.
         */
        @NonNull
        public Builder setVersion(@NonNull String version) {
            mVersion = version;
            return this;
        }

        /** Add the rules to include. */
        @NonNull
        public Builder addRules(@NonNull List<Rule> rules) {
            mRules.addAll(rules);
            return this;
        }

        /**
         * Builds a {@link RuleSet}.
         *
         * @throws IllegalArgumentException if version is null
         */
        @NonNull
        public RuleSet build() {
            Objects.requireNonNull(mVersion);
            return new RuleSet(mVersion, mRules);
        }
    }
}
