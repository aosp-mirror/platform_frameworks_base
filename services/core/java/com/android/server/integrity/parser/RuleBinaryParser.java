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

package com.android.server.integrity.parser;

import static com.android.server.integrity.model.ComponentBitSize.ATOMIC_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_END;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.CONNECTOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.EFFECT_BITS;
import static com.android.server.integrity.model.ComponentBitSize.FORMAT_VERSION_BITS;
import static com.android.server.integrity.model.ComponentBitSize.IS_HASHED_BITS;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SIGNAL_BIT;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.parser.BinaryFileOperations.getBooleanValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getIntValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getStringValue;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import com.android.server.integrity.model.BitTrackedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** A helper class to parse rules into the {@link Rule} model from Binary representation. */
public class RuleBinaryParser implements RuleParser {

    @Override
    public List<Rule> parse(byte[] ruleBytes) throws RuleParseException {
        try {
            BitTrackedInputStream bitTrackedInputStream = new BitTrackedInputStream(ruleBytes);
            return parseRules(bitTrackedInputStream);
        } catch (Exception e) {
            throw new RuleParseException(e.getMessage(), e);
        }
    }

    @Override
    public List<Rule> parse(InputStream inputStream) throws RuleParseException {
        try {
            BitTrackedInputStream bitTrackedInputStream = new BitTrackedInputStream(inputStream);
            return parseRules(bitTrackedInputStream);
        } catch (Exception e) {
            throw new RuleParseException(e.getMessage(), e);
        }
    }

    private List<Rule> parseRules(BitTrackedInputStream bitTrackedInputStream) throws IOException {
        List<Rule> parsedRules = new ArrayList<>();

        // Read the rule binary file format version.
        bitTrackedInputStream.getNext(FORMAT_VERSION_BITS);

        while (bitTrackedInputStream.hasNext()) {
            if (bitTrackedInputStream.getNext(SIGNAL_BIT) == 1) {
                parsedRules.add(parseRule(bitTrackedInputStream));
            }
        }

        return parsedRules;
    }

    private Rule parseRule(BitTrackedInputStream bitTrackedInputStream) throws IOException {
        Formula formula = parseFormula(bitTrackedInputStream);
        int effect = bitTrackedInputStream.getNext(EFFECT_BITS);

        if (bitTrackedInputStream.getNext(SIGNAL_BIT) != 1) {
            throw new IllegalArgumentException("A rule must end with a '1' bit.");
        }

        return new Rule(formula, effect);
    }

    private Formula parseFormula(BitTrackedInputStream bitTrackedInputStream) throws IOException {
        int separator = bitTrackedInputStream.getNext(SEPARATOR_BITS);
        switch (separator) {
            case ATOMIC_FORMULA_START:
                return parseAtomicFormula(bitTrackedInputStream);
            case COMPOUND_FORMULA_START:
                return parseCompoundFormula(bitTrackedInputStream);
            case COMPOUND_FORMULA_END:
                return null;
            default:
                throw new IllegalArgumentException(
                        String.format("Unknown formula separator: %s", separator));
        }
    }

    private CompoundFormula parseCompoundFormula(BitTrackedInputStream bitTrackedInputStream)
            throws IOException {
        int connector = bitTrackedInputStream.getNext(CONNECTOR_BITS);
        List<Formula> formulas = new ArrayList<>();

        Formula parsedFormula = parseFormula(bitTrackedInputStream);
        while (parsedFormula != null) {
            formulas.add(parsedFormula);
            parsedFormula = parseFormula(bitTrackedInputStream);
        }

        return new CompoundFormula(connector, formulas);
    }

    private AtomicFormula parseAtomicFormula(BitTrackedInputStream bitTrackedInputStream)
            throws IOException {
        int key = bitTrackedInputStream.getNext(KEY_BITS);
        int operator = bitTrackedInputStream.getNext(OPERATOR_BITS);

        switch (key) {
            case AtomicFormula.PACKAGE_NAME:
            case AtomicFormula.APP_CERTIFICATE:
            case AtomicFormula.INSTALLER_NAME:
            case AtomicFormula.INSTALLER_CERTIFICATE:
                boolean isHashedValue = bitTrackedInputStream.getNext(IS_HASHED_BITS) == 1;
                int valueSize = bitTrackedInputStream.getNext(VALUE_SIZE_BITS);
                String stringValue = getStringValue(bitTrackedInputStream, valueSize,
                        isHashedValue);
                return new AtomicFormula.StringAtomicFormula(key, stringValue, isHashedValue);
            case AtomicFormula.VERSION_CODE:
                int intValue = getIntValue(bitTrackedInputStream);
                return new AtomicFormula.IntAtomicFormula(key, operator, intValue);
            case AtomicFormula.PRE_INSTALLED:
                boolean booleanValue = getBooleanValue(bitTrackedInputStream);
                return new AtomicFormula.BooleanAtomicFormula(key, booleanValue);
            default:
                throw new IllegalArgumentException(String.format("Unknown key: %d", key));
        }
    }
}
