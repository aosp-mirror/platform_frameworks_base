
<ul>
  <li>
    <?cs if:android.whichdoc != "online" ?><h2>Android 1.1 SDK, r1</h2>
    <?cs else ?><h2>Latest SDK Release</h2><?cs /if ?>
    <ul>
      <?cs if:android.whichdoc == "online" ?>
        <li><a href="<?cs var:toroot ?>sdk/1.1_r1/index.html">Download</a></li>
      <?cs /if ?>
      <li><a href="<?cs var:toroot ?>sdk/1.1_r1/installing.html">Installing</a></li>
      <li><a href="<?cs var:toroot ?>sdk/1.1_r1/upgrading.html">Upgrading</a></li>
      <li><a href="<?cs var:toroot ?>sdk/1.1_r1/requirements.html">System Requirements</a></li>
      <li><a href="<?cs var:toroot ?>sdk/RELEASENOTES.html">SDK Release Notes</a></li>
    </ul>
    <ul>
      <li><a href="<?cs var:toroot ?>sdk/android-1.1.html">Android 1.1 Version Notes</a></li>
    </ul>
  </li>
</ul>
<ul>
  <li><a href="<?cs var:toroot ?>sdk/terms.html">SDK Terms and Conditions</a></li>
  <?cs if:android.whichdoc == "online" ?>
    <li><a href="http://code.google.com/android/download_list.html">Previous SDK Releases</a></li>
  <?cs /if ?>
</ul>

