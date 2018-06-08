#!/usr/bin/python

import optparse
import sys
import sqlite3
import scipy.stats
import numpy
from math import log10, floor
import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import pylab

import adbutil
from devices import DEVICES

DB_PATH="/data/data/com.android.benchmark/databases/BenchmarkResults"
OUT_PATH = "db/"

QUERY_BAD_FRAME = ("select run_id, name, iteration, total_duration from ui_results "
                   "where total_duration >= 16 order by run_id, name, iteration")
QUERY_PERCENT_JANK = ("select run_id, name, iteration, sum(jank_frame) as jank_count, count (*) as total "
                      "from ui_results group by run_id, name, iteration")

SKIP_TESTS = [
    # "BMUpload",
    # "Low-hitrate text render",
    # "High-hitrate text render",
    # "Edit Text Input",
    # "List View Fling"
]

INCLUDE_TESTS = [
    #"BMUpload"
    #"Shadow Grid Fling"
    #"Image List View Fling"
    #"Edit Text Input"
]

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
        iteration = row[2]
        total_duration = row[3]

        if not run_id in scoremap:
            scoremap[run_id] = {}

        if not name in scoremap[run_id]:
            scoremap[run_id][name] = {}

        if not iteration in scoremap[run_id][name]:
            scoremap[run_id][name][iteration] = IterationResult()

        scoremap[run_id][name][iteration].durations.append(float(total_duration))

    for row in db.execute(QUERY_PERCENT_JANK):
        run_id = row[0]
        name = row[1]
        iteration = row[2]
        jank_count = row[3]
        total_count = row[4]

        if run_id in scoremap.keys() and name in scoremap[run_id].keys() and iteration in scoremap[run_id][name].keys():
            scoremap[run_id][name][iteration].jank_count = long(jank_count)
            scoremap[run_id][name][iteration].total_count = long(total_count)

    db.close()
    return scoremap

def round_to_2(val):
    return val
    if val == 0:
        return val
    return round(val , -int(floor(log10(abs(val)))) + 1)

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
            if test in SKIP_TESTS:
                continue
            if INCLUDE_TESTS and test not in INCLUDE_TESTS:
                continue
            if verbose:
                print "\t%s" % test
            scores = []
            means = []
            stddevs = []
            pjs = []
            sample_count = 0
            hit_min_count = 0
            # try pooling together all iterations
            for iteration in iter(scoremap[run_id][test]):
                res = scoremap[run_id][test][iteration]
                stddev = round_to_2(numpy.std(res.durations))
                mean = round_to_2(numpy.mean(res.durations))
                sample_count += len(res.durations)
                pj = round_to_2(100 * res.jank_count / float(res.total_count))
                score = stddev * mean * pj
                score = 100 * len(res.durations) / float(res.total_count)
                if score == 0:
                    score = 1
                scores.append(score)
                means.append(mean)
                stddevs.append(stddev)
                pjs.append(pj)
                if verbose:
                    print "\t%s: Score = %f x %f x %f = %f (%d samples)" % (iteration, stddev, mean, pj, score, len(res.durations))

            if verbose:
                print "\tHit min: %d" % hit_min_count
                print "\tMean Variation: %0.2f%%" % (100 * scipy.stats.variation(means))
                print "\tStdDev Variation: %0.2f%%" % (100 * scipy.stats.variation(stddevs))
                print "\tPJ Variation: %0.2f%%" % (100 * scipy.stats.variation(pjs))

            geo_run = numpy.mean(scores)
            if test not in per_test_score:
                per_test_score[test] = []

            if test not in per_test_sample_count:
                per_test_sample_count[test] = []

            sample_count /= len(scoremap[run_id][test])

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

    worst_offender_test = None
    worst_offender_variation = 0
    for test in per_test_score:
        variation = 100 * scipy.stats.variation(per_test_score[test])
        if worst_offender_variation < variation:
            worst_offender_test = test
            worst_offender_variation = variation
        print "\t%s:\t%0.2f%% (%0.2f avg sample count)" % (test, variation, numpy.mean(per_test_sample_count[test]))

    print "\tOverall: %0.2f%%" % (100 * scipy.stats.variation([x for x in global_overall.values()]))
    print ""

    return {
            "overall": global_overall.values(),
            "worst_offender_test": (name, worst_offender_test, worst_offender_variation)
            }

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
        device_scores = []
        worst_offenders = []
        for name, serial in DEVICES.iteritems():
            print "======== %s =========" % name
            result = score_device(name, serial, options.pull, options.verbose)
            device_scores.append((name, result["overall"]))
            worst_offenders.append(result["worst_offender_test"])


        device_scores.sort(cmp=(lambda x, y: cmp(x[1], y[1])))
        print "Ranking by max overall score:"
        for name, score in device_scores:
            plt.plot([0, 1, 2, 3, 4, 5], score, label=name)
            print "\t%s: %s" % (name, score)

        plt.ylabel("Jank %")
        plt.xlabel("Iteration")
        plt.title("Jank Percentage")
        plt.legend()
        pylab.savefig("holy.png", bbox_inches="tight")

        print "Worst offender tests:"
        for device, test, variation in worst_offenders:
            print "\t%s: %s %.2f%%" % (device, test, variation)

if __name__ == "__main__":
    main()

