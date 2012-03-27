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


package android.filterfw.io;

import java.lang.Float;
import java.lang.Integer;
import java.lang.String;

import java.util.ArrayList;
import java.util.regex.Pattern;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterFactory;
import android.filterfw.core.FilterGraph;
import android.filterfw.core.KeyValueMap;
import android.filterfw.core.ProtocolException;
import android.filterfw.io.GraphReader;
import android.filterfw.io.GraphIOException;
import android.filterfw.io.PatternScanner;

/**
 * @hide
 */
public class TextGraphReader extends GraphReader {

    private ArrayList<Command> mCommands = new ArrayList<Command>();
    private Filter mCurrentFilter;
    private FilterGraph mCurrentGraph;
    private KeyValueMap mBoundReferences;
    private KeyValueMap mSettings;
    private FilterFactory mFactory;

    private interface Command {
        public void execute(TextGraphReader reader) throws GraphIOException;
    }

    private class ImportPackageCommand implements Command {
        private String mPackageName;

        public ImportPackageCommand(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public void execute(TextGraphReader reader) throws GraphIOException {
            try {
                reader.mFactory.addPackage(mPackageName);
            } catch (IllegalArgumentException e) {
                throw new GraphIOException(e.getMessage());
            }
        }
    }

    private class AddLibraryCommand implements Command {
        private String mLibraryName;

        public AddLibraryCommand(String libraryName) {
            mLibraryName = libraryName;
        }

        @Override
        public void execute(TextGraphReader reader) {
            reader.mFactory.addFilterLibrary(mLibraryName);
        }
    }

    private class AllocateFilterCommand implements Command {
        private String mClassName;
        private String mFilterName;

        public AllocateFilterCommand(String className, String filterName) {
            mClassName = className;
            mFilterName = filterName;
        }

        public void execute(TextGraphReader reader) throws GraphIOException {
            // Create the filter
            Filter filter = null;
            try {
                filter = reader.mFactory.createFilterByClassName(mClassName, mFilterName);
            } catch (IllegalArgumentException e) {
                throw new GraphIOException(e.getMessage());
            }

            // Set it as the current filter
            reader.mCurrentFilter = filter;
        }
    }

    private class InitFilterCommand implements Command {
        private KeyValueMap mParams;

        public InitFilterCommand(KeyValueMap params) {
            mParams = params;
        }

        @Override
        public void execute(TextGraphReader reader) throws GraphIOException {
            Filter filter = reader.mCurrentFilter;
            try {
                filter.initWithValueMap(mParams);
            } catch (ProtocolException e) {
                throw new GraphIOException(e.getMessage());
            }
            reader.mCurrentGraph.addFilter(mCurrentFilter);
        }
    }

    private class ConnectCommand implements Command {
        private String mSourceFilter;
        private String mSourcePort;
        private String mTargetFilter;
        private String mTargetName;

        public ConnectCommand(String sourceFilter,
                              String sourcePort,
                              String targetFilter,
                              String targetName) {
            mSourceFilter = sourceFilter;
            mSourcePort = sourcePort;
            mTargetFilter = targetFilter;
            mTargetName = targetName;
        }

        @Override
        public void execute(TextGraphReader reader) {
            reader.mCurrentGraph.connect(mSourceFilter, mSourcePort, mTargetFilter, mTargetName);
        }
    }

    @Override
    public FilterGraph readGraphString(String graphString) throws GraphIOException {
        FilterGraph result = new FilterGraph();

        reset();
        mCurrentGraph = result;
        parseString(graphString);
        applySettings();
        executeCommands();
        reset();

        return result;
    }

    private void reset() {
        mCurrentGraph = null;
        mCurrentFilter = null;
        mCommands.clear();
        mBoundReferences = new KeyValueMap();
        mSettings = new KeyValueMap();
        mFactory = new FilterFactory();
    }

    private void parseString(String graphString) throws GraphIOException {
        final Pattern commandPattern = Pattern.compile("@[a-zA-Z]+");
        final Pattern curlyClosePattern = Pattern.compile("\\}");
        final Pattern curlyOpenPattern = Pattern.compile("\\{");
        final Pattern ignorePattern = Pattern.compile("(\\s+|//[^\\n]*\\n)+");
        final Pattern packageNamePattern = Pattern.compile("[a-zA-Z\\.]+");
        final Pattern libraryNamePattern = Pattern.compile("[a-zA-Z\\./:]+");
        final Pattern portPattern = Pattern.compile("\\[[a-zA-Z0-9\\-_]+\\]");
        final Pattern rightArrowPattern = Pattern.compile("=>");
        final Pattern semicolonPattern = Pattern.compile(";");
        final Pattern wordPattern = Pattern.compile("[a-zA-Z0-9\\-_]+");

        final int STATE_COMMAND           = 0;
        final int STATE_IMPORT_PKG        = 1;
        final int STATE_ADD_LIBRARY       = 2;
        final int STATE_FILTER_CLASS      = 3;
        final int STATE_FILTER_NAME       = 4;
        final int STATE_CURLY_OPEN        = 5;
        final int STATE_PARAMETERS        = 6;
        final int STATE_CURLY_CLOSE       = 7;
        final int STATE_SOURCE_FILTERNAME = 8;
        final int STATE_SOURCE_PORT       = 9;
        final int STATE_RIGHT_ARROW       = 10;
        final int STATE_TARGET_FILTERNAME = 11;
        final int STATE_TARGET_PORT       = 12;
        final int STATE_ASSIGNMENT        = 13;
        final int STATE_EXTERNAL          = 14;
        final int STATE_SETTING           = 15;
        final int STATE_SEMICOLON         = 16;

        int state = STATE_COMMAND;
        PatternScanner scanner = new PatternScanner(graphString, ignorePattern);

        String curClassName = null;
        String curSourceFilterName = null;
        String curSourcePortName = null;
        String curTargetFilterName = null;
        String curTargetPortName = null;

        // State machine main loop
        while (!scanner.atEnd()) {
            switch (state) {
                case STATE_COMMAND: {
                    String curCommand = scanner.eat(commandPattern, "<command>");
                    if (curCommand.equals("@import")) {
                        state = STATE_IMPORT_PKG;
                    } else if (curCommand.equals("@library")) {
                        state = STATE_ADD_LIBRARY;
                    } else if (curCommand.equals("@filter")) {
                        state = STATE_FILTER_CLASS;
                    } else if (curCommand.equals("@connect")) {
                        state = STATE_SOURCE_FILTERNAME;
                    } else if (curCommand.equals("@set")) {
                        state = STATE_ASSIGNMENT;
                    } else if (curCommand.equals("@external")) {
                        state = STATE_EXTERNAL;
                    } else if (curCommand.equals("@setting")) {
                        state = STATE_SETTING;
                    } else {
                        throw new GraphIOException("Unknown command '" + curCommand + "'!");
                    }
                    break;
                }

                case STATE_IMPORT_PKG: {
                    String packageName = scanner.eat(packageNamePattern, "<package-name>");
                    mCommands.add(new ImportPackageCommand(packageName));
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_ADD_LIBRARY: {
                    String libraryName = scanner.eat(libraryNamePattern, "<library-name>");
                    mCommands.add(new AddLibraryCommand(libraryName));
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_FILTER_CLASS:
                    curClassName = scanner.eat(wordPattern, "<class-name>");
                    state = STATE_FILTER_NAME;
                    break;

                case STATE_FILTER_NAME: {
                    String curFilterName = scanner.eat(wordPattern, "<filter-name>");
                    mCommands.add(new AllocateFilterCommand(curClassName, curFilterName));
                    state = STATE_CURLY_OPEN;
                    break;
                }

                case STATE_CURLY_OPEN:
                    scanner.eat(curlyOpenPattern, "{");
                    state = STATE_PARAMETERS;
                    break;

                case STATE_PARAMETERS: {
                    KeyValueMap params = readKeyValueAssignments(scanner, curlyClosePattern);
                    mCommands.add(new InitFilterCommand(params));
                    state = STATE_CURLY_CLOSE;
                    break;
                }

                case STATE_CURLY_CLOSE:
                    scanner.eat(curlyClosePattern, "}");
                    state = STATE_COMMAND;
                    break;

                case STATE_SOURCE_FILTERNAME:
                    curSourceFilterName = scanner.eat(wordPattern, "<source-filter-name>");
                    state = STATE_SOURCE_PORT;
                    break;

                case STATE_SOURCE_PORT: {
                    String portString = scanner.eat(portPattern, "[<source-port-name>]");
                    curSourcePortName = portString.substring(1, portString.length() - 1);
                    state = STATE_RIGHT_ARROW;
                    break;
                }

                case STATE_RIGHT_ARROW:
                    scanner.eat(rightArrowPattern, "=>");
                    state = STATE_TARGET_FILTERNAME;
                    break;

                case STATE_TARGET_FILTERNAME:
                    curTargetFilterName = scanner.eat(wordPattern, "<target-filter-name>");
                    state = STATE_TARGET_PORT;
                    break;

                case STATE_TARGET_PORT: {
                    String portString = scanner.eat(portPattern, "[<target-port-name>]");
                    curTargetPortName = portString.substring(1, portString.length() - 1);
                    mCommands.add(new ConnectCommand(curSourceFilterName,
                                                     curSourcePortName,
                                                     curTargetFilterName,
                                                     curTargetPortName));
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_ASSIGNMENT: {
                    KeyValueMap assignment = readKeyValueAssignments(scanner, semicolonPattern);
                    mBoundReferences.putAll(assignment);
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_EXTERNAL: {
                    String externalName = scanner.eat(wordPattern, "<external-identifier>");
                    bindExternal(externalName);
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_SETTING: {
                    KeyValueMap setting = readKeyValueAssignments(scanner, semicolonPattern);
                    mSettings.putAll(setting);
                    state = STATE_SEMICOLON;
                    break;
                }

                case STATE_SEMICOLON:
                    scanner.eat(semicolonPattern, ";");
                    state = STATE_COMMAND;
                    break;
            }
        }

        // Make sure end of input was expected
        if (state != STATE_SEMICOLON && state != STATE_COMMAND) {
            throw new GraphIOException("Unexpected end of input!");
        }
    }

    @Override
    public KeyValueMap readKeyValueAssignments(String assignments) throws GraphIOException {
        final Pattern ignorePattern = Pattern.compile("\\s+");
        PatternScanner scanner = new PatternScanner(assignments, ignorePattern);
        return readKeyValueAssignments(scanner, null);
    }

    private KeyValueMap readKeyValueAssignments(PatternScanner scanner,
                                                Pattern endPattern) throws GraphIOException {
        // Our parser is a state-machine, and these are our states
        final int STATE_IDENTIFIER = 0;
        final int STATE_EQUALS     = 1;
        final int STATE_VALUE      = 2;
        final int STATE_POST_VALUE = 3;

        final Pattern equalsPattern = Pattern.compile("=");
        final Pattern semicolonPattern = Pattern.compile(";");
        final Pattern wordPattern = Pattern.compile("[a-zA-Z]+[a-zA-Z0-9]*");
        final Pattern stringPattern = Pattern.compile("'[^']*'|\\\"[^\\\"]*\\\"");
        final Pattern intPattern = Pattern.compile("[0-9]+");
        final Pattern floatPattern = Pattern.compile("[0-9]*\\.[0-9]+f?");
        final Pattern referencePattern = Pattern.compile("\\$[a-zA-Z]+[a-zA-Z0-9]");
        final Pattern booleanPattern = Pattern.compile("true|false");

        int state = STATE_IDENTIFIER;
        KeyValueMap newVals = new KeyValueMap();
        String curKey = null;
        String curValue = null;

        while (!scanner.atEnd() && !(endPattern != null && scanner.peek(endPattern))) {
            switch (state) {
                case STATE_IDENTIFIER:
                    curKey = scanner.eat(wordPattern, "<identifier>");
                    state = STATE_EQUALS;
                    break;

                case STATE_EQUALS:
                    scanner.eat(equalsPattern, "=");
                    state = STATE_VALUE;
                    break;

                case STATE_VALUE:
                    if ((curValue = scanner.tryEat(stringPattern)) != null) {
                        newVals.put(curKey, curValue.substring(1, curValue.length() - 1));
                    } else if ((curValue = scanner.tryEat(referencePattern)) != null) {
                        String refName = curValue.substring(1, curValue.length());
                        Object referencedObject = mBoundReferences != null
                            ? mBoundReferences.get(refName)
                            : null;
                        if (referencedObject == null) {
                            throw new GraphIOException(
                                "Unknown object reference to '" + refName + "'!");
                        }
                        newVals.put(curKey, referencedObject);
                    } else if ((curValue = scanner.tryEat(booleanPattern)) != null) {
                        newVals.put(curKey, Boolean.parseBoolean(curValue));
                    } else if ((curValue = scanner.tryEat(floatPattern)) != null) {
                        newVals.put(curKey, Float.parseFloat(curValue));
                    } else if ((curValue = scanner.tryEat(intPattern)) != null) {
                        newVals.put(curKey, Integer.parseInt(curValue));
                    } else {
                        throw new GraphIOException(scanner.unexpectedTokenMessage("<value>"));
                    }
                    state = STATE_POST_VALUE;
                    break;

                case STATE_POST_VALUE:
                    scanner.eat(semicolonPattern, ";");
                    state = STATE_IDENTIFIER;
                    break;
            }
        }

        // Make sure end is expected
        if (state != STATE_IDENTIFIER && state != STATE_POST_VALUE) {
            throw new GraphIOException(
                "Unexpected end of assignments on line " + scanner.lineNo() + "!");
        }

        return newVals;
    }

    private void bindExternal(String name) throws GraphIOException {
        if (mReferences.containsKey(name)) {
            Object value = mReferences.get(name);
            mBoundReferences.put(name, value);
        } else {
            throw new GraphIOException("Unknown external variable '" + name + "'! "
                + "You must add a reference to this external in the host program using "
                + "addReference(...)!");
        }
    }

    /**
     * Unused for now: Often you may want to declare references that are NOT in a certain graph,
     * e.g. when reading multiple graphs with the same reader. We could print a warning, but even
     * that may be too much.
     **/
    private void checkReferences() throws GraphIOException {
        for (String reference : mReferences.keySet()) {
            if (!mBoundReferences.containsKey(reference)) {
                throw new GraphIOException(
                    "Host program specifies reference to '" + reference + "', which is not "
                    + "declared @external in graph file!");
            }
        }
    }

    private void applySettings() throws GraphIOException {
        for (String setting : mSettings.keySet()) {
            Object value = mSettings.get(setting);
            if (setting.equals("autoBranch")) {
                expectSettingClass(setting, value, String.class);
                if (value.equals("synced")) {
                    mCurrentGraph.setAutoBranchMode(FilterGraph.AUTOBRANCH_SYNCED);
                } else if (value.equals("unsynced")) {
                    mCurrentGraph.setAutoBranchMode(FilterGraph.AUTOBRANCH_UNSYNCED);
                } else if (value.equals("off")) {
                    mCurrentGraph.setAutoBranchMode(FilterGraph.AUTOBRANCH_OFF);
                } else {
                    throw new GraphIOException("Unknown autobranch setting: " + value + "!");
                }
            } else if (setting.equals("discardUnconnectedOutputs")) {
                expectSettingClass(setting, value, Boolean.class);
                mCurrentGraph.setDiscardUnconnectedOutputs((Boolean)value);
            } else {
                throw new GraphIOException("Unknown @setting '" + setting + "'!");
            }
        }
    }

    private void expectSettingClass(String setting,
                                    Object value,
                                    Class expectedClass) throws GraphIOException {
        if (value.getClass() != expectedClass) {
            throw new GraphIOException("Setting '" + setting + "' must have a value of type "
                + expectedClass.getSimpleName() + ", but found a value of type "
                + value.getClass().getSimpleName() + "!");
        }
    }

    private void executeCommands() throws GraphIOException {
        for (Command command : mCommands) {
            command.execute(this);
        }
    }
}
