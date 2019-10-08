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

package com.android.server.integrity.model;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.List;

/**
 * Represents a complex formula consisting of other simple and complex formulas.
 *
 * <p>Instances of this class are immutable.
 */
public final class OpenFormula extends Formula {

    public enum Connector {
        AND,
        OR,
        NOT
    }

    private final Connector mConnector;
    private final List<Formula> mFormulas;

    public OpenFormula(Connector connector, List<Formula> formulas) {
        validateFormulas(connector, formulas);
        this.mConnector = checkNotNull(connector);
        this.mFormulas = Collections.unmodifiableList(checkNotNull(formulas));
    }

    public Connector getConnector() {
        return mConnector;
    }

    public List<Formula> getFormulas() {
        return mFormulas;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mFormulas.size(); i++) {
            if (i > 0) {
                sb.append(String.format(" %s ", mConnector));
            }
            sb.append(mFormulas.get(i).toString());
        }
        return sb.toString();
    }

    private void validateFormulas(Connector connector, List<Formula> formulas) {
        switch (connector) {
            case AND:
            case OR:
                checkArgument(formulas.size() >= 2,
                        String.format("Connector %s must have at least 2 formulas", connector));
                break;
            case NOT:
                checkArgument(formulas.size() == 1,
                        String.format("Connector %s must have 1 formula only", connector));
                break;
        }
    }
}
