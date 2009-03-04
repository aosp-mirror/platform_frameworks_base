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

package android.pim;

import android.util.Log;
import android.util.Config;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/**
 * Parses RFC 2445 iCalendar objects.
 */
public class ICalendar {

    private static final String TAG = "Sync";

    // TODO: keep track of VEVENT, VTODO, VJOURNAL, VFREEBUSY, VTIMEZONE, VALARM
    // components, by type field or by subclass?  subclass would allow us to
    // enforce grammars.

    /**
     * Exception thrown when an iCalendar object has invalid syntax.
     */
    public static class FormatException extends Exception {
        public FormatException() {
            super();
        }

        public FormatException(String msg) {
            super(msg);
        }

        public FormatException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * A component within an iCalendar (VEVENT, VTODO, VJOURNAL, VFEEBUSY,
     * VTIMEZONE, VALARM).
     */
    public static class Component {

        // components
        private static final String BEGIN = "BEGIN";
        private static final String END = "END";
        private static final String NEWLINE = "\n";
        public static final String VCALENDAR = "VCALENDAR";
        public static final String VEVENT = "VEVENT";
        public static final String VTODO = "VTODO";
        public static final String VJOURNAL = "VJOURNAL";
        public static final String VFREEBUSY = "VFREEBUSY";
        public static final String VTIMEZONE = "VTIMEZONE";
        public static final String VALARM = "VALARM";

        private final String mName;
        private final Component mParent; // see if we can get rid of this
        private LinkedList<Component> mChildren = null;
        private final LinkedHashMap<String, ArrayList<Property>> mPropsMap =
                new LinkedHashMap<String, ArrayList<Property>>();

        /**
         * Creates a new component with the provided name.
         * @param name The name of the component.
         */
        public Component(String name, Component parent) {
            mName = name;
            mParent = parent;
        }

        /**
         * Returns the name of the component.
         * @return The name of the component.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the parent of this component.
         * @return The parent of this component.
         */
        public Component getParent() {
            return mParent;
        }

        /**
         * Helper that lazily gets/creates the list of children.
         * @return The list of children.
         */
        protected LinkedList<Component> getOrCreateChildren() {
            if (mChildren == null) {
                mChildren = new LinkedList<Component>();
            }
            return mChildren;
        }

        /**
         * Adds a child component to this component.
         * @param child The child component.
         */
        public void addChild(Component child) {
            getOrCreateChildren().add(child);
        }

        /**
         * Returns a list of the Component children of this component.  May be
         * null, if there are no children.
         *
         * @return A list of the children.
         */
        public List<Component> getComponents() {
            return mChildren;
        }

        /**
         * Adds a Property to this component.
         * @param prop
         */
        public void addProperty(Property prop) {
            String name= prop.getName();
            ArrayList<Property> props = mPropsMap.get(name);
            if (props == null) {
                props = new ArrayList<Property>();
                mPropsMap.put(name, props);
            }
            props.add(prop);
        }

        /**
         * Returns a set of the property names within this component.
         * @return A set of property names within this component.
         */
        public Set<String> getPropertyNames() {
            return mPropsMap.keySet();
        }

        /**
         * Returns a list of properties with the specified name.  Returns null
         * if there are no such properties.
         * @param name The name of the property that should be returned.
         * @return A list of properties with the requested name.
         */
        public List<Property> getProperties(String name) {
            return mPropsMap.get(name);
        }

        /**
         * Returns the first property with the specified name.  Returns null
         * if there is no such property.
         * @param name The name of the property that should be returned.
         * @return The first property with the specified name.
         */
        public Property getFirstProperty(String name) {
            List<Property> props = mPropsMap.get(name);
            if (props == null || props.size() == 0) {
                return null;
            }
            return props.get(0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            sb.append(NEWLINE);
            return sb.toString();
        }

        /**
         * Helper method that appends this component to a StringBuilder.  The
         * caller is responsible for appending a newline at the end of the
         * component.
         */
        public void toString(StringBuilder sb) {
            sb.append(BEGIN);
            sb.append(":");
            sb.append(mName);
            sb.append(NEWLINE);

            // append the properties
            for (String propertyName : getPropertyNames()) {
                for (Property property : getProperties(propertyName)) {
                    property.toString(sb);
                    sb.append(NEWLINE);
                }
            }

            // append the sub-components
            if (mChildren != null) {
                for (Component component : mChildren) {
                    component.toString(sb);
                    sb.append(NEWLINE);
                }
            }

            sb.append(END);
            sb.append(":");
            sb.append(mName);
        }
    }

    /**
     * A property within an iCalendar component (e.g., DTSTART, DTEND, etc.,
     * within a VEVENT).
     */
    public static class Property {
        // properties
        // TODO: do we want to list these here?  the complete list is long.
        public static final String DTSTART = "DTSTART";
        public static final String DTEND = "DTEND";
        public static final String DURATION = "DURATION";
        public static final String RRULE = "RRULE";
        public static final String RDATE = "RDATE";
        public static final String EXRULE = "EXRULE";
        public static final String EXDATE = "EXDATE";
        // ... need to add more.
        
        private final String mName;
        private LinkedHashMap<String, ArrayList<Parameter>> mParamsMap =
                new LinkedHashMap<String, ArrayList<Parameter>>();
        private String mValue; // TODO: make this final?

        /**
         * Creates a new property with the provided name.
         * @param name The name of the property.
         */
        public Property(String name) {
            mName = name;
        }

        /**
         * Creates a new property with the provided name and value.
         * @param name The name of the property.
         * @param value The value of the property.
         */
        public Property(String name, String value) {
            mName = name;
            mValue = value;
        }

        /**
         * Returns the name of the property.
         * @return The name of the property.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the value of this property.
         * @return The value of this property.
         */
        public String getValue() {
            return mValue;
        }

        /**
         * Sets the value of this property.
         * @param value The desired value for this property.
         */
        public void setValue(String value) {
            mValue = value;
        }        

        /**
         * Adds a {@link Parameter} to this property.
         * @param param The parameter that should be added.
         */
        public void addParameter(Parameter param) {
            ArrayList<Parameter> params = mParamsMap.get(param.name);
            if (params == null) {
                params = new ArrayList<Parameter>();
                mParamsMap.put(param.name, params);
            }
            params.add(param);
        }

        /**
         * Returns the set of parameter names for this property.
         * @return The set of parameter names for this property.
         */
        public Set<String> getParameterNames() {
            return mParamsMap.keySet();
        }

        /**
         * Returns the list of parameters with the specified name.  May return
         * null if there are no such parameters.
         * @param name The name of the parameters that should be returned.
         * @return The list of parameters with the specified name.
         */
        public List<Parameter> getParameters(String name) {
            return mParamsMap.get(name);
        }

        /**
         * Returns the first parameter with the specified name.  May return
         * nll if there is no such parameter.
         * @param name The name of the parameter that should be returned.
         * @return The first parameter with the specified name.
         */
        public Parameter getFirstParameter(String name) {
            ArrayList<Parameter> params = mParamsMap.get(name);
            if (params == null || params.size() == 0) {
                return null;
            }
            return params.get(0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }

        /**
         * Helper method that appends this property to a StringBuilder.  The
         * caller is responsible for appending a newline after this property.
         */
        public void toString(StringBuilder sb) {
            sb.append(mName);
            Set<String> parameterNames = getParameterNames();
            for (String parameterName : parameterNames) {
                for (Parameter param : getParameters(parameterName)) {
                    sb.append(";");
                    param.toString(sb);
                }
            }
            sb.append(":");
            sb.append(mValue);
        }
    }

    /**
     * A parameter defined for an iCalendar property.
     */
    // TODO: make this a proper class rather than a struct?
    public static class Parameter {
        public String name;
        public String value;

        /**
         * Creates a new empty parameter.
         */
        public Parameter() {
        }

        /**
         * Creates a new parameter with the specified name and value.
         * @param name The name of the parameter.
         * @param value The value of the parameter.
         */
        public Parameter(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }

        /**
         * Helper method that appends this parameter to a StringBuilder.
         */
        public void toString(StringBuilder sb) {
            sb.append(name);
            sb.append("=");
            sb.append(value);
        }
    }

    private static final class ParserState {
        // public int lineNumber = 0;
        public String line; // TODO: just point to original text
        public int index;
    }

    // use factory method
    private ICalendar() {
    }

    // TODO: get rid of this -- handle all of the parsing in one pass through
    // the text.
    private static String normalizeText(String text) {
        // it's supposed to be \r\n, but not everyone does that
        text = text.replaceAll("\r\n", "\n");
        text = text.replaceAll("\r", "\n");

        // we deal with line folding, by replacing all "\n " strings
        // with nothing.  The RFC specifies "\r\n " to be folded, but
        // we handle "\n " and "\r " too because we can get those.
        text = text.replaceAll("\n ", "");

        return text;
    }

    /**
     * Parses text into an iCalendar component.  Parses into the provided
     * component, if not null, or parses into a new component.  In the latter
     * case, expects a BEGIN as the first line.  Returns the provided or newly
     * created top-level component.
     */
    // TODO: use an index into the text, so we can make this a recursive
    // function?
    private static Component parseComponentImpl(Component component,
                                                String text)
            throws FormatException {
        Component current = component;
        ParserState state = new ParserState();
        state.index = 0;

        // split into lines
        String[] lines = text.split("\n");

        // each line is of the format:
        // name *(";" param) ":" value
        for (String line : lines) {
            try {
                current = parseLine(line, state, current);
                // if the provided component was null, we will return the root
                // NOTE: in this case, if the first line is not a BEGIN, a
                // FormatException will get thrown.   
                if (component == null) {
                    component = current;
                }
            } catch (FormatException fe) {
                if (Config.LOGV) {
                    Log.v(TAG, "Cannot parse " + line, fe);
                }
                // for now, we ignore the parse error.  Google Calendar seems
                // to be emitting some misformatted iCalendar objects.
            }
            continue;
        }
        return component;
    }

    /**
     * Parses a line into the provided component.  Creates a new component if
     * the line is a BEGIN, adding the newly created component to the provided
     * parent.  Returns whatever component is the current one (to which new
     * properties will be added) in the parse.
     */
    private static Component parseLine(String line, ParserState state,
                                       Component component)
            throws FormatException {
        state.line = line;
        int len = state.line.length();

        // grab the name
        char c = 0;
        for (state.index = 0; state.index < len; ++state.index) {
            c = line.charAt(state.index);
            if (c == ';' || c == ':') {
                break;
            }
        }
        String name = line.substring(0, state.index);

        if (component == null) {
            if (!Component.BEGIN.equals(name)) {
                throw new FormatException("Expected BEGIN");
            }
        }

        Property property;
        if (Component.BEGIN.equals(name)) {
            // start a new component
            String componentName = extractValue(state);
            Component child = new Component(componentName, component);
            if (component != null) {
                component.addChild(child);
            }
            return child;
        } else if (Component.END.equals(name)) {
            // finish the current component
            String componentName = extractValue(state);
            if (component == null ||
                    !componentName.equals(component.getName())) {
                throw new FormatException("Unexpected END " + componentName);
            }
            return component.getParent();
        } else {
            property = new Property(name);
        }

        if (c == ';') {
            Parameter parameter = null;
            while ((parameter = extractParameter(state)) != null) {
                property.addParameter(parameter);
            }
        }
        String value = extractValue(state);
        property.setValue(value);
        component.addProperty(property);
        return component;
    }

    /**
     * Extracts the value ":..." on the current line.  The first character must
     * be a ':'.
     */
    private static String extractValue(ParserState state)
            throws FormatException {
        String line = state.line;
        if (state.index >= line.length() || line.charAt(state.index) != ':') {
            throw new FormatException("Expected ':' before end of line in "
                    + line);
        }
        String value = line.substring(state.index + 1);
        state.index = line.length() - 1;
        return value;
    }

    /**
     * Extracts the next parameter from the line, if any.  If there are no more
     * parameters, returns null.
     */
    private static Parameter extractParameter(ParserState state)
            throws FormatException {
        String text = state.line;
        int len = text.length();
        Parameter parameter = null;
        int startIndex = -1;
        int equalIndex = -1;
        while (state.index < len) {
            char c = text.charAt(state.index);
            if (c == ':') {
                if (parameter != null) {
                    if (equalIndex == -1) {
                        throw new FormatException("Expected '=' within "
                                + "parameter in " + text);
                    }
                    parameter.value = text.substring(equalIndex + 1,
                                                     state.index);
                }
                return parameter; // may be null
            } else if (c == ';') {
                if (parameter != null) {
                    if (equalIndex == -1) {
                        throw new FormatException("Expected '=' within "
                                + "parameter in " + text);
                    }
                    parameter.value = text.substring(equalIndex + 1,
                                                     state.index);
                    return parameter;
                } else {
                    parameter = new Parameter();
                    startIndex = state.index;
                }
            } else if (c == '=') {
                equalIndex = state.index;
                if ((parameter == null) || (startIndex == -1)) {
                    throw new FormatException("Expected ';' before '=' in "
                            + text);
                }
                parameter.name = text.substring(startIndex + 1, equalIndex);
            }
            ++state.index;
        }
        throw new FormatException("Expected ':' before end of line in " + text);
    }

    /**
     * Parses the provided text into an iCalendar object.  The top-level
     * component must be of type VCALENDAR.
     * @param text The text to be parsed.
     * @return The top-level VCALENDAR component.
     * @throws FormatException Thrown if the text could not be parsed into an
     * iCalendar VCALENDAR object.
     */
    public static Component parseCalendar(String text) throws FormatException {
        Component calendar = parseComponent(null, text);
        if (calendar == null || !Component.VCALENDAR.equals(calendar.getName())) {
            throw new FormatException("Expected " + Component.VCALENDAR);
        }
        return calendar;
    }

    /**
     * Parses the provided text into an iCalendar event.  The top-level
     * component must be of type VEVENT.
     * @param text The text to be parsed.
     * @return The top-level VEVENT component.
     * @throws FormatException Thrown if the text could not be parsed into an
     * iCalendar VEVENT.
     */
    public static Component parseEvent(String text) throws FormatException {
        Component event = parseComponent(null, text);
        if (event == null || !Component.VEVENT.equals(event.getName())) {
            throw new FormatException("Expected " + Component.VEVENT);
        }
        return event;
    }

    /**
     * Parses the provided text into an iCalendar component.
     * @param text The text to be parsed.
     * @return The top-level component.
     * @throws FormatException Thrown if the text could not be parsed into an
     * iCalendar component.
     */
    public static Component parseComponent(String text) throws FormatException {
        return parseComponent(null, text);
    }

    /**
     * Parses the provided text, adding to the provided component.
     * @param component The component to which the parsed iCalendar data should
     * be added.
     * @param text The text to be parsed.
     * @return The top-level component.
     * @throws FormatException Thrown if the text could not be parsed as an
     * iCalendar object.
     */
    public static Component parseComponent(Component component, String text)
        throws FormatException {
        text = normalizeText(text);
        return parseComponentImpl(component, text);
    }
}
