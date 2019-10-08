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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;

/**
 * Represents a complex formula consisting of other simple and complex formulas.
 *
 * <p>Instances of this class are immutable.
 */
public final class OpenFormula extends Formula {

    enum Connector {
        AND,
        OR,
        NOT
    }

    private final Connector mConnector;
    private final Formula mMainFormula;
    private final Formula mAuxiliaryFormula;

    public OpenFormula(Connector connector, Formula mainFormula,
            @Nullable Formula auxiliaryFormula) {
        validateAuxiliaryFormula(connector, auxiliaryFormula);
        this.mConnector = checkNotNull(connector);
        this.mMainFormula = checkNotNull(mainFormula);
        // TODO: Add validators on auxiliary formula
        this.mAuxiliaryFormula = auxiliaryFormula;
    }

    public Connector getConnector() {
        return mConnector;
    }

    public Formula getMainFormula() {
        return mMainFormula;
    }

    public Formula getAuxiliaryFormula() {
        return mAuxiliaryFormula;
    }

    private void validateAuxiliaryFormula(Connector connector, Formula auxiliaryFormula) {
        boolean validAuxiliaryFormula;
        switch (connector) {
            case AND:
            case OR:
                validAuxiliaryFormula = (auxiliaryFormula != null);
                break;
            case NOT:
                validAuxiliaryFormula = (auxiliaryFormula == null);
                break;
            default:
                validAuxiliaryFormula = false;
        }
        if (!validAuxiliaryFormula) {
            throw new IllegalArgumentException(
                    String.format("Invalid formulas used for connector %s", connector));
        }
    }
}
