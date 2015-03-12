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

package com.android.databinding.reflection;

import com.google.common.base.Preconditions;
import com.android.databinding.util.L;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Class that is used for SDK related stuff.
 * <p>
 * Must be initialized with the sdk location to work properly
 */
public class SdkUtil {

    static File mSdkPath;

    static ApiChecker mApiChecker;

    static int mMinSdk;

    public static void initialize(int minSdk, File sdkPath) {
        mSdkPath = sdkPath;
        mMinSdk = minSdk;
        mApiChecker = new ApiChecker(new File(sdkPath.getAbsolutePath()
                + "/platform-tools/api/api-versions.xml"));
        L.d("SdkUtil init, minSdk: %s", minSdk);
    }

    public static int getMinApi(ModelClass modelClass) {
        return mApiChecker.getMinApi(modelClass.getJniDescription(), null);
    }

    public static int getMinApi(ModelMethod modelMethod) {
        ModelClass declaringClass = modelMethod.getDeclaringClass();
        Preconditions.checkNotNull(mApiChecker, "should've initialized api checker");
        while (declaringClass != null) {
            String classDesc = declaringClass.getJniDescription();
            String methodDesc = modelMethod.getJniDescription();
            int result = mApiChecker.getMinApi(classDesc, methodDesc);
            L.d("checking method api for %s, class:%s method:%s. result: %d", modelMethod.getName(),
                    classDesc, methodDesc, result);
            if (result > 1) {
                return result;
            }
            declaringClass = declaringClass.getSuperclass();
        }
        return 1;
    }

    private static class ApiChecker {

        private Map<String, Integer> mFullLookup = new HashMap<String, Integer>();

        private Document mDoc;

        private XPath mXPath;

        public ApiChecker(File apiFile) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                mDoc = builder.parse(apiFile);
                XPathFactory xPathFactory = XPathFactory.newInstance();
                mXPath = xPathFactory.newXPath();
                buildFullLookup();
            } catch (Throwable t) {
                L.e(t, "cannot load api descriptions from %s", apiFile);
            }
        }

        private void buildFullLookup() throws XPathExpressionException {
            NodeList allClasses = mDoc.getChildNodes().item(0).getChildNodes();
            for (int j = 0; j < allClasses.getLength(); j++) {
                Node node = allClasses.item(j);
                if (node.getNodeType() != Node.ELEMENT_NODE || !"class"
                        .equals(node.getNodeName())) {
                    continue;
                }
                //L.d("checking node %s", node.getAttributes().getNamedItem("name").getNodeValue());
                int classSince = getSince(node);
                String classDesc = node.getAttributes().getNamedItem("name").getNodeValue();

                final NodeList childNodes = node.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node child = childNodes.item(i);
                    if (child.getNodeType() != Node.ELEMENT_NODE || !"method"
                            .equals(child.getNodeName())) {
                        continue;
                    }
                    int methodSince = getSince(child);
                    int since = Math.max(classSince, methodSince);
                    if (since > SdkUtil.mMinSdk) {
                        String methodDesc = child.getAttributes().getNamedItem("name")
                                .getNodeValue();
                        String key = cacheKey(classDesc, methodDesc);
                        mFullLookup.put(key, since);
                    }
                }
            }
        }

        public int getMinApi(String classDesc, String methodOrFieldDesc) {
            if (mDoc == null || mXPath == null) {
                return 1;
            }
            if (classDesc == null || classDesc.isEmpty()) {
                return 1;
            }
            final String key = cacheKey(classDesc, methodOrFieldDesc);
            Integer since = mFullLookup.get(key);
            return since == null ? 1 : since;
        }

        private static String cacheKey(String classDesc, String methodOrFieldDesc) {
            return classDesc + "~" + methodOrFieldDesc;
        }

        private static int getSince(Node node) {
            final Node since = node.getAttributes().getNamedItem("since");
            if (since != null) {
                final String nodeValue = since.getNodeValue();
                if (nodeValue != null && !nodeValue.isEmpty()) {
                    try {
                        return Integer.parseInt(nodeValue);
                    } catch (Throwable t) {
                    }
                }
            }

            return 1;
        }
    }
}
