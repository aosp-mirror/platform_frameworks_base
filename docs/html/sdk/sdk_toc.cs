<?cs if:!sdk.redirect ?>
<ul>
  <li><?cs 
   if:android.whichdoc != "online" ?>
    <h2>Android <?cs var:sdk.version ?> SDK, r<?cs var:sdk.rel.id ?></h2><?cs 
   else ?>
    <h2><span class="en">Current SDK Release</span>
        <span class="de">Aktuelle SDK-Version</span>
        <span class="es">Versión actual del SDK</span>
        <span class="fr">Version actuelle du SDK</span>
        <span class="it">Release SDK attuale</span>
        <span class="ja">現在リリースされている SDK</span>
        <span class="zh-CN">当前的 SDK 版本</span>
        <span class="zh-TW">目前 SDK 發行版本</span>
    </h2><?cs 
   /if ?>
    <ul><?cs 
     if:android.whichdoc == "online" ?>
      <li><a href="<?cs var:toroot ?>sdk/<?cs var:sdk.current ?>/index.html">
          <span class="en">Download</span>
          <span class="de">Herunterladen</span>
          <span class="es">Descargar</span>
          <span class="fr">Téléchargement</span>
          <span class="it">Download</span>
          <span class="ja">ダウンロード</span>
          <span class="zh-CN">下载</span>
          <span class="zh-TW">下載</span>
         </a></li><?cs 
     /if ?>
      <li><a href="<?cs var:toroot ?>sdk/<?cs var:sdk.current ?>/installing.html">
          <span class="en">Installing</span>
          <span class="de">Installieren</span>
          <span class="es">Instalación</span>
          <span class="fr">Installation</span>
          <span class="it">Installazione</span>
          <span class="ja">インストール</span>
          <span class="zh-CN">安装</span>
          <span class="zh-TW">安裝</span>
      </a></li>
      <li><a href="<?cs var:toroot ?>sdk/<?cs var:sdk.current ?>/upgrading.html">Upgrading</a></li>
      <li><a href="<?cs var:toroot ?>sdk/<?cs var:sdk.current ?>/requirements.html">System Requirements</a></li>
    </ul>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/terms.html">SDK Terms and Conditions</a></li>
      <li><a href="<?cs var:toroot ?>sdk/RELEASENOTES.html">SDK Release Notes</a></li>
    </ul><?cs 
 if:android.whichdoc == "online" ?>
  <li>
    <h2><span class="en">System Image Version Notes</span>
        <span class="de">Versionshinweise zum Systemabbild</span>
        <span class="es">Notas de la versión de System Image</span>
        <span class="fr">Notes de version de l'image système</span>
        <span class="it">Note sulla versione dell'immagine <br />di sistema</span>
        <span class="ja">システム イメージ バージョンに<br />関する注意事項</span>
        <span class="zh-CN">系统图片版本说明</span>
        <span class="zh-TW">系統影像版本資訊</span>
    </h2>
    <ul>
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
        <span class="de">Frühere SDK-Releases</span>
        <span class="es">Versiones anteriores del SDK</span>
        <span class="fr">Anciennes versions du SDK</span>
        <span class="it">Release SDK precedenti</span>
        <span class="ja">SDK の過去のリリース</span>
        <span class="zh-CN">以前的 SDK 版本</span>
        <span class="zh-TW">較舊的 SDK 發行版本</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/1.1_r1/index.html">Android 1.1 SDK, r1</a></li>
      <li><a href="<?cs var:toroot ?>sdk/1.0_r2/index.html">Android 1.0 SDK, r2</a></li>
      <li><a href="<?cs var:toroot ?>sdk/older_releases.html">Other Releases</a></li>
    </ul>
  </li><?cs 
 /if ?>
</ul>

<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>
<?cs /if ?>