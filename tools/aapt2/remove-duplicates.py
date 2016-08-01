#!/usr/bin/env python

import os
import os.path
import sys
import tempfile
import xml.parsers.expat

"""
Scans each resource file in res/values/ looking for duplicates.
All but the last occurrence of resource definition are removed.
This creates no semantic changes, the resulting APK when built
should contain the same definition.
"""

class Duplicate:
    """A small struct to maintain the positions of a Duplicate resource definition."""
    def __init__(self, name, product, depth, start, end):
        self.name = name
        self.product = product
        self.depth = depth
        self.start = start
        self.end = end

class ResourceDefinitionLocator:
    """Callback class for xml.parsers.expat which records resource definitions and their
    locations.
    """
    def __init__(self, parser):
        self.resource_definitions = {}
        self._parser = parser
        self._depth = 0
        self._current_resource = None

    def start_element(self, tag_name, attrs):
        self._depth += 1
        if self._depth == 2 and tag_name not in ["public", "java-symbol", "eat-comment", "skip"]:
            resource_name = None
            product = ""
            try:
                product = attrs["product"]
            except KeyError:
                pass

            if tag_name == "item":
                resource_name = "{0}/{1}".format(attrs["type"], attrs["name"])
            else:
                resource_name = "{0}/{1}".format(tag_name, attrs["name"])
            self._current_resource = Duplicate(
                    resource_name,
                    product,
                    self._depth,
                    (self._parser.CurrentLineNumber - 1, self._parser.CurrentColumnNumber),
                    None)

    def end_element(self, tag_name):
        if self._current_resource and self._depth == self._current_resource.depth:
            # Record the end position of the element, which is the length of the name
            # plus the </> symbols (len("</>") == 3).
            self._current_resource.end = (self._parser.CurrentLineNumber - 1,
                    self._parser.CurrentColumnNumber + 3 + len(tag_name))
            key_name = "{0}:{1}".format(self._current_resource.name,
                    self._current_resource.product)
            try:
                self.resource_definitions[key_name] += [self._current_resource]
            except KeyError:
                self.resource_definitions[key_name] = [self._current_resource]
            self._current_resource = None
        self._depth -= 1

def remove_duplicates(xml_path):
    """Reads the input file and generates an output file with any duplicate
    resources removed, keeping the last occurring definition and removing
    the others. The output is written to a temporary and then renamed
    to the original file name.
    """
    input = ""
    with open(xml_path) as fin:
        input = fin.read()

    parser = xml.parsers.expat.ParserCreate("utf-8")
    parser.returns_unicode = True
    tracker = ResourceDefinitionLocator(parser)
    parser.StartElementHandler = tracker.start_element
    parser.EndElementHandler = tracker.end_element
    parser.Parse(input)

    # Treat the input as UTF-8 or else column numbers will be wrong.
    input_lines = input.decode('utf-8').splitlines(True)

    # Extract the duplicate resource definitions, ignoring the last definition
    # which will take precedence and be left intact.
    duplicates = []
    for res_name, entries in tracker.resource_definitions.iteritems():
        if len(entries) > 1:
            duplicates += entries[:-1]

    # Sort the duplicates so that they are in order. That way we only do one pass.
    duplicates = sorted(duplicates, key=lambda x: x.start)

    last_line_no = 0
    last_col_no = 0
    output_lines = []
    current_line = ""
    for definition in duplicates:
        print "{0}:{1}:{2}: removing duplicate resource '{3}'".format(
                xml_path, definition.start[0] + 1, definition.start[1], definition.name)

        if last_line_no < definition.start[0]:
            # The next definition is on a new line, so write what we have
            # to the output.
            new_line = current_line + input_lines[last_line_no][last_col_no:]
            if not new_line.isspace():
                output_lines.append(new_line)
            current_line = ""
            last_col_no = 0
            last_line_no += 1

        # Copy all the lines up until this one.
        for line_to_copy in xrange(last_line_no, definition.start[0]):
            output_lines.append(input_lines[line_to_copy])

        # Add to the existing line we're building, by including the prefix of this line
        # and skipping the lines and characters until the end of this duplicate definition.
        last_line_no = definition.start[0]
        current_line += input_lines[last_line_no][last_col_no:definition.start[1]]
        last_line_no = definition.end[0]
        last_col_no = definition.end[1]

    new_line = current_line + input_lines[last_line_no][last_col_no:]
    if not new_line.isspace():
        output_lines.append(new_line)
    current_line = ""
    last_line_no += 1
    last_col_no = 0

    for line_to_copy in xrange(last_line_no, len(input_lines)):
        output_lines.append(input_lines[line_to_copy])

    if len(duplicates) > 0:
        print "{0}: writing deduped copy...".format(xml_path)

        # Write the lines to a temporary file.
        dirname, basename = os.path.split(xml_path)
        temp_name = ""
        with tempfile.NamedTemporaryFile(prefix=basename, dir=dirname, delete=False) as temp:
            temp_name = temp.name
            for line in output_lines:
                temp.write(line.encode('utf-8'))

        # Now rename that file to the original so we have an atomic write that is consistent.
        os.rename(temp.name, xml_path)

def enumerate_files(res_path):
    """Enumerates all files in the resource directory that are XML files and
       within a values-* subdirectory. These types of files end up compiled
       in the resources.arsc table of an APK.
    """
    values_directories = os.listdir(res_path)
    values_directories = filter(lambda f: f.startswith('values'), values_directories)
    values_directories = map(lambda f: os.path.join(res_path, f), values_directories)
    all_files = []
    for dir in values_directories:
        files = os.listdir(dir)
        files = filter(lambda f: f.endswith('.xml'), files)
        files = map(lambda f: os.path.join(dir, f), files)
        all_files += files
    return all_files

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print >> sys.stderr, "please specify a path to a resource directory"
        sys.exit(1)

    res_path = os.path.abspath(sys.argv[1])
    print "looking in {0} ...".format(res_path)

    for f in enumerate_files(res_path):
        print "checking {0} ...".format(f)
        remove_duplicates(f)

