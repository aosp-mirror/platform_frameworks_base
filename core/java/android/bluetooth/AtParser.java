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

package android.bluetooth;

import android.bluetooth.AtCommandHandler;
import android.bluetooth.AtCommandResult;

import java.util.*;

/**
 * An AT (Hayes command) Parser based on (a subset of) the ITU-T V.250 standard.
 * <p>
 *
 * Conforment with the subset of V.250 required for implementation of the
 * Bluetooth Headset and Handsfree Profiles, as per Bluetooth SIP
 * specifications. Also implements some V.250 features not required by
 * Bluetooth - such as chained commands.<p>
 *
 * Command handlers are registered with an AtParser object. These handlers are
 * invoked when command lines are processed by AtParser's process() method.<p>
 *
 * The AtParser object accepts a new command line to parse via its process()
 * method. It breaks each command line into one or more commands. Each command
 * is parsed for name, type, and (optional) arguments, and an appropriate
 * external handler method is called through the AtCommandHandler interface.
 *
 * The command types are<ul>
 * <li>Basic Command. For example "ATDT1234567890". Basic command names are a
 * single character (e.g. "D"), and everything following this character is
 * passed to the handler as a string argument (e.g. "T1234567890").
 * <li>Action Command. For example "AT+CIMI". The command name is "CIMI", and
 * there are no arguments for action commands.
 * <li>Read Command. For example "AT+VGM?". The command name is "VGM", and there
 * are no arguments for get commands.
 * <li>Set Command. For example "AT+VGM=14". The command name is "VGM", and
 * there is a single integer argument in this case. In the general case then
 * can be zero or more arguments (comma deliminated) each of integer or string
 * form.
 * <li>Test Command. For example "AT+VGM=?. No arguments.
 * </ul>
 *
 * In V.250 the last four command types are known as Extended Commands, and
 * they are used heavily in Bluetooth.<p>
 *
 * Basic commands cannot be chained in this implementation. For Bluetooth
 * headset/handsfree use this is acceptable, because they only use the basic
 * commands ATA and ATD, which are not allowed to be chained. For general V.250
 * use we would need to improve this class to allow Basic command chaining -
 * however its tricky to get right becuase there is no deliminator for Basic
 * command chaining.<p>
 *
 * Extended commands can be chained. For example:<p>
 * AT+VGM?;+VGM=14;+CIMI<p>
 * This is equivalent to:<p>
 * AT+VGM?
 * AT+VGM=14
 * AT+CIMI
 * Except that only one final result code is return (although several
 * intermediate responses may be returned), and as soon as one command in the
 * chain fails the rest are abandonded.<p>
 *
 * Handlers are registered by there command name via register(Char c, ...) or
 * register(String s, ...). Handlers for Basic command should be registered by
 * the basic command character, and handlers for Extended commands should be
 * registered by String.<p>
 *
 * Refer to:<ul>
 * <li>ITU-T Recommendation V.250
 * <li>ETSI TS 127.007  (AT Comannd set for User Equipment, 3GPP TS 27.007)
 * <li>Bluetooth Headset Profile Spec (K6)
 * <li>Bluetooth Handsfree Profile Spec (HFP 1.5)
 * </ul>
 * @hide
 */
public class AtParser {

    // Extended command type enumeration, only used internally
    private static final int TYPE_ACTION = 0;   // AT+FOO
    private static final int TYPE_READ = 1;     // AT+FOO?
    private static final int TYPE_SET = 2;      // AT+FOO=
    private static final int TYPE_TEST = 3;     // AT+FOO=?

    private HashMap<String, AtCommandHandler> mExtHandlers;
    private HashMap<Character, AtCommandHandler> mBasicHandlers;

    private String mLastInput;  // for "A/" (repeat last command) support

    /**
     * Create a new AtParser.<p>
     * No handlers are registered.
     */
    public AtParser() {
        mBasicHandlers = new HashMap<Character, AtCommandHandler>();
        mExtHandlers = new HashMap<String, AtCommandHandler>();
        mLastInput = "";
    }

    /**
     * Register a Basic command handler.<p>
     * Basic command handlers are later called via their
     * <code>handleBasicCommand(String args)</code> method.
     * @param  command Command name - a single character
     * @param  handler Handler to register
     */
    public void register(Character command, AtCommandHandler handler) {
        mBasicHandlers.put(command, handler);
    }

    /**
     * Register an Extended command handler.<p>
     * Extended command handlers are later called via:<ul>
     * <li><code>handleActionCommand()</code>
     * <li><code>handleGetCommand()</code>
     * <li><code>handleSetCommand()</code>
     * <li><code>handleTestCommand()</code>
     * </ul>
     * Only one method will be called for each command processed.
     * @param  command Command name - can be multiple characters
     * @param  handler Handler to register
     */
    public void register(String command, AtCommandHandler handler) {
        mExtHandlers.put(command, handler);
    }


    /**
     * Strip input of whitespace and force Uppercase - except sections inside
     * quotes. Also fixes unmatched quotes (by appending a quote). Double
     * quotes " are the only quotes allowed by V.250
     */
    static private String clean(String input) {
        StringBuilder out = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                int j = input.indexOf('"', i + 1 );  // search for closing "
                if (j == -1) {  // unmatched ", insert one.
                    out.append(input.substring(i, input.length()));
                    out.append('"');
                    break;
                }
                out.append(input.substring(i, j + 1));
                i = j;
            } else if (c != ' ') {
                out.append(Character.toUpperCase(c));
            }
        }

        return out.toString();
    }

    static private boolean isAtoZ(char c) {
        return (c >= 'A' && c <= 'Z');
    }

    /**
     * Find a character ch, ignoring quoted sections.
     * Return input.length() if not found.
     */
    static private int findChar(char ch, String input, int fromIndex) {
        for (int i = fromIndex; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                i = input.indexOf('"', i + 1);
                if (i == -1) {
                    return input.length();
                }
            } else if (c == ch) {
                return i;
            }
        }
        return input.length();
    }

    /**
     * Break an argument string into individual arguments (comma deliminated).
     * Integer arguments are turned into Integer objects. Otherwise a String
     * object is used.
     */
    static private Object[] generateArgs(String input) {
        int i = 0;
        int j;
        ArrayList<Object> out = new ArrayList<Object>();
        while (i <= input.length()) {
            j = findChar(',', input, i);

            String arg = input.substring(i, j);
            try {
                out.add(new Integer(arg));
            } catch (NumberFormatException e) {
                out.add(arg);
            }

            i = j + 1; // move past comma
        }
        return out.toArray();
    }

    /**
     * Return the index of the end of character after the last characeter in
     * the extended command name. Uses the V.250 spec for allowed command
     * names.
     */
    static private int findEndExtendedName(String input, int index) {
        for (int i = index; i < input.length(); i++) {
            char c = input.charAt(i);

            // V.250 defines the following chars as legal extended command
            // names
            if (isAtoZ(c)) continue;
            if (c >= '0' && c <= '9') continue;
            switch (c) {
            case '!':
            case '%':
            case '-':
            case '.':
            case '/':
            case ':':
            case '_':
                continue;
            default:
                return i;
            }
        }
        return input.length();
    }

    /**
     * Processes an incoming AT command line.<p>
     * This method will invoke zero or one command handler methods for each
     * command in the command line.<p>
     * @param raw_input The AT input, without EOL deliminator (e.g. <CR>).
     * @return          Result object for this command line. This can be
     *                  converted to a String[] response with toStrings().
     */
    public AtCommandResult process(String raw_input) {
        String input = clean(raw_input);

        // Handle "A/" (repeat previous line)
        if (input.regionMatches(0, "A/", 0, 2)) {
            input = new String(mLastInput);
        } else {
            mLastInput = new String(input);
        }

        // Handle empty line - no response necessary
        if (input.equals("")) {
            // Return []
            return new AtCommandResult(AtCommandResult.UNSOLICITED);
        }

        // Anything else deserves an error
        if (!input.regionMatches(0, "AT", 0, 2)) {
            // Return ["ERROR"]
            return new AtCommandResult(AtCommandResult.ERROR);
        }

        // Ok we have a command that starts with AT. Process it
        int index = 2;
        AtCommandResult result =
                new AtCommandResult(AtCommandResult.UNSOLICITED);
        while (index < input.length()) {
            char c = input.charAt(index);

            if (isAtoZ(c)) {
                // Option 1: Basic Command
                // Pass the rest of the line as is to the handler. Do not
                // look for any more commands on this line.
                String args = input.substring(index + 1);
                if (mBasicHandlers.containsKey((Character)c)) {
                    result.addResult(mBasicHandlers.get(
                            (Character)c).handleBasicCommand(args));
                    return result;
                } else {
                    // no handler
                    result.addResult(
                            new AtCommandResult(AtCommandResult.ERROR));
                    return result;
                }
                // control never reaches here
            }

            if (c == '+') {
                // Option 2: Extended Command
                // Search for first non-name character. Shortcircuit if we dont
                // handle this command name.
                int i = findEndExtendedName(input, index + 1);
                String commandName = input.substring(index, i);
                if (!mExtHandlers.containsKey(commandName)) {
                    // no handler
                    result.addResult(
                            new AtCommandResult(AtCommandResult.ERROR));
                    return result;
                }
                AtCommandHandler handler = mExtHandlers.get(commandName);

                // Search for end of this command - this is usually the end of
                // line
                int endIndex = findChar(';', input, index);

                // Determine what type of command this is.
                // Default to TYPE_ACTION if we can't find anything else
                // obvious.
                int type;

                if (i >= endIndex) {
                    type = TYPE_ACTION;
                } else if (input.charAt(i) == '?') {
                    type = TYPE_READ;
                } else if (input.charAt(i) == '=') {
                    if (i + 1 < endIndex) {
                        if (input.charAt(i + 1) == '?') {
                            type = TYPE_TEST;
                        } else {
                            type = TYPE_SET;
                        }
                    } else {
                        type = TYPE_SET;
                    }
                } else {
                    type = TYPE_ACTION;
                }

                // Call this command. Short-circuit as soon as a command fails
                switch (type) {
                case TYPE_ACTION:
                    result.addResult(handler.handleActionCommand());
                    break;
                case TYPE_READ:
                    result.addResult(handler.handleReadCommand());
                    break;
                case TYPE_TEST:
                    result.addResult(handler.handleTestCommand());
                    break;
                case TYPE_SET:
                    Object[] args =
                            generateArgs(input.substring(i + 1, endIndex));
                    result.addResult(handler.handleSetCommand(args));
                    break;
                }
                if (result.getResultCode() != AtCommandResult.OK) {
                    return result;   // short-circuit
                }

                index = endIndex;
            } else {
                // Can't tell if this is a basic or extended command.
                // Push forwards and hope we hit something.
                index++;
            }
        }
        // Finished processing (and all results were ok)
        return result;
    }
}
