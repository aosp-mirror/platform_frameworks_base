#!/usr/bin/env python

import argparse

from fontTools import ttLib


def update_font_revision(font, revisionSpec):
    if revisionSpec.startswith('+'):
      font['head'].fontRevision += float(revisionSpec[1:])
    else:
      font['head'].fontRevision = float(revisionSpec)


def main():
    args_parser = argparse.ArgumentParser(description='Update font file metadata')
    args_parser.add_argument('--input', help='Input otf/ttf font file.')
    args_parser.add_argument('--output', help='Output file for updated font file.')
    args_parser.add_argument('--revision', help='Updated font revision. Use + to update revision based on the current revision')
    args = args_parser.parse_args()

    font = ttLib.TTFont(args.input)
    update_font_revision(font, args.revision)
    font.save(args.output)

if __name__ == "__main__":
    main()
