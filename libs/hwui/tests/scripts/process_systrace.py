#!/usr/bin/env python

import codecs, httplib, json, os, urllib, shutil, subprocess, sys, argparse

upstream_git = 'https://github.com/catapult-project/catapult.git'

script_dir = os.path.dirname(os.path.abspath(sys.argv[0]))
catapult_src_dir = os.path.join(script_dir, 'catapult-upstream')

parser = argparse.ArgumentParser()
parser.add_argument('trace_file_or_dir',
      help='Path to trace file or directory of trace files.')
parser.add_argument('--output_file', dest='outfile', default=os.path.join(os.getcwd(), 'mapper_output.json'),
      help='Path to output file to store results.')
parser.add_argument('--mapper_func', dest='func', default='AvgDrawFrame',
      help='Name of javascript mapper function in systrace_parser.html.')
args = parser.parse_args()

# Update the source if needed.
if not os.path.exists(catapult_src_dir):
  # Pull the latest source from the upstream git.
  git_args = ['git', 'clone', upstream_git, catapult_src_dir]
  p = subprocess.Popen(git_args, stdout=subprocess.PIPE, cwd=script_dir)
  p.communicate()
  if p.wait() != 0:
    print 'Failed to checkout source from upstream git.'
    sys.exit(1)

mapper_func_file = os.path.join(script_dir, 'systrace_parser.html')
path_to_process_traces = os.path.join(catapult_src_dir, 'trace_processor/bin/process_traces')
run_command = path_to_process_traces + " --mapper_handle " + mapper_func_file + ":" + args.func + " --output_file " + args.outfile + " " + args.trace_file_or_dir
print run_command
sys.exit(os.system(run_command))

