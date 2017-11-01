#!/usr/bin/env python

"""
Scans each resource file in res/ applying various transformations
to fix invalid resource files.
"""

import os
import os.path
import sys
import tempfile

from consumers.duplicates import DuplicateRemover
from consumers.positional_arguments import PositionalArgumentFixer

def do_it(res_path, consumers):
    for file_path in enumerate_files(res_path):
        eligible_consumers = filter(lambda c: c.matches(file_path), consumers)
        if len(eligible_consumers) > 0:
            print "checking {0} ...".format(file_path)

            original_contents = read_contents(file_path)
            contents = original_contents
            for c in eligible_consumers:
                contents = c.consume(file_path, contents)
            if original_contents != contents:
                write_contents(file_path, contents)

def enumerate_files(res_path):
    """Enumerates all files in the resource directory."""
    values_directories = os.listdir(res_path)
    values_directories = map(lambda f: os.path.join(res_path, f), values_directories)
    all_files = []
    for dir in values_directories:
        files = os.listdir(dir)
        files = map(lambda f: os.path.join(dir, f), files)
        for f in files:
            yield f

def read_contents(file_path):
    """Reads the contents of file_path without decoding."""
    with open(file_path) as fin:
        return fin.read()

def write_contents(file_path, contents):
    """Writes the bytes in contents to file_path by first writing to a temporary, then
    renaming the temporary to file_path, ensuring a consistent write.
    """
    dirname, basename = os.path.split(file_path)
    temp_name = ""
    with tempfile.NamedTemporaryFile(prefix=basename, dir=dirname, delete=False) as temp:
        temp_name = temp.name
        temp.write(contents)
    os.rename(temp.name, file_path)

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print >> sys.stderr, "please specify a path to a resource directory"
        sys.exit(1)

    res_path = os.path.abspath(sys.argv[1])
    print "looking in {0} ...".format(res_path)
    do_it(res_path, [DuplicateRemover(), PositionalArgumentFixer()])
