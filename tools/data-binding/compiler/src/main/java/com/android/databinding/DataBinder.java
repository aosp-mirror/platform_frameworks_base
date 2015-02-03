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

package com.android.databinding;

import com.google.common.base.Preconditions;

import com.android.databinding.util.L;
import com.android.databinding.util.ParserHelper;

import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * The main class that handles parsing files and generating classes.
 */
public class DataBinder {
    private static final String XPATH_VARIABLE_DEFINITIONS = "//variable";
    private static final String XPATH_BINDING_2_EXPR = "//@*[starts-with(., '{') and substring(., string-length(.)) = '}']";
    private static final String XPATH_BINDING_ELEMENTS = XPATH_BINDING_2_EXPR + "/..";
    private static final String XPATH_IMPORT_DEFINITIONS = "//import";
    final String LAYOUT_PREFIX = "@layout/";

    HashMap<String, List<LayoutBinder>> mLayoutBinders = new HashMap<>();

    public LayoutBinder parseXml(File xml, String pkg)
            throws ParserConfigurationException, IOException, SAXException,
            XPathExpressionException {
        L.d("parsing file %s", xml.getAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final Document doc = builder.parse(xml);

        final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();
        //
        final LayoutBinder layoutBinder = new LayoutBinder(doc);

        List<Node> variableNodes = getVariableNodes(doc, xPath);

        L.d("number of variable nodes %d", variableNodes.size());
        for (Node item : variableNodes) {
            L.d("reading variable node %s", item);
            NamedNodeMap attributes = item.getAttributes();
            String variableName = attributes.getNamedItem("name").getNodeValue();
            String variableType = attributes.getNamedItem("type").getNodeValue();
            L.d("name: %s, type:%s", variableName, variableType);
            layoutBinder.addVariable(variableName, variableType);
        }

        final List<Node> imports = getImportNodes(doc, xPath);
        L.d("import node count %d", imports.size());
        for (Node item : imports) {
            NamedNodeMap attributes = item.getAttributes();
            String type = attributes.getNamedItem("type").getNodeValue();
            final Node aliasNode = attributes.getNamedItem("alias");
            final String alias;
            if (aliasNode == null) {
                final String[] split = StringUtils.split(type, '.');
                alias = split[split.length - 1];
            } else {
                alias = aliasNode.getNodeValue();
            }
            layoutBinder.addImport(alias, type);
        }

        final List<Node> bindingNodes = getBindingNodes(doc, xPath);
        L.d("number of binding nodes %d", bindingNodes.size());
        for (Node parent : bindingNodes) {
            NamedNodeMap attributes = parent.getAttributes();
            Node id = attributes.getNamedItem("android:id");
            if (id != null) {
                String nodeName = parent.getNodeName();
                String layoutName = null;
                final String fullClassName;
                if ("include".equals(nodeName)) {
                    // get the layout attribute
                    final Node includedLayout = attributes.getNamedItem("layout");
                    Preconditions.checkNotNull(includedLayout, "must include a layout");
                    final String includeValue = includedLayout.getNodeValue();
                    Preconditions.checkArgument(includeValue.startsWith(LAYOUT_PREFIX));
                    // if user is binding something there, there MUST be a layout file to be
                    // generated.
                    layoutName = includeValue.substring(LAYOUT_PREFIX.length());
                    L.d("replaced node name to " + nodeName);
                    fullClassName = pkg + "." + ParserHelper.INSTANCE$.toClassName(layoutName) + "Binder";
                } else {
                    fullClassName = getFullViewClassName(nodeName);
                }
                final BindingTarget bindingTarget = layoutBinder
                        .createBindingTarget(id.getNodeValue(), fullClassName, true);
                bindingTarget.setIncludedLayout(layoutName);
                int attrCount = attributes.getLength();
                for (int i = 0; i < attrCount; i ++) {
                    final Node attr = attributes.item(i);
                    String value = attr.getNodeValue();
                    if (value.charAt(0) == '{' && value.charAt(value.length() - 1) == '}') {
                        final String strippedValue = value.substring(1, value.length() - 1);
                        bindingTarget.addBinding(attr.getNodeName(), layoutBinder.parse(strippedValue));
                    }
                }
            } else {
                throw new RuntimeException("data binding requires id for now.");
            }
        }

        if (!layoutBinder.isEmpty()) {
            if (!mLayoutBinders.containsKey(xml.getName())) {
                mLayoutBinders.put(xml.getName(), new ArrayList<LayoutBinder>());
            }
            mLayoutBinders.get(xml.getName()).add(layoutBinder);
        }
        return layoutBinder;
    }

    private List<Node> getBindingNodes(Document doc, XPath xPath) throws XPathExpressionException {
        return get(doc, xPath, XPATH_BINDING_ELEMENTS);
    }

    private List<Node> getVariableNodes(Document doc, XPath xPath) throws XPathExpressionException {
        return get(doc, xPath, XPATH_VARIABLE_DEFINITIONS);
    }

    private List<Node> getImportNodes(Document doc, XPath xPath) throws XPathExpressionException {
        return get(doc, xPath, XPATH_IMPORT_DEFINITIONS);
    }

    private List<Node> get(Document doc, XPath xPath, String pattern)
            throws XPathExpressionException {
        final XPathExpression expr = xPath.compile(pattern);
        return toList((NodeList) expr.evaluate(doc, XPathConstants.NODESET));
    }

    private List<Node> toList(NodeList nodeList) {
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i ++) {
            result.add(nodeList.item(i));
        }
        return result;
    }

    private String getFullViewClassName(String viewName) {
        if (viewName.indexOf('.') == -1) {
            if (Objects.equals(viewName, "View") || Objects.equals(viewName, "ViewGroup")) {
                return "android.view." + viewName;
            }
            return "android.widget." + viewName;
        }
        return viewName;
    }

    public HashMap<String, List<LayoutBinder>> getLayoutBinders() {
        return mLayoutBinders;
    }
}
