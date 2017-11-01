#!/usr/bin/env python

"""
Looks for strings with multiple substitution arguments (%d, &s, etc)
and replaces them with positional arguments (%1$d, %2$s).
"""

import os.path
import re
import xml.parsers.expat

class PositionalArgumentFixer:
    def matches(self, file_path):
        dirname, basename = os.path.split(file_path)
        dirname = os.path.split(dirname)[1]
        return dirname.startswith("values") and basename.endswith(".xml")

    def consume(self, xml_path, input):
        parser = xml.parsers.expat.ParserCreate("utf-8")
        locator = SubstitutionArgumentLocator(parser)
        parser.returns_unicode = True
        parser.StartElementHandler = locator.start_element
        parser.EndElementHandler = locator.end_element
        parser.CharacterDataHandler = locator.character_data
        parser.Parse(input)

        if len(locator.arguments) > 0:
            output = ""
            last_index = 0
            for arg in locator.arguments:
                output += input[last_index:arg.start]
                output += "%{0}$".format(arg.number)
                last_index = arg.start + 1
            output += input[last_index:]
            print "fixed {0}".format(xml_path)
            return output
        return input

class Argument:
    def __init__(self, start, number):
        self.start = start
        self.number = number

class SubstitutionArgumentLocator:
    """Callback class for xml.parsers.expat which records locations of
    substitution arguments in strings when there are more than 1 of
    them in a single <string> tag (and they are not positional).
    """
    def __init__(self, parser):
        self.arguments = []
        self._parser = parser
        self._depth = 0
        self._within_string = False
        self._current_arguments = []
        self._next_number = 1

    def start_element(self, tag_name, attrs):
        self._depth += 1
        if self._depth == 2 and tag_name == "string" and "translateable" not in attrs:
            self._within_string = True

    def character_data(self, data):
        if self._within_string:
            for m in re.finditer("%[-#+ 0,(]?\d*[bBhHsScCdoxXeEfgGaAtTn]", data):
                start, end = m.span()
                self._current_arguments.append(\
                        Argument(self._parser.CurrentByteIndex + start, self._next_number))
                self._next_number += 1

    def end_element(self, tag_name):
        if self._within_string and self._depth == 2:
            if len(self._current_arguments) > 1:
                self.arguments += self._current_arguments
            self._current_arguments = []
            self._within_string = False
            self._next_number = 1
        self._depth -= 1
