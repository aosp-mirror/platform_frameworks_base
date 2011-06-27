<?cs if:!sdk.redirect ?>
<ul>
  <li>
    <h2>
      <span class="en">Android SDK Starter Package</span>
      <span style="display:none" class="de">Aktuelle SDK-Version</span>
      <span style="display:none" class="es">Versión actual del SDK</span>
      <span style="display:none" class="fr">Version actuelle du SDK</span>
      <span style="display:none" class="it">Release SDK attuale</span>
      <span style="display:none" class="ja">現在リリースされている SDK</span>
      <span style="display:none" class="zh-CN">当前的 SDK 版本</span>
      <span style="display:none" class="zh-TW">目前 SDK 發行版本</span>
    </h2>
    <ul><?cs
  if:android.whichdoc == "online" ?>
      <li><a href="<?cs var:toroot ?>sdk/index.html">
          <span class="en">Download</span>
          <span style="display:none" class="de">Herunterladen</span>
          <span style="display:none" class="es">Descargar</span>
          <span style="display:none" class="fr">Téléchargement</span>
          <span style="display:none" class="it">Download</span>
          <span style="display:none" class="ja">ダウンロード</span>
          <span style="display:none" class="zh-CN">下载</span>
          <span style="display:none" class="zh-TW">下載</span>
        </a></li><?cs 
  /if ?>
      <li><a href="<?cs var:toroot ?>sdk/installing.html">
          <span class="en">Installing the SDK</span>
          <span style="display:none" class="de">Installieren</span>
          <span style="display:none" class="es">Instalación</span>
          <span style="display:none" class="fr">Installation</span>
          <span style="display:none" class="it">Installazione</span>
          <span style="display:none" class="ja">インストール</span>
          <span style="display:none" class="zh-CN">安装</span>
          <span style="display:none" class="zh-TW">安裝</span>
        </a></li>
    </ul>
  </li><?cs
  if:sdk.preview ?>
  <li><h2>Android 3.0 Preview SDK</h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/preview/start.html">Getting Started</a> <span
class="new">new!</span></li>
    </ul>
  </li><?cs
  /if ?>
  <?cs
  if:sdk.preview ?>
  <li><h2>Android x.x Preview</h2>
    <ul>
    </ul>
  </li><?cs
  /if ?>
  <li>
    <h2>
      <span class="en">Downloadable SDK Components</span>
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/adding-components.html">
      <span class="en">Adding SDK Components</span>
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span></a>
      </li>
    </ul>
    <ul>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>sdk/android-3.2.html">
        <span class="en">Android 3.2 Platform</span></a> <span class="new">new!</span></div>
        <ul>
          <!-- <li><a href="<?cs var:toroot ?>sdk/android-3.2-highlights.html">Platform Highlights</a></li> -->
          <li><a href="<?cs var:toroot ?>sdk/api_diff/13/changes.html">API Differences Report &raquo;</a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>sdk/android-3.1.html">
        <span class="en">Android 3.1 Platform</span></a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>sdk/android-3.1-highlights.html">Platform Highlights</a></li> 
          <li><a href="<?cs var:toroot ?>sdk/api_diff/12/changes.html">API Differences Report &raquo;</a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>sdk/android-3.0.html">
        <span class="en">Android 3.0 Platform</span></a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>sdk/android-3.0-highlights.html">Platform Highlights</a></li> 
          <li><a href="<?cs var:toroot ?>sdk/api_diff/11/changes.html">API Differences Report &raquo;</a></li>
        </ul>
      </li>
      <li><a href="<?cs var:toroot ?>sdk/android-2.3.4.html">Android 2.3.4 Platform</span></a></li>
      <li class="toggle-list">
      <div><a href="<?cs var:toroot ?>sdk/android-2.3.3.html">
      <span class="en">Android 2.3.3 Platform</span></a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>sdk/api_diff/10/changes.html">API Differences Report &raquo;</a></li> 
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>sdk/android-2.2.html">
        <span class="en">Android 2.2 Platform</span></a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>sdk/android-2.2-highlights.html">Platform Highlights</a></li> 
          <li><a href="<?cs var:toroot ?>sdk/api_diff/8/changes.html">API Differences Report &raquo;</a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>sdk/android-2.1.html">
        <span class="en">Android 2.1 Platform</span></a></div>
        <ul>
          <li><a href="<?cs var:toroot ?>sdk/api_diff/7/changes.html">API Differences Report &raquo;</a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="#" onclick="toggle(this.parentNode.parentNode,true); return false;">Other Platforms</a></div>
        <ul>
          <li class="toggle-list">
            <div><a href="<?cs var:toroot ?>sdk/android-2.3.html">
            <span class="en">Android 2.3 Platform</span></a></div>
              <ul>
                <li><a href="<?cs var:toroot ?>sdk/android-2.3-highlights.html">Platform Highlights</a></li> 
                <li><a href="<?cs var:toroot ?>sdk/api_diff/9/changes.html">API Differences Report &raquo;</a></li> 
              </ul>
          </li>
          <li><a href="<?cs var:toroot ?>sdk/android-2.0.1.html">Android 2.0.1 Platform</a></li>
          <li><a href="<?cs var:toroot ?>sdk/android-2.0.html">Android 2.0 Platform</a></li>
          <li><a href="<?cs var:toroot ?>sdk/android-1.6.html">Android 1.6 Platform</a></li>
          <li><a href="<?cs var:toroot ?>sdk/android-1.5.html">Android 1.5 Platform</a></li>
          <li><a href="<?cs var:toroot ?>sdk/android-1.1.html">Android 1.1 Platform</a></li>
        </ul>
      </li>
    </ul>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/tools-notes.html">SDK Tools, r12</a> <span
class="new">new!</span></li>
      <li><a href="<?cs var:toroot ?>sdk/win-usb.html">Google USB Driver, r4</a></li>
      <li><a href="<?cs var:toroot ?>sdk/compatibility-library.html">Compatibility Library,
r2</a></li>
    </ul>
  </li>
  <li>
      <h2>
      <span class="en">ADT Plugin for Eclipse</span>
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span>
      </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/eclipse-adt.html">ADT 12.0.0
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span></a>
      <span class="new">new!</span></li>
    </ul>
  </li>
  <li>
    <h2><span class="en">Native Development Tools</span>
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/ndk/index.html">Android NDK, r6 <span
       class="new">new!</span></a>
        </li>
      <li><a href="<?cs var:toroot ?>sdk/ndk/overview.html">What is the NDK?</a></li>
    </ul>
  </li>

  <li>
    <h2>
      <span class="en">More Information</span>
      <span style="display:none" class="de"></span>
      <span style="display:none" class="es"></span>
      <span style="display:none" class="fr"></span>
      <span style="display:none" class="it"></span>
      <span style="display:none" class="ja"></span>
      <span style="display:none" class="zh-CN"></span>
      <span style="display:none" class="zh-TW"></span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/oem-usb.html">
        <span class="en">OEM USB Drivers</span>
      </a></li>
      <li><a href="<?cs var:toroot ?>sdk/requirements.html">SDK System Requirements</a></li>
      <li><a href="<?cs var:toroot ?>sdk/older_releases.html">SDK Archives</a></li>

    </ul>
  </li>

</ul>

<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>
<?cs /if ?>
