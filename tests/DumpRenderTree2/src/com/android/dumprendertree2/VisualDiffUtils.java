/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import name.fraser.neil.plaintext.diff_match_patch;

import java.util.LinkedList;

/**
 * Helper methods fo TextResult.getDiffAsHtml()
 */
public class VisualDiffUtils {

    private static final int DONT_PRINT_LINE_NUMBER = -1;

    /**
     * Preprocesses the list of diffs so that new line characters appear only at the end of
     * diff.text
     *
     * @param diffs
     * @return
     *      LinkedList of diffs where new line character appears only on the end of
     *      diff.text
     */
    public static LinkedList<diff_match_patch.Diff> splitDiffsOnNewline(
            LinkedList<diff_match_patch.Diff> diffs) {
        LinkedList<diff_match_patch.Diff> newDiffs = new LinkedList<diff_match_patch.Diff>();

        String[] parts;
        int lengthMinusOne;
        for (diff_match_patch.Diff diff : diffs) {
            parts = diff.text.split("\n", -1);
            if (parts.length == 1) {
                newDiffs.add(diff);
                continue;
            }

            lengthMinusOne = parts.length - 1;
            for (int i = 0; i < lengthMinusOne; i++) {
                newDiffs.add(new diff_match_patch.Diff(diff.operation, parts[i] + "\n"));
            }
            if (!parts[lengthMinusOne].isEmpty()) {
                newDiffs.add(new diff_match_patch.Diff(diff.operation, parts[lengthMinusOne]));
            }
        }

        return newDiffs;
    }

    public static void generateExpectedResultLines(LinkedList<diff_match_patch.Diff> diffs,
            LinkedList<Integer> lineNums, LinkedList<String> lines) {
        String delSpan = "<span class=\"del\">";
        String eqlSpan = "<span class=\"eql\">";

        String line = "";
        int i = 1;
        diff_match_patch.Diff diff;
        int size = diffs.size();
        boolean isLastDiff;
        for (int j = 0; j < size; j++) {
            diff = diffs.get(j);
            isLastDiff = j == size - 1;
            switch (diff.operation) {
                case DELETE:
                    line = processDiff(diff, lineNums, lines, line, i, delSpan, isLastDiff);
                    if (line.equals("")) {
                        i++;
                    }
                    break;

                case INSERT:
                    // If the line is currently empty and this insertion is the entire line, the
                    // expected line is absent, so it has no line number.
                    if (diff.text.endsWith("\n") || isLastDiff) {
                        lineNums.add(line.equals("") ? DONT_PRINT_LINE_NUMBER : i++);
                        lines.add(line);
                        line = "";
                    }
                    break;

                case EQUAL:
                    line = processDiff(diff, lineNums, lines, line, i, eqlSpan, isLastDiff);
                    if (line.equals("")) {
                        i++;
                    }
                    break;
            }
        }
    }

    public static void generateActualResultLines(LinkedList<diff_match_patch.Diff> diffs,
            LinkedList<Integer> lineNums, LinkedList<String> lines) {
        String insSpan = "<span class=\"ins\">";
        String eqlSpan = "<span class=\"eql\">";

        String line = "";
        int i = 1;
        diff_match_patch.Diff diff;
        int size = diffs.size();
        boolean isLastDiff;
        for (int j = 0; j < size; j++) {
            diff = diffs.get(j);
            isLastDiff = j == size - 1;
            switch (diff.operation) {
                case INSERT:
                    line = processDiff(diff, lineNums, lines, line, i, insSpan, isLastDiff);
                    if (line.equals("")) {
                        i++;
                    }
                    break;

                case DELETE:
                    // If the line is currently empty and deletion is the entire line, the
                    // actual line is absent, so it has no line number.
                    if (diff.text.endsWith("\n") || isLastDiff) {
                        lineNums.add(line.equals("") ? DONT_PRINT_LINE_NUMBER : i++);
                        lines.add(line);
                        line = "";
                    }
                    break;

                case EQUAL:
                    line = processDiff(diff, lineNums, lines, line, i, eqlSpan, isLastDiff);
                    if (line.equals("")) {
                        i++;
                    }
                    break;
            }
        }
    }

    /**
     * Generate or append a line for a given diff and add it to given collections if necessary.
     * It puts diffs in HTML spans.
     *
     * @param diff
     * @param lineNums
     * @param lines
     * @param line
     * @param i
     * @param begSpan
     * @param forceOutputLine Force the current line to be output
     * @return
     */
    public static String processDiff(diff_match_patch.Diff diff, LinkedList<Integer> lineNums,
            LinkedList<String> lines, String line, int i, String begSpan, boolean forceOutputLine) {
        String endSpan = "</span>";
        String br = "&nbsp;";

        if (diff.text.endsWith("\n") || forceOutputLine) {
            lineNums.add(i);
            /** TODO: Think of better way to replace stuff */
            line += begSpan + diff.text.replace("  ", "&nbsp;&nbsp;")
                    + endSpan + br;
            lines.add(line);
            line = "";
        } else {
            line += begSpan + diff.text.replace("  ", "&nbsp;&nbsp;") + endSpan;
        }

        return line;
    }

    public static String getHtml(LinkedList<Integer> lineNums1, LinkedList<String> lines1,
            LinkedList<Integer> lineNums2, LinkedList<String> lines2) {
        StringBuilder html = new StringBuilder();
        int lineNum;
        int size = lines1.size();
        for (int i = 0; i < size; i++) {
            html.append("<tr class=\"results\">");

            html.append("    <td class=\"line_count\">");
            lineNum = lineNums1.removeFirst();
            if (lineNum > 0) {
                html.append(lineNum);
            }
            html.append("    </td>");

            html.append("    <td class=\"line\">");
            html.append(lines1.removeFirst());
            html.append("    </td>");

            html.append("    <td class=\"space\"></td>");

            html.append("    <td class=\"line_count\">");
            lineNum = lineNums2.removeFirst();
            if (lineNum > 0) {
                html.append(lineNum);
            }
            html.append("    </td>");

            html.append("    <td class=\"line\">");
            html.append(lines2.removeFirst());
            html.append("    </td>");

            html.append("</tr>");
        }
        return html.toString();
    }
}
