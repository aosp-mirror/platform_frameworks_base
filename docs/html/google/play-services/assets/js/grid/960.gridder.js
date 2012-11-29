/*
 * Release: 1.3.1 2009-04-26
 */
/*
 * Copyright (c) Andr?e Hansson (peolanha AT gmail DOT com)
 * MIT License - http://www.opensource.org/licenses/mit-license.php
 * Idea loosely based on JASH, http://billyreisinger.com/jash/
 *
 * Website: http://gridder.andreehansson.se/
 *
 * Changelog:
 * - New GUI! The new GUI should be less obtrusive and has been repositioned.
 *   It is also featuring a slight delay on inputs so that you'll have a chance
 *   to change the settings before it is re-rendering the grid
 * - Due to a lot of inquries regarding affiliation with jQuery the filenames has
 *   been changed, I'm very sorry for the inconvenience!
 * - CSS issues with the GUI should also be fixed in more websites, please report
 *   in any issue you stumble upon
 * - A small bug in IE that made the paragraph lines not position correctly has been
 *   fixed
 * - A dropdown box has replaced the columns input box, 960 Gridder calculates the
 *   proper number of columns that can be used with the specified grid width
 * - The 960 Gridder is now displaying perfectly (into the very pixels) in all
 *   A-grade browsers (according to browsershots.org)
 * - An option to invert the gutters has been added, set this to 'true' if
 *   you want to use it, OR use the shortcut CTRL+ALT+A
 * - Some other minor changes...
 */
function Grid() {
  var c = this;
  c.settingsDef = {
    urlBase: "http://gridder.andreehansson.se/releases/1.3.1/",
    gColor: "#EEEEEE",
    gColumns: 12,
    gOpacity: 0.45,
    gWidth: 10,
    pColor: "#C0C0C0",
    pHeight: 15,
    pOffset: 0,
    pOpacity: 0.55,
    center: true,
    invert: false,
    gEnabled: true,
    pEnabled: true,
    size: 960,
    fixFlash: true,
    setupEnabled: true,
    pressedKeys: [],
    delayTimer: ""
  };
  c.settings = (typeof (window.gOverride) === "undefined") ? {} : window.gOverride;
  for (var a in c.settingsDef) {
    if (typeof (c.settings[a]) === "undefined") {
      c.settings[a] = c.settingsDef[a];
    }
  }
  if (typeof (window.jQuery) === "undefined" || jQuery().jquery.match(/^1\.3/) === null) {
    window.jQuery = undefined;
    var b = document.createElement("script");
    b.type = "text/javascript";
    b.src = c.settings.urlBase + "jquery.js";
    document.body.appendChild(b);
  }
  c._createEntity = function (e, d) {
    jQuery('<div class="g-' + e + '">&nbsp;</div>').appendTo("#g-grid").css(d);
  };
  c._setVariable = function (d, e) {
    d = d.replace(/g-setup-/, "");
    if (isNaN(parseInt(e, 10)) || parseInt(e, 10) === 0) {
      c.settings[d] = e;
    } else {
      c.settings[d] = parseInt(e, 10);
    }
    if (e === true) {
      jQuery("#g-setup-" + d).attr("checked", "checked");
    } else {
      if (e === false) {
        jQuery("#g-setup-" + d).removeAttr("checked");
      } else {
        jQuery("#g-setup-" + d).val(e);
      }
    }
  };
  c.setupWindow = function () {
    jQuery('<style type"text/css">#g-setup{position:absolute;top:150px;left:-310px;padding:6px;margin:0;list-style:none;width:320px!important;background-color:#d1cfe6;border:2px solid #a19bd1;z-index:2100; display:none;}#g-setup *{background:transparent!important;border:0!important;color:#58517c!important;font-family:Verdana,Geneva,sans-serif!important;font-size:10px!important;font-weight:normal!important;letter-spacing:normal!important;line-height:1!important;list-style-type:none!important;margin:0!important;padding:0!important;text-decoration:none!important;text-indent:0!important;text-transform:none!important;word-spacing:0!important;z-index:2100!important;}#g-setup .head{font-weight:bold!important;text-align:center;border-bottom:1px solid #7cb267!important;}#g-setup ul{width:150px;float:left!important;}#g-setup li{clear:left;padding:5px!important;}* html #g-setup li{clear:none!important;padding:4px!important;}#g-setup span{float:left!important;width:50px;padding:1px 4px 0 0!important;text-align:right!important;line-height:1.5!important;}#g-setup input,#g-setup select{float:left!important;width:70px;border:1px solid #a19bd1!important;background-color:#e7e6ee!important;padding:2px!important;}#g-setup select{width:77px;padding:0!important;}#g-setup-misc{margin-top:5px!important;clear:left;float:none!important;width:300px!important;border-top:1px solid #7cb267!important;}#g-setup-misc span{line-height:1.1!important;width:200px;}#g-setup-misc input{width:15px;padding:0!important;height:15px;}#g-setup-tab{width:26px;overflow:hidden;position:absolute;top:0;left:100%;margin-left:-26px!important;z-index:2100!important;}#g-setup-tab img{left:0;position:relative;}#g-grid{left:0;position:absolute;z-index:500;top:0;}#g-grid .g-vertical,#g-grid .g-horizontal{position:absolute;z-index:1000;}*:first-child+html #g-grid .g-horizontal,*:first-child+html #g-grid .g-vertical{margin-left:-1px;}#g-grid .g-horizontal{min-height:1px;height:1px;font-size:0;line-height:0;}</style>').appendTo("head");
    c.settings.height = jQuery(document).height();
    if (c.settings.setupEnabled) {
      jQuery('<div id="g-setup"><ul><li class="head">Vertical</li><li><span>Color</span><input id="g-setup-gColor" /></li><li><span>Opacity</span><input id="g-setup-gOpacity" /></li><li><span>Width</span><input id="g-setup-gWidth" /></li><li><span>Columns</span><select id="g-setup-gColumns"></select></li></ul><ul><li class="head">Horizontal</li><li><span>Color</span><input id="g-setup-pColor" /></li><li><span>Opacity</span><input id="g-setup-pOpacity" /></li><li><span>Height</span><input id="g-setup-pHeight" /></li><li><span>Offset</span><input id="g-setup-pOffset" /></li></ul><ul id="g-setup-misc"><li><span>Enable vertical (gutters)</span><input id="g-setup-gEnabled" type="checkbox" /></li><li><span>Enable horizontal (paragraphs)</span><input id="g-setup-pEnabled" type="checkbox" /></li><li><span>Invert vertical</span><input id="g-setup-invert" type="checkbox" /></li><li><span>Center grid</span><input id="g-setup-center" type="checkbox" /></li></ul><div style="clear: left;"></div><div id="g-setup-tab"><a href="javascript:;"><img src="http://gridder.andreehansson.se/releases/1.3.1/logo-sprite.png" alt="" /></a></div></div>').appendTo("body");
      for (var d = 2; d < 48; d++) {
        if (Math.round((c.settings.size / d)) === (c.settings.size / d)) {
          jQuery('<option value="' + d + '">' + d + "</option>").appendTo("#g-setup-gColumns");
        }
      }
      for (var d in c.settings) {
        if (jQuery("#g-setup-" + d).length !== 0) {
          if (jQuery("#g-setup-" + d).parent().parent().is("#g-setup-misc") && c.settings[d]) {
            jQuery("#g-setup-" + d).attr("checked", "checked");
          } else {
            jQuery("#g-setup-" + d).val(c.settings[d]);
          }
        }
      }
      jQuery("#g-setup").css("top", jQuery(window).scrollTop() + 150);
      jQuery("#g-setup-tab a").click(function () {
        c.toggleSetupWindow();
      });
      jQuery("#g-setup input").keyup(function () {
        var e = this;
        clearTimeout(c.settings.delayTimer);
        c.settings.delayTimer = setTimeout(function () {
          c.setVariable(jQuery(e).attr("id"), jQuery(e).val());
        }, 700);
      });
      jQuery("#g-setup-gColumns").change(function () {
        c.setVariable("gColumns", $(this).val());
      });
      jQuery("#g-setup-misc input").click(function () {
        c.setVariable(jQuery(this).attr("id"), jQuery(this).attr("checked"));
      });
      jQuery().keydown(function (f) {
        if (jQuery.inArray(f.which, c.settings.pressedKeys) === -1) {
          c.settings.pressedKeys.push(f.which);
        }
      });
      jQuery(window).scroll(function () {
        jQuery("#g-setup").css("top", jQuery().scrollTop() + 150);
      });
    }
    jQuery().keyup(function (g) {
      if (jQuery.inArray(17, c.settings.pressedKeys) !== -1 && jQuery.inArray(18, c.settings.pressedKeys) !== -1) {
        if (jQuery.inArray(90, c.settings.pressedKeys) !== -1) {
          c.setVariable("gEnabled", !c.settings.gEnabled);
        } else {
          if (jQuery.inArray(88, c.settings.pressedKeys) !== -1) {
            c.setVariable("pEnabled", !c.settings.pEnabled);
          } else {
            if (jQuery.inArray(65, c.settings.pressedKeys) !== -1) {
              c.setVariable("invert", !c.settings.invert);
            } else {
              if (jQuery.inArray(67, c.settings.pressedKeys) !== -1) {
                c.setVariable({
                  gEnabled: !c.settings.gEnabled,
                  pEnabled: !c.settings.pEnabled
                });
              }
            }
          }
        }
      }
      var f = jQuery.inArray(g.which, c.settings.pressedKeys);
      c.settings.pressedKeys.splice(f, f);
    });
  };
  c.setVariable = function () {
    if (typeof (arguments[0]) === "object") {
      for (var d in arguments[0]) {
        c._setVariable(d, arguments[0][d]);
      }
    } else {
      c._setVariable(arguments[0], arguments[1]);
    }
    c.createGrid();
  };
  c.toggleSetupWindow = function () {
    var d = jQuery("#g-setup-tab img");
    d.css("left", d.position().left === 0 ? -26 : 0);
    if (parseInt(jQuery("#g-setup").css("left"), 10) === 0) {
      jQuery("#g-setup").animate({
        left: -310
      }, 200);
    } else {
      jQuery("#g-setup").animate({
        left: 0
      }, 200);
    }
  };
  c.createGrid = function () {
    jQuery("embed").each(function () {
      if (c.settings.fixFlash) {
        jQuery(this).attr("wmode", "transparent");
      } else {
        jQuery(this).removeAttr("wmode");
      }
      var i = jQuery(this).wrap("<div></div>").parent().html();
      jQuery(this).parent().replaceWith(i);
      jQuery(this).remove();
    });
    jQuery("#g-grid").remove();
    jQuery('<div id="g-grid"></div>').appendTo("body").css("width", c.settings.size);
    if (c.settings.center) {
      jQuery("#g-grid").css({
        left: "50%",
        marginLeft: -((c.settings.size / 2) + c.settings.gWidth)
      });
    }
    if (c.settings.gEnabled && c.settings.gColumns > 0) {
      if (c.settings.invert) {
        jQuery().css("overflow-x", "hidden");
        var e = (jQuery(window).width() - c.settings.size) / 2;
        c._createEntity("vertical", {
          left: -e,
          width: e,
          height: c.settings.height,
          backgroundColor: c.settings.gColor,
          opacity: c.settings.gOpacity
        });
        for (var g = 0; g < c.settings.gColumns; g++) {
          var f = (c.settings.size / c.settings.gColumns) - (c.settings.gWidth * 2);
          var h = (c.settings.gWidth * 2);
          c._createEntity("vertical", {
            left: ((f + h) * g) + h,
            width: f + "px",
            height: c.settings.height,
            backgroundColor: c.settings.gColor,
            opacity: c.settings.gOpacity
          });
        }
        if ((c.settings.height + 10) > jQuery(window).height()) {
          e -= 10;
        }
        c._createEntity("vertical", {
          left: "100%",
          marginLeft: 20,
          width: e,
          height: c.settings.height,
          backgroundColor: c.settings.gColor,
          opacity: c.settings.gOpacity
        });
      } else {
        for (var g = 0; g <= c.settings.gColumns; g++) {
          c._createEntity("vertical", {
            left: ((c.settings.size / c.settings.gColumns) * g),
            width: (c.settings.gWidth * 2),
            height: c.settings.height,
            backgroundColor: c.settings.gColor,
            opacity: c.settings.gOpacity
          });
        }
      }
    }
    if (c.settings.pEnabled && c.settings.pHeight > 1) {
      var d = ((c.settings.height - c.settings.pOffset) / c.settings.pHeight);
      for (g = 0; g <= d; g++) {
        c._createEntity("horizontal", {
          top: ((c.settings.height / d) * g) + c.settings.pOffset,
          left: "50%",
          marginLeft: -(c.settings.size / 2),
          width: (c.settings.size + (c.settings.gWidth * 2)),
          backgroundColor: c.settings.pColor,
          opacity: c.settings.pOpacity
        });
      }
    }
  };
}
var checkJQuery = function () {
    if (typeof (window.jQuery) === "undefined") {
      setTimeout(function () {
        checkJQuery();
      }, 10);
    } else {
      window.grid.setupWindow();
      window.grid.createGrid();
    }
  };
if (typeof (window.grid) === "undefined") {
  window.grid = new Grid();
  checkJQuery();
} else {
  window.grid.toggleSetupWindow();
}