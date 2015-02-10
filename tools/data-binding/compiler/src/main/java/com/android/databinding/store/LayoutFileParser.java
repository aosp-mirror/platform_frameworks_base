/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.store;

import com.google.common.base.Preconditions;

import com.android.databinding.util.L;
import com.android.databinding.util.ParserHelper;
import com.android.databinding.util.XmlEditor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
 * Gets the list of XML files and creates a list of
 * {@link com.android.databinding.store.ResourceBundle} that can be persistent or converted to
 * LayoutBinder.
 */
public class LayoutFileParser {
    private static final String XPATH_VARIABLE_DEFINITIONS = "//variable";
    private static final String XPATH_BINDING_2_EXPR = "//@*[starts-with(., '@{') and substring(., string-length(.)) = '}']";
    private static final String XPATH_BINDING_ELEMENTS = XPATH_BINDING_2_EXPR + "/..";
    private static final String XPATH_IMPORT_DEFINITIONS = "//import";
    final String LAYOUT_PREFIX = "@layout/";

    public ResourceBundle.LayoutFileBundle parseXml(File xml, String pkg, int layoutId)
            throws ParserConfigurationException, IOException, SAXException,
            XPathExpressionException {
        final File original = stripFileAndGetOriginal(xml, "" + layoutId);
        if (original == null) {
            return null;
        }
        return parseOriginalXml(original, pkg);
    }

    private ResourceBundle.LayoutFileBundle parseOriginalXml(File xml, String pkg)
            throws ParserConfigurationException, IOException, SAXException,
            XPathExpressionException {
        ResourceBundle.LayoutFileBundle bundle = new ResourceBundle.LayoutFileBundle(ParserHelper.INSTANCE$.stripExtension(xml.getName()));

        L.d("parsing file %s", xml.getAbsolutePath());

        bundle.setTransientFile(xml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        final DocumentBuilder builder = factory.newDocumentBuilder();
        final Document doc = builder.parse(xml);

        final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();

        List<Node> variableNodes = getVariableNodes(doc, xPath);

        L.d("number of variable nodes %d", variableNodes.size());
        for (Node item : variableNodes) {
            L.d("reading variable node %s", item);
            NamedNodeMap attributes = item.getAttributes();
            String variableName = attributes.getNamedItem("name").getNodeValue();
            String variableType = attributes.getNamedItem("type").getNodeValue();
            L.d("name: %s, type:%s", variableName, variableType);
            bundle.addVariable(variableName, variableType);
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
            bundle.addImport(alias, type);
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
                    fullClassName = pkg + ".generated." + ParserHelper.INSTANCE$.toClassName(layoutName) + "Binder";
                } else {
                    fullClassName = getFullViewClassName(nodeName);
                }
                final ResourceBundle.BindingTargetBundle bindingTargetBundle = bundle.createBindingTarget(id.getNodeValue(), fullClassName, true);
                bindingTargetBundle.setIncludedLayout(layoutName);
                int attrCount = attributes.getLength();
                for (int i = 0; i < attrCount; i ++) {
                    final Node attr = attributes.item(i);
                    String value = attr.getNodeValue();
                    if (value.charAt(0) == '@' && value.charAt(1) == '{' &&
                            value.charAt(value.length() - 1) == '}') {
                        final String strippedValue = value.substring(2, value.length() - 1);
                        bindingTargetBundle.addBinding(attr.getNodeName(), strippedValue);
                    }
                }
            } else {
                throw new RuntimeException("data binding requires id for now.");
            }
        }

        return bundle;
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
            if (Objects.equals(viewName, "View") || Objects.equals(viewName, "ViewGroup") ||
                    Objects.equals(viewName, "ViewStub")) {
                return "android.view." + viewName;
            }
            return "android.widget." + viewName;
        }
        return viewName;
    }

    private void stripBindingTags(File xml, String newTag) throws IOException {
        String res = XmlEditor.INSTANCE$.strip(xml, newTag);
        if (res != null) {
            L.d("file %s has changed, overwriting %s", xml.getName(), xml.getAbsolutePath());
            FileUtils.writeStringToFile(xml, res);
        }
    }

    private File stripFileAndGetOriginal(File xml, String binderId)
            throws ParserConfigurationException, IOException, SAXException,
            XPathExpressionException {
        L.d("parsing resourceY file %s", xml.getAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xml);
        XPathFactory xPathFactory = XPathFactory.newInstance();
        XPath xPath = xPathFactory.newXPath();
        final XPathExpression commentElementExpr = xPath
                .compile("//comment()[starts-with(., \" From: file:\")][last()]");
        final NodeList commentElementNodes = (NodeList) commentElementExpr
                .evaluate(doc, XPathConstants.NODESET);
        L.d("comment element nodes count %s", commentElementNodes.getLength());
        if (commentElementNodes.getLength() == 0) {
            L.d("cannot find comment element to find the actual file");
            return null;
        }
        final Node first = commentElementNodes.item(0);
        String actualFilePath = first.getNodeValue().substring(" From: file:".length()).trim();
        L.d("actual file to parse: %s", actualFilePath);
        File actualFile = new File(actualFilePath);
        if (!actualFile.canRead()) {
            L.d("cannot find original, skipping. %s", actualFile.getAbsolutePath());
            return null;
        }

        // now if file has any binding expressions, find and delete them
        // TODO we should rely on namespace to avoid parsing file twice
        boolean changed = getVariableNodes(doc, xPath).size() > 0 || getImportNodes(doc, xPath).size() > 0;
        if (changed) {
            stripBindingTags(xml, binderId);
        }
        return actualFile;
    }
}
