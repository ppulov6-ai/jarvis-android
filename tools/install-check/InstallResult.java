package ru.pulat.jarvis.installcheck;
import android.content.*;
import android.content.pm.PackageInstaller;
public final class InstallResult extends BroadcastReceiver {
 public void onReceive(Context c, Intent i) {
  if(i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)!=c.getSharedPreferences("diagnostic",0).getInt("session",-2))return;
  int status=i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
  String message=i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
  c.getSharedPreferences("diagnostic",0).edit().putInt("status",status).putString("message",message==null?"":message).putBoolean("busy",false).commit();
  if(status==PackageInstaller.STATUS_PENDING_USER_ACTION)
   MainActivity.pending=i.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class);
  MainActivity.changed();
 }
}
