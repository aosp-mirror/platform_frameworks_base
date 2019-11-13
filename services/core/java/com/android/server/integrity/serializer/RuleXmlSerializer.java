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

import android.util.Xml;

import com.android.server.integrity.model.AtomicFormula;
import com.android.server.integrity.model.Formula;
import com.android.server.integrity.model.OpenFormula;
import com.android.server.integrity.model.Rule;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A helper class to serialize rules from the {@link Rule} model to Xml representation.
 */
public class RuleXmlSerializer implements RuleSerializer {

    public static final String TAG = "RuleXmlSerializer";
    private static final String NAMESPACE = "";

    private static final String RULE_LIST_TAG = "RL";
    private static final String RULE_TAG = "R";
    private static final String OPEN_FORMULA_TAG = "OF";
    private static final String ATOMIC_FORMULA_TAG = "AF";
    private static final String EFFECT_TAG = "E";
    private static final String KEY_TAG = "K";
    private static final String OPERATOR_TAG = "O";
    private static final String VALUE_TAG = "V";
    private static final String CONNECTOR_TAG = "C";

    @Override
    public void serialize(List<Rule> rules, OutputStream outputStream)
            throws RuleSerializeException {
        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            xmlSerializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
            serializeRules(rules, xmlSerializer);
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    @Override
    public String serialize(List<Rule> rules) throws RuleSerializeException {
        try {
            XmlSerializer xmlSerializer = Xml.newSerializer();
            StringWriter writer = new StringWriter();
            xmlSerializer.setOutput(writer);
            serializeRules(rules, xmlSerializer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    private void serializeRules(List<Rule> rules, XmlSerializer xmlSerializer) throws IOException {
        xmlSerializer.startTag(NAMESPACE, RULE_LIST_TAG);
        for (Rule rule : rules) {
            serialize(rule, xmlSerializer);
        }
        xmlSerializer.endTag(NAMESPACE, RULE_LIST_TAG);
        xmlSerializer.endDocument();
    }

    private void serialize(Rule rule, XmlSerializer xmlSerializer) throws IOException {
        if (rule == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, RULE_TAG);
        serializeFormula(rule.getFormula(), xmlSerializer);
        serializeValue(EFFECT_TAG, String.valueOf(rule.getEffect()), xmlSerializer);
        xmlSerializer.endTag(NAMESPACE, RULE_TAG);
    }

    private void serializeFormula(Formula formula, XmlSerializer xmlSerializer) throws IOException {
        if (formula instanceof AtomicFormula) {
            serializeAtomicFormula((AtomicFormula) formula, xmlSerializer);
        } else if (formula instanceof OpenFormula) {
            serializeOpenFormula((OpenFormula) formula, xmlSerializer);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid formula type: %s", formula.getClass()));
        }
    }

    private void serializeOpenFormula(OpenFormula openFormula, XmlSerializer xmlSerializer)
            throws IOException {
        if (openFormula == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, OPEN_FORMULA_TAG);
        serializeValue(CONNECTOR_TAG, String.valueOf(openFormula.getConnector()), xmlSerializer);
        for (Formula formula : openFormula.getFormulas()) {
            serializeFormula(formula, xmlSerializer);
        }
        xmlSerializer.endTag(NAMESPACE, OPEN_FORMULA_TAG);
    }

    private void serializeAtomicFormula(AtomicFormula atomicFormula, XmlSerializer xmlSerializer)
            throws IOException {
        if (atomicFormula == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, ATOMIC_FORMULA_TAG);
        serializeValue(KEY_TAG, String.valueOf(atomicFormula.getKey()), xmlSerializer);
        if (atomicFormula instanceof AtomicFormula.StringAtomicFormula) {
            serializeValue(VALUE_TAG,
                    ((AtomicFormula.StringAtomicFormula) atomicFormula).getValue(), xmlSerializer);
        } else if (atomicFormula instanceof AtomicFormula.IntAtomicFormula) {
            serializeValue(OPERATOR_TAG,
                    String.valueOf(((AtomicFormula.IntAtomicFormula) atomicFormula).getOperator()),
                    xmlSerializer);
            serializeValue(VALUE_TAG,
                    String.valueOf(((AtomicFormula.IntAtomicFormula) atomicFormula).getValue()),
                    xmlSerializer);
        } else if (atomicFormula instanceof AtomicFormula.BooleanAtomicFormula) {
            serializeValue(VALUE_TAG,
                    String.valueOf(((AtomicFormula.BooleanAtomicFormula) atomicFormula).getValue()),
                    xmlSerializer);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid atomic formula type: %s", atomicFormula.getClass()));
        }
        xmlSerializer.endTag(NAMESPACE, ATOMIC_FORMULA_TAG);
    }

    private void serializeValue(String tag, String value, XmlSerializer xmlSerializer)
            throws IOException {
        if (value == null) {
            return;
        }
        xmlSerializer.startTag(NAMESPACE, tag);
        xmlSerializer.text(value);
        xmlSerializer.endTag(NAMESPACE, tag);
    }
}
