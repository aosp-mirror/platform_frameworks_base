#ifndef MERGE_RES_AND_XLIFF_H
#define MERGE_RES_AND_XLIFF_H

#include "ValuesFile.h"
#include "XLIFFFile.h"

ValuesFile* merge_res_and_xliff(const ValuesFile* en_current,
                                const ValuesFile* xx_current, const ValuesFile* xx_old,
                                const string& filename, const XLIFFFile* xliff);

int do_merge(const vector<string>& xliffFilenames);

#endif // MERGE_RES_AND_XLIFF_H
