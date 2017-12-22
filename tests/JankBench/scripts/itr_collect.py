#!/usr/bin/python

import optparse
import sys
import sqlite3
import scipy.stats
import numpy

import adbutil
from devices import DEVICES

DB_PATH="/data/data/com.android.benchmark/databases/BenchmarkResults"
OUT_PATH = "db/"

QUERY_BAD_FRAME = ("select run_id, name, total_duration from ui_results "
                   "where total_duration >=12 order by run_id, name")
QUERY_PERCENT_JANK = ("select run_id, name, sum(jank_frame) as jank_count, count (*) as total "
                      "from ui_results group by run_id, name")

class IterationResult:
    def __init__(self):
        self.durations = []
        self.jank_count = 0
        self.total_count = 0


def get_scoremap(dbpath):
    db = sqlite3.connect(dbpath)
    rows = db.execute(QUERY_BAD_FRAME)

    scoremap = {}
    for row in rows:
        run_id = row[0]
        name = row[1]
        total_duration = row[2]

        if not run_id in scoremap:
            scoremap[run_id] = {}

        if not name in scoremap[run_id]:
            scoremap[run_id][name] = IterationResult()


        scoremap[run_id][name].durations.append(float(total_duration))

    for row in db.execute(QUERY_PERCENT_JANK):
        run_id = row[0]
        name = row[1]
        jank_count = row[2]
        total_count = row[3]

        if run_id in scoremap.keys() and name in scoremap[run_id].keys():
            scoremap[run_id][name].jank_count = long(jank_count)
            scoremap[run_id][name].total_count = long(total_count)


    db.close()
    return scoremap

def score_device(name, serial, pull = False, verbose = False):
    dbpath = OUT_PATH + name + ".db"

    if pull:
        adbutil.root(serial)
        adbutil.pull(serial, DB_PATH, dbpath)

    scoremap = None
    try:
        scoremap = get_scoremap(dbpath)
    except sqlite3.DatabaseError:
        print "Database corrupt, fetching..."
        adbutil.root(serial)
        adbutil.pull(serial, DB_PATH, dbpath)
        scoremap = get_scoremap(dbpath)

    per_test_score = {}
    per_test_sample_count = {}
    global_overall = {}

    for run_id in iter(scoremap):
        overall = []
        if len(scoremap[run_id]) < 1:
            if verbose:
                print "Skipping short run %s" % run_id
            continue
        print "Run: %s" % run_id
        for test in iter(scoremap[run_id]):
            if verbose:
                print "\t%s" % test
            scores = []
            sample_count = 0
            res = scoremap[run_id][test]
            stddev = numpy.std(res.durations)
            mean = numpy.mean(res.durations)
            sample_count = len(res.durations)
            pj = 100 * res.jank_count / float(res.total_count)
            score = stddev * mean *pj
            if score == 0:
                score = 1
            scores.append(score)
            if verbose:
                print "\tScore = %f x %f x %f = %f (%d samples)" % (stddev, mean, pj, score, len(res.durations))

            geo_run = scipy.stats.gmean(scores)
            if test not in per_test_score:
                per_test_score[test] = []

            if test not in per_test_sample_count:
                per_test_sample_count[test] = []

            per_test_score[test].append(geo_run)
            per_test_sample_count[test].append(int(sample_count))
            overall.append(geo_run)

            if not verbose:
                print "\t%s:\t%0.2f (%0.2f avg. sample count)" % (test, geo_run, sample_count)
            else:
                print "\tOverall:\t%0.2f (%0.2f avg. sample count)" % (geo_run, sample_count)
                print ""

        global_overall[run_id] = scipy.stats.gmean(overall)
        print "Run Overall: %f" % global_overall[run_id]
        print ""

    print ""
    print "Variability (CV) - %s:" % name

    for test in per_test_score:
        print "\t%s:\t%0.2f%% (%0.2f avg sample count)" % (test, 100 * scipy.stats.variation(per_test_score[test]), numpy.mean(per_test_sample_count[test]))

    print "\tOverall: %0.2f%%" % (100 * scipy.stats.variation([x for x in global_overall.values()]))
    print ""

def parse_options(argv):
    usage = 'Usage: %prog [options]'
    desc = 'Example: %prog'
    parser = optparse.OptionParser(usage=usage, description=desc)
    parser.add_option("-p", dest='pull', action="store_true")
    parser.add_option("-d", dest='device', action="store")
    parser.add_option("-v", dest='verbose', action="store_true")
    options, categories = parser.parse_args(argv[1:])
    return options

def main():
    options = parse_options(sys.argv)
    if options.device != None:
        score_device(options.device, DEVICES[options.device], options.pull, options.verbose)
    else:
        for name, serial in DEVICES.iteritems():
            print "======== %s =========" % name
            score_device(name, serial, options.pull, options.verbose)

if __name__ == "__main__":
    main()
