#!/usr/bin/python
"""
Compares results of two webkit layout test runs and writes
results to a file.
"""

import optparse
import os
import sys

def DiffResults(marker, new_results, old_results, diff_results, strip_reason):
   """ Given two result files, generate diff and
       write to diff_results file. All arguments are absolute paths
       to files.
   """
   old_file = open(old_results, "r")
   new_file = open(new_results, "r")
   diff_file = open(diff_results, "a") 

   # Read lines from each file
   ndict = new_file.readlines()
   cdict = old_file.readlines()

   # Write marker to diff file
   diff_file.writelines(marker + "\n")
   diff_file.writelines("###############\n")

   # Strip reason from result lines
   if strip_reason is True:
     for i in range(0, len(ndict)):
       ndict[i] = ndict[i].split(' ')[0] + "\n"
     for i in range(0, len(cdict)):
       cdict[i] = cdict[i].split(' ')[0] + "\n"

   # Find results in new_results missing in old_results
   new_count=0
   for line in ndict:
     if line not in cdict:
       diff_file.writelines("+ " + line)
       new_count += 1

   # Find results in old_results missing in new_results
   missing_count=0
   for line in cdict:
     if line not in ndict:
       diff_file.writelines("- " + line)
       missing_count += 1

   print marker + "  >>> added " + str(new_count) + " tests, removed " + str(missing_count) + " tests" 

   diff_file.writelines("\n\n")

   old_file.close()
   new_file.close()
   diff_file.close()
   return

def main(options, args):
  results_dir = os.path.abspath(options.results_directory)
  ref_dir = options.ref_directory

  # if ref_dir is null, cannonify ref_dir to the script dir.
  if not ref_dir:
    script_self = sys.argv[0]
    script_dir = os.path.dirname(script_self)
    ref_dir = os.path.join(script_dir, "results")

  ref_dir = os.path.abspath(ref_dir)

  diff_result = os.path.join(results_dir, "layout_tests_diff.txt") 
  if os.path.exists(diff_result):
    os.remove(diff_result)

  files=["passed", "failed", "nontext", "crashed"]
  for f in files:
    result_file_name = "layout_tests_" + f + ".txt"
    DiffResults(f, os.path.join(results_dir, result_file_name),
                os.path.join(ref_dir, result_file_name), diff_result,
                f == "failed")

if '__main__' == __name__:
  option_parser = optparse.OptionParser()
  option_parser.add_option("", "--ref-directory",
                           default=None,
                           dest="ref_directory",
                           help="directory name under which results are stored.")

  option_parser.add_option("", "--results-directory",
                           default="layout-test-results/",
                           dest="results_directory",
                           help="directory name under which results are stored.")
  options, args = option_parser.parse_args()
  main(options, args)
