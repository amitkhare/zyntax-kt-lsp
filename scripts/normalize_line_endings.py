#!/usr/bin/env python3
import sys
import argparse

def normalize_line_endings(input_path, output_path=None):
    with open(input_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Replace escaped sequences with actual line endings
    # Order matters: replace \r\n first to avoid partial replacements
    content = content.replace('\\r\\n', '\r\n')
    content = content.replace('\\r', '\r')
    content = content.replace('\\n', '\n')

    if output_path:
        with open(output_path, 'w', encoding='utf-8', newline='') as f:
            f.write(content)
    else:
        sys.stdout.write(content)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description="Converts escaped line ending sequences (\\r\\n, \\r, \\n) to actual line endings."
    )
    parser.add_argument('input_file', help='Path to input file')
    parser.add_argument('output_file', nargs='?', help='Path to output file (writes to stdout if omitted)')
    args = parser.parse_args()

    normalize_line_endings(args.input_file, args.output_file)
