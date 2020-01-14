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

package com.android.server.integrity.serializer;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Holds the indexing type and indexing key of a given formula. */
class RuleIndexingDetails {

    static final int NOT_INDEXED = 0;
    static final int PACKAGE_NAME_INDEXED = 1;
    static final int APP_CERTIFICATE_INDEXED = 2;

    static final String DEFAULT_RULE_KEY = "N/A";

    /** Represents which indexed file the rule should be located. */
    @IntDef(
            value = {
                    NOT_INDEXED,
                    PACKAGE_NAME_INDEXED,
                    APP_CERTIFICATE_INDEXED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndexType {
    }

    private @IndexType int mIndexType;
    private String mRuleKey;

    /** Constructor without a ruleKey for {@code NOT_INDEXED}. */
    RuleIndexingDetails(@IndexType int indexType) {
        this.mIndexType = indexType;
        this.mRuleKey = DEFAULT_RULE_KEY;
    }

    /** Constructor with a ruleKey for indexed rules. */
    RuleIndexingDetails(@IndexType int indexType, String ruleKey) {
        this.mIndexType = indexType;
        this.mRuleKey = ruleKey;
    }

    /** Returns the indexing type for the rule. */
    @IndexType
    public int getIndexType() {
        return mIndexType;
    }

    /** Returns the identified rule key. */
    public String getRuleKey() {
        return mRuleKey;
    }
}
