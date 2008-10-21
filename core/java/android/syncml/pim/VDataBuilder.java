/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.syncml.pim;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.net.QuotedPrintableCodec;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Store the parse result to custom datastruct: VNode, PropertyNode
 * Maybe several vcard instance, so use vNodeList to store.
 * VNode: standy by a vcard instance.
 * PropertyNode: standy by a property line of a card.
 */
public class VDataBuilder implements VBuilder {

    /** type=VNode */
    public ArrayList<VNode> vNodeList = new ArrayList<VNode>();
    int nodeListPos = 0;
    VNode curVNode;
    PropertyNode curPropNode;
    String curParamType;

    public void start() {
    }

    public void end() {
    }

    public void startRecord(String type) {
        VNode vnode = new VNode();
        vnode.parseStatus = 1;
        vnode.VName = type;
        vNodeList.add(vnode);
        nodeListPos = vNodeList.size()-1;
        curVNode = vNodeList.get(nodeListPos);
    }

    public void endRecord() {
        VNode endNode = vNodeList.get(nodeListPos);
        endNode.parseStatus = 0;
        while(nodeListPos > 0){
            nodeListPos--;
            if((vNodeList.get(nodeListPos)).parseStatus == 1)
                break;
        }
        curVNode = vNodeList.get(nodeListPos);
    }

    public void startProperty() {
    //  System.out.println("+ startProperty. ");
    }

    public void endProperty() {
    //  System.out.println("- endProperty. ");
    }

    public void propertyName(String name) {
        curPropNode = new PropertyNode();
        curPropNode.propName = name;
    }

    public void propertyParamType(String type) {
        curParamType = type;
    }

    public void propertyParamValue(String value) {
        if(curParamType == null)
            curPropNode.paraMap_TYPE.add(value);
        else if(curParamType.equalsIgnoreCase("TYPE"))
            curPropNode.paraMap_TYPE.add(value);
        else
            curPropNode.paraMap.put(curParamType, value);

        curParamType = null;
    }

    public void propertyValues(Collection<String> values) {
        curPropNode.propValue_vector = values;
        curPropNode.propValue = listToString(values);
        //decode value string to propValue_byts
        if(curPropNode.paraMap.containsKey("ENCODING")){
            if(curPropNode.paraMap.getAsString("ENCODING").
                                        equalsIgnoreCase("BASE64")){
                curPropNode.propValue_byts =
                    Base64.decodeBase64(curPropNode.propValue.
                            replaceAll(" ","").replaceAll("\t","").
                            replaceAll("\r\n","").
                            getBytes());
            }
            if(curPropNode.paraMap.getAsString("ENCODING").
                                        equalsIgnoreCase("QUOTED-PRINTABLE")){
                try{
                    curPropNode.propValue_byts =
                        QuotedPrintableCodec.decodeQuotedPrintable(
                                curPropNode.propValue.
                                replaceAll("= ", " ").replaceAll("=\t", "\t").
                                getBytes() );
                    curPropNode.propValue =
                        new String(curPropNode.propValue_byts);
                }catch(Exception e){
                    System.out.println("=Decode quoted-printable exception.");
                    e.printStackTrace();
                }
            }
        }
        curVNode.propList.add(curPropNode);
    }

    private String listToString(Collection<String> list){
        StringBuilder typeListB = new StringBuilder();
        for (String type : list) {
            typeListB.append(type).append(";");
        }
        int len = typeListB.length();
        if (len > 0 && typeListB.charAt(len - 1) == ';') {
            return typeListB.substring(0, len - 1);
        }
        return typeListB.toString();
    }

    public String getResult(){
        return null;
    }
}

