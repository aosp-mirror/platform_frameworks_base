<?cs if:!sdk.redirect ?>
<ul>
  <li><?cs 
   if:android.whichdoc == "online" ?>
    <h2>
      <span class="en">Current SDK Release</span>
      <span style="display:none" class="de">Aktuelle SDK-Version</span>
      <span style="display:none" class="es">Versión actual del SDK</span>
      <span style="display:none" class="fr">Version actuelle du SDK</span>
      <span style="display:none" class="it">Release SDK attuale</span>
      <span style="display:none" class="ja">現在リリースされている SDK</span>
      <span style="display:none" class="zh-CN">当前的 SDK 版本</span>
      <span style="display:none" class="zh-TW">目前 SDK 發行版本</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/<?cs
        if:sdk.preview ?>preview<?cs
			  else ?><?cs var:sdk.current ?><?cs
			  /if ?>/index.html">
          <span class="en">Download</span>
          <span style="display:none" class="de">Herunterladen</span>
          <span style="display:none" class="es">Descargar</span>
          <span style="display:none" class="fr">Téléchargement</span>
          <span style="display:none" class="it">Download</span>
          <span style="display:none" class="ja">ダウンロード</span>
          <span style="display:none" class="zh-CN">下载</span>
          <span style="display:none" class="zh-TW">下載</span>
        </a></li>
      <li><a href="<?cs var:toroot ?>sdk/<?cs
        if:sdk.preview ?>preview<?cs
			  else ?><?cs var:sdk.current ?><?cs
			  /if ?>/installing.html">
          <span class="en">Installing</span>
          <span style="display:none" class="de">Installieren</span>
          <span style="display:none" class="es">Instalación</span>
          <span style="display:none" class="fr">Installation</span>
          <span style="display:none" class="it">Installazione</span>
          <span style="display:none" class="ja">インストール</span>
          <span style="display:none" class="zh-CN">安装</span>
          <span style="display:none" class="zh-TW">安裝</span>
        </a></li>
      <li><a href="<?cs var:toroot ?>sdk/<?cs
        if:sdk.preview ?>preview<?cs
			  else ?><?cs var:sdk.current ?><?cs
			  /if ?>/upgrading.html">Upgrading</a></li>
      <li><a href="<?cs var:toroot ?>sdk/<?cs
        if:sdk.preview ?>preview<?cs
			  else ?><?cs var:sdk.current ?><?cs
			  /if ?>/requirements.html">System Requirements</a></li>
    </ul><?cs 
   else ?> <?cs # else "if NOT online" ... ?>
    <h2>
      <span class="en">Android SDK</span>
    </h2><?cs 
   /if ?> <?cs # end of "if/else online" ... ?>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/terms.html">SDK Terms and Conditions</a></li>
      <li><a href="<?cs var:toroot ?><?cs 
        if:sdk.current == "preview" ?>sdk/preview/index.html<?cs 
        else ?>sdk/RELEASENOTES.html<?cs 
        /if ?>">SDK Release Notes</a></li>
    </ul>
  </li><?cs 
 if:android.whichdoc == "online" ?>
  <li>
    <h2>
      <span class="en">System Image Version Notes</span>
      <span style="display:none" class="de">Versionshinweise zum Systemabbild</span>
      <span style="display:none" class="es">Notas de la versión de System Image</span>
      <span style="display:none" class="fr">Notes de version de l'image système</span>
      <span style="display:none" class="it">Note sulla versione dell'immagine <br />di sistema</span>
      <span style="display:none" class="ja">システム イメージ バージョンに<br />関する注意事項</span>
      <span style="display:none" class="zh-CN">系统图片版本说明</span>
      <span style="display:none" class="zh-TW">系統影像版本資訊</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/android-1.6.html">Android 1.6 Version Notes</a></li>
      <li><a href="<?cs var:toroot ?>sdk/android-1.5.html">Android 1.5 Version Notes</a></li>
      <li><a href="<?cs var:toroot ?>sdk/android-1.1.html">Android 1.1 Version Notes</a></li>
    </ul>
  </li>
  <li>
    <h2>Native Development Tools</h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/ndk/1.5_r1/index.html">Android 1.5 NDK, r1</a></li>
    </ul>
  </li>
  <li>
     <h2>
        <span class="en">Previous SDK Releases</span>
        <span style="display:none" class="de">Frühere SDK-Releases</span>
        <span style="display:none" class="es">Versiones anteriores del SDK</span>
        <span style="display:none" class="fr">Anciennes versions du SDK</span>
        <span style="display:none" class="it">Release SDK precedenti</span>
        <span style="display:none" class="ja">SDK の過去のリリース</span>
        <span style="display:none" class="zh-CN">以前的 SDK 版本</span>
        <span style="display:none" class="zh-TW">較舊的 SDK 發行版本</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/1.5_r3/index.html">Android 1.5 SDK, r3</a></li>
      <li><a href="<?cs var:toroot ?>sdk/1.1_r1/index.html">Android 1.1 SDK, r1</a></li>
      <li><a href="<?cs var:toroot ?>sdk/1.0_r2/index.html">Android 1.0 SDK, r2</a></li>
      <li><a href="<?cs var:toroot ?>sdk/older_releases.html">Other Releases</a></li>
    </ul>
  </li><?cs 
 /if ?> <?cs # end of "if online" ?>
</ul>

<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>
<?cs /if ?>