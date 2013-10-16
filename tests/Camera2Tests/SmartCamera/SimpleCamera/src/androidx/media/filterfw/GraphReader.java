/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterfw;

import android.text.TextUtils;

import java.io.InputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A GraphReader allows obtaining filter graphs from XML graph files or strings.
 */
public class GraphReader {

    private static interface Command {
        public void execute(CommandStack stack);
    }

    private static class CommandStack {
        private ArrayList<Command> mCommands = new ArrayList<Command>();
        private FilterGraph.Builder mBuilder;
        private FilterFactory mFactory;
        private MffContext mContext;

        public CommandStack(MffContext context) {
            mContext = context;
            mBuilder = new FilterGraph.Builder(mContext);
            mFactory = new FilterFactory();
        }

        public void execute() {
            for (Command command : mCommands) {
                command.execute(this);
            }
        }

        public void append(Command command) {
            mCommands.add(command);
        }

        public FilterFactory getFactory() {
            return mFactory;
        }

        public MffContext getContext() {
            return mContext;
        }

        protected FilterGraph.Builder getBuilder() {
            return mBuilder;
        }
    }

    private static class ImportPackageCommand implements Command {
        private String mPackageName;

        public ImportPackageCommand(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public void execute(CommandStack stack) {
            try {
                stack.getFactory().addPackage(mPackageName);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    private static class AddLibraryCommand implements Command {
        private String mLibraryName;

        public AddLibraryCommand(String libraryName) {
            mLibraryName = libraryName;
        }

        @Override
        public void execute(CommandStack stack) {
            FilterFactory.addFilterLibrary(mLibraryName);
        }
    }

    private static class AllocateFilterCommand implements Command {
        private String mClassName;
        private String mFilterName;

        public AllocateFilterCommand(String className, String filterName) {
            mClassName = className;
            mFilterName = filterName;
        }

        @Override
	public void execute(CommandStack stack) {
            Filter filter = null;
            try {
                filter = stack.getFactory().createFilterByClassName(mClassName,
                                                                    mFilterName,
                                                                    stack.getContext());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Error creating filter " + mFilterName + "!", e);
            }
            stack.getBuilder().addFilter(filter);
        }
    }

    private static class AddSourceSlotCommand implements Command {
        private String mName;
        private String mSlotName;

        public AddSourceSlotCommand(String name, String slotName) {
            mName = name;
            mSlotName = slotName;
        }

        @Override
        public void execute(CommandStack stack) {
            stack.getBuilder().addFrameSlotSource(mName, mSlotName);
        }
    }

    private static class AddTargetSlotCommand implements Command {
        private String mName;
        private String mSlotName;

        public AddTargetSlotCommand(String name, String slotName) {
            mName = name;
            mSlotName = slotName;
        }

        @Override
        public void execute(CommandStack stack) {
            stack.getBuilder().addFrameSlotTarget(mName, mSlotName);
        }
    }

    private static class AddVariableCommand implements Command {
        private String mName;
        private Object mValue;

        public AddVariableCommand(String name, Object value) {
            mName = name;
            mValue = value;
        }

        @Override
        public void execute(CommandStack stack) {
            stack.getBuilder().addVariable(mName, mValue);
        }
    }

    private static class SetFilterInputCommand implements Command {
        private String mFilterName;
        private String mFilterInput;
        private Object mValue;

        public SetFilterInputCommand(String filterName, String input, Object value) {
            mFilterName = filterName;
            mFilterInput = input;
            mValue = value;
        }

        @Override
        public void execute(CommandStack stack) {
            if (mValue instanceof Variable) {
                String varName = ((Variable)mValue).name;
                stack.getBuilder().assignVariableToFilterInput(varName, mFilterName, mFilterInput);
            } else {
                stack.getBuilder().assignValueToFilterInput(mValue, mFilterName, mFilterInput);
            }
        }
    }

    private static class ConnectCommand implements Command {
        private String mSourceFilter;
        private String mSourcePort;
        private String mTargetFilter;
        private String mTargetPort;

        public ConnectCommand(String sourceFilter,
                              String sourcePort,
                              String targetFilter,
                              String targetPort) {
            mSourceFilter = sourceFilter;
            mSourcePort = sourcePort;
            mTargetFilter = targetFilter;
            mTargetPort = targetPort;
        }

        @Override
        public void execute(CommandStack stack) {
            stack.getBuilder().connect(mSourceFilter, mSourcePort, mTargetFilter, mTargetPort);
        }
    }

    private static class Variable {
        public String name;

        public Variable(String name) {
            this.name = name;
        }
    }

    private static class XmlGraphReader {

        private SAXParserFactory mParserFactory;

        private static class GraphDataHandler extends DefaultHandler {

            private CommandStack mCommandStack;
            private boolean mInGraph = false;
            private String mCurFilterName = null;

            public GraphDataHandler(CommandStack commandStack) {
                mCommandStack = commandStack;
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attr)
                    throws SAXException {
                if (localName.equals("graph")) {
                    beginGraph();
                } else {
                    assertInGraph(localName);
                    if (localName.equals("import")) {
                        addImportCommand(attr);
                    } else if (localName.equals("library")) {
                        addLibraryCommand(attr);
                    } else if (localName.equals("connect")) {
                        addConnectCommand(attr);
                    } else if (localName.equals("var")) {
                        addVarCommand(attr);
                    } else if (localName.equals("filter")) {
                        beginFilter(attr);
                    } else if (localName.equals("input")) {
                        addFilterInput(attr);
                    } else {
                        throw new SAXException("Unknown XML element '" + localName + "'!");
                    }
                }
            }

            @Override
            public void endElement (String uri, String localName, String qName) {
                if (localName.equals("graph")) {
                    endGraph();
                } else if (localName.equals("filter")) {
                    endFilter();
                }
            }

            private void addImportCommand(Attributes attributes) throws SAXException {
                String packageName = getRequiredAttribute(attributes, "package");
                mCommandStack.append(new ImportPackageCommand(packageName));
            }

            private void addLibraryCommand(Attributes attributes) throws SAXException {
                String libraryName = getRequiredAttribute(attributes, "name");
                mCommandStack.append(new AddLibraryCommand(libraryName));
            }

            private void addConnectCommand(Attributes attributes) {
                String sourcePortName   = null;
                String sourceFilterName = null;
                String targetPortName   = null;
                String targetFilterName = null;

                // check for shorthand: <connect source="filter:port" target="filter:port"/>
                String sourceTag = attributes.getValue("source");
                if (sourceTag != null) {
                    String[] sourceParts = sourceTag.split(":");
                    if (sourceParts.length == 2) {
                        sourceFilterName = sourceParts[0];
                        sourcePortName   = sourceParts[1];
                    } else {
                        throw new RuntimeException(
                            "'source' tag needs to have format \"filter:port\"! " +
                            "Alternatively, you may use the form " +
                            "'sourceFilter=\"filter\" sourcePort=\"port\"'.");
                    }
                } else {
                    sourceFilterName = attributes.getValue("sourceFilter");
                    sourcePortName   = attributes.getValue("sourcePort");
                }

                String targetTag = attributes.getValue("target");
                if (targetTag != null) {
                    String[] targetParts = targetTag.split(":");
                    if (targetParts.length == 2) {
                        targetFilterName = targetParts[0];
                        targetPortName   = targetParts[1];
                    } else {
                        throw new RuntimeException(
                            "'target' tag needs to have format \"filter:port\"! " +
                            "Alternatively, you may use the form " +
                            "'targetFilter=\"filter\" targetPort=\"port\"'.");
                    }
                } else {
                    targetFilterName = attributes.getValue("targetFilter");
                    targetPortName   = attributes.getValue("targetPort");
                }

                String sourceSlotName = attributes.getValue("sourceSlot");
                String targetSlotName = attributes.getValue("targetSlot");
                if (sourceSlotName != null) {
                    sourceFilterName = "sourceSlot_" + sourceSlotName;
                    mCommandStack.append(new AddSourceSlotCommand(sourceFilterName,
                                                                  sourceSlotName));
                    sourcePortName = "frame";
                }
                if (targetSlotName != null) {
                    targetFilterName = "targetSlot_" + targetSlotName;
                    mCommandStack.append(new AddTargetSlotCommand(targetFilterName,
                                                                  targetSlotName));
                    targetPortName = "frame";
                }
                assertValueNotNull("sourceFilter", sourceFilterName);
                assertValueNotNull("sourcePort", sourcePortName);
                assertValueNotNull("targetFilter", targetFilterName);
                assertValueNotNull("targetPort", targetPortName);
                // TODO: Should slot connections auto-branch?
                mCommandStack.append(new ConnectCommand(sourceFilterName,
                                                        sourcePortName,
                                                        targetFilterName,
                                                        targetPortName));
            }

            private void addVarCommand(Attributes attributes) throws SAXException {
                String varName = getRequiredAttribute(attributes, "name");
                Object varValue = getAssignmentValue(attributes);
                mCommandStack.append(new AddVariableCommand(varName, varValue));
            }

            private void beginGraph() throws SAXException {
                if (mInGraph) {
                    throw new SAXException("Found more than one graph element in XML!");
                }
                mInGraph = true;
            }

            private void endGraph() {
                mInGraph = false;
            }

            private void beginFilter(Attributes attributes) throws SAXException {
                String className = getRequiredAttribute(attributes, "class");
                mCurFilterName = getRequiredAttribute(attributes, "name");
                mCommandStack.append(new AllocateFilterCommand(className, mCurFilterName));
            }

            private void endFilter() {
                mCurFilterName = null;
            }

            private void addFilterInput(Attributes attributes) throws SAXException {
                // Make sure we are in a filter element
                if (mCurFilterName == null) {
                    throw new SAXException("Found 'input' element outside of 'filter' "
                        + "element!");
                }

                // Get input name and value
                String inputName = getRequiredAttribute(attributes, "name");
                Object inputValue = getAssignmentValue(attributes);
                if (inputValue == null) {
                    throw new SAXException("No value specified for input '" + inputName + "' "
                        + "of filter '" + mCurFilterName + "'!");
                }

                // Push commmand
                mCommandStack.append(new SetFilterInputCommand(mCurFilterName,
                                                               inputName,
                                                               inputValue));
            }

            private void assertInGraph(String localName) throws SAXException {
                if (!mInGraph) {
                    throw new SAXException("Encountered '" + localName + "' element outside of "
                        + "'graph' element!");
                }
            }

            private static Object getAssignmentValue(Attributes attributes) {
                String strValue = null;
                if ((strValue = attributes.getValue("stringValue")) != null) {
                    return strValue;
                } else if ((strValue = attributes.getValue("booleanValue")) != null) {
                    return Boolean.parseBoolean(strValue);
                } else if ((strValue = attributes.getValue("intValue")) != null) {
                    return Integer.parseInt(strValue);
                } else if ((strValue = attributes.getValue("floatValue")) != null) {
                    return Float.parseFloat(strValue);
                } else if ((strValue = attributes.getValue("floatsValue")) != null) {
                    String[] floatStrings = TextUtils.split(strValue, ",");
                    float[] result = new float[floatStrings.length];
                    for (int i = 0; i < floatStrings.length; ++i) {
                        result[i] = Float.parseFloat(floatStrings[i]);
                    }
                    return result;
                } else if ((strValue = attributes.getValue("varValue")) != null) {
                    return new Variable(strValue);
                } else {
                    return null;
                }
            }

            private static String getRequiredAttribute(Attributes attributes, String name)
                    throws SAXException {
                String result = attributes.getValue(name);
                if (result == null) {
                    throw new SAXException("Required attribute '" + name + "' not found!");
                }
                return result;
            }

            private static void assertValueNotNull(String valueName, Object value) {
                if (value == null) {
                    throw new NullPointerException("Required value '" + value + "' not specified!");
                }
            }

        }

        public XmlGraphReader() {
            mParserFactory = SAXParserFactory.newInstance();
        }

        public void parseString(String graphString, CommandStack commandStack) throws IOException {
            try {
                XMLReader reader = getReaderForCommandStack(commandStack);
                reader.parse(new InputSource(new StringReader(graphString)));
            } catch (SAXException e) {
                throw new IOException("XML parse error during graph parsing!", e);
            }
        }

        public void parseInput(InputStream inputStream, CommandStack commandStack)
                throws IOException {
            try {
                XMLReader reader = getReaderForCommandStack(commandStack);
                reader.parse(new InputSource(inputStream));
            } catch (SAXException e) {
                throw new IOException("XML parse error during graph parsing!", e);
            }
        }

        private XMLReader getReaderForCommandStack(CommandStack commandStack) throws IOException {
            try {
                SAXParser parser = mParserFactory.newSAXParser();
                XMLReader reader = parser.getXMLReader();
                GraphDataHandler graphHandler = new GraphDataHandler(commandStack);
                reader.setContentHandler(graphHandler);
                return reader;
            } catch (ParserConfigurationException e) {
                throw new IOException("Error creating SAXParser for graph parsing!", e);
            } catch (SAXException e) {
                throw new IOException("Error creating XMLReader for graph parsing!", e);
            }
        }
    }

    /**
     * Read an XML graph from a String.
     *
     * This function automatically checks each filters' signatures and throws a Runtime Exception
     * if required ports are unconnected. Use the 3-parameter version to avoid this behavior.
     *
     * @param context the MffContext into which to load the graph.
     * @param xmlSource the graph specified in XML.
     * @return the FilterGraph instance for the XML source.
     * @throws IOException if there was an error parsing the source.
     */
    public static FilterGraph readXmlGraph(MffContext context, String xmlSource)
            throws IOException {
        FilterGraph.Builder builder = getBuilderForXmlString(context, xmlSource);
        return builder.build();
    }

    /**
     * Read an XML sub-graph from a String.
     *
     * @param context the MffContext into which to load the graph.
     * @param xmlSource the graph specified in XML.
     * @param parentGraph the parent graph.
     * @return the FilterGraph instance for the XML source.
     * @throws IOException if there was an error parsing the source.
     */
    public static FilterGraph readXmlSubGraph(
            MffContext context, String xmlSource, FilterGraph parentGraph)
            throws IOException {
        FilterGraph.Builder builder = getBuilderForXmlString(context, xmlSource);
        return builder.buildSubGraph(parentGraph);
    }

    /**
     * Read an XML graph from a resource.
     *
     * This function automatically checks each filters' signatures and throws a Runtime Exception
     * if required ports are unconnected. Use the 3-parameter version to avoid this behavior.
     *
     * @param context the MffContext into which to load the graph.
     * @param resourceId the XML resource ID.
     * @return the FilterGraph instance for the XML source.
     * @throws IOException if there was an error reading or parsing the resource.
     */
    public static FilterGraph readXmlGraphResource(MffContext context, int resourceId)
            throws IOException {
        FilterGraph.Builder builder = getBuilderForXmlResource(context, resourceId);
        return builder.build();
    }

    /**
     * Read an XML graph from a resource.
     *
     * This function automatically checks each filters' signatures and throws a Runtime Exception
     * if required ports are unconnected. Use the 3-parameter version to avoid this behavior.
     *
     * @param context the MffContext into which to load the graph.
     * @param resourceId the XML resource ID.
     * @return the FilterGraph instance for the XML source.
     * @throws IOException if there was an error reading or parsing the resource.
     */
    public static FilterGraph readXmlSubGraphResource(
            MffContext context, int resourceId, FilterGraph parentGraph)
            throws IOException {
        FilterGraph.Builder builder = getBuilderForXmlResource(context, resourceId);
        return builder.buildSubGraph(parentGraph);
    }

    private static FilterGraph.Builder getBuilderForXmlString(MffContext context, String source)
            throws IOException {
        XmlGraphReader reader = new XmlGraphReader();
        CommandStack commands = new CommandStack(context);
        reader.parseString(source, commands);
        commands.execute();
        return commands.getBuilder();
    }

    private static FilterGraph.Builder getBuilderForXmlResource(MffContext context, int resourceId)
            throws IOException {
        InputStream inputStream = context.getApplicationContext().getResources()
                .openRawResource(resourceId);
        XmlGraphReader reader = new XmlGraphReader();
        CommandStack commands = new CommandStack(context);
        reader.parseInput(inputStream, commands);
        commands.execute();
        return commands.getBuilder();
    }
}

