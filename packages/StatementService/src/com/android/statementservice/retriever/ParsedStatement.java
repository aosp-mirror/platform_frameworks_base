/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice.retriever;

import java.util.List;

/**
 * A class that stores a list of statement and/or a list of delegate url.
 */
/* package private */ final class ParsedStatement {

    private final List<Statement> mStatements;
    private final List<String> mDelegates;

    public ParsedStatement(List<Statement> statements, List<String> delegates) {
        this.mStatements = statements;
        this.mDelegates = delegates;
    }

    public List<Statement> getStatements() {
        return mStatements;
    }

    public List<String> getDelegates() {
        return mDelegates;
    }
}
