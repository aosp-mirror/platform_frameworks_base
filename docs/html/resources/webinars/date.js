// Copyright 2009 Google Inc. All Rights Reserved.

/**
 * @fileoverview Utility functions for formating date.
 */

/**
 * Formats start date and end date in JSON format to string of format:
   "09/09/2010 20:00 PM to 22:00 PM PST"
 * @param {object} start date in JSON format.
 * @param {object} end date in JSON format.
 * @param {string} formatted date string.
 */
function formatDate(start,end) {
  var s_ampm = null;
  var e_ampm = null;

  var toStr = function (num) {
    if (num <= 12) {
      return "" + num;
    } else {
      return "" + (num - 12);
    }
  };

  var getMonthName = function (num) {
    switch(num) {
      case 1:
        return 'January';
      case 2:
        return 'February';
      case 3:
        return 'March';
      case 4:
        return 'April';
      case 5:
        return 'May';
      case 6:
        return 'June';
      case 7:
        return 'July';
      case 8:
        return 'August';
      case 9:
        return 'September';
      case 10:
        return 'October';
      case 11:
        return 'November';
      case 12:
        return 'December';
     }
  }

  var regex =  /(^\d{4})-(\d{2})-(\d{2})\s{1}(\d{2}):(\d{2}):(\d{2}$)/;
  var s_match = regex.exec(start.toString());

  if( s_match == null) {
   return '';
  }
  var yy = s_match[1];

  var mm = parseInt(s_match[2], 10 /** base 10 **/);
  var dd = s_match[3];

  var s_hh = parseInt(s_match[4], 10 /** base 10 **/);
 
  if (s_hh > 12) {
    s_ampm = "PM";
  } else {
    s_ampm = "AM";
  }
  s_hh = toStr(s_hh);
  var s_mi = s_match[5];


  var str =  getMonthName(mm) + " " +  dd + ", " + yy ;
  str += " " + s_hh + ":" + s_mi;
  str += " " + s_ampm;

  regex =  /(^\d{4})-(\d{2})-(\d{2})\s{1}(\d{2}):(\d{2}):(\d{2}$)/;
  var e_match = regex.exec(end.toString());
  if( e_match == null) {
   return str + ' PST';
  }
  var e_hh = parseInt(e_match[4], 10 /** base 10 **/);
  if (e_hh > 12) {
    e_ampm = "PM";
  } else {
    e_ampm = "AM";
  }
  e_hh = toStr(e_hh);
  var e_mi = e_match[5];

  str += " to " + e_hh + ":" + e_mi;
  str += " " + e_ampm;
  str += " PST";
  return str;

}


function formatDateUtf(date) {
  var regex =  /(^\d{4})-(\d{2})-(\d{2})\s{1}(\d{2}):(\d{2}):(\d{2}$)/;
  var match = regex.exec(date.toString());
  var yy = match[1];
  var mm = match[2];
  var dd = match[3];
  var hh = parseInt(match[4], 10) + 8;  // +8 to convert from PST to GMT
  var mi = match[5] + "00"; // add seconds

  if (hh >= 24) {  // If the GMT adjustment put us into the next day,
    dd++;          // increment the day and
    hh -= 24;      // set the hour back to real hours
  }
  hh = hh < 10 ? "0" + hh : hh;  // form a double digit number for single digit hours

  return yy + mm + dd + 'T' + hh + mi + 'Z';
}
