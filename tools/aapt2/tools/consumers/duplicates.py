"""
Looks for duplicate resource definitions and removes all but the last one.
"""

import os.path
import xml.parsers.expat

class DuplicateRemover:
    def matches(self, file_path):
        dirname, basename = os.path.split(file_path)
        dirname = os.path.split(dirname)[1]
        return dirname.startswith("values") and basename.endswith(".xml")

    def consume(self, xml_path, input):
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
            print "{0}: removing duplicate resource '{1}'".format(xml_path, definition.name)

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
            # and skipping the lines and characters until the end of this duplicate
            # definition.
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
            print "deduped {0}".format(xml_path)
            return "".join(output_lines).encode("utf-8")
        return input

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
