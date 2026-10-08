package ru.pulat.jarvis.installcheck;
import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Locale;

public final class MainActivity extends Activity {
 static final String APK_SHA="cf31e3dc73b6b5965cc39441a8b937a4a9a59ef8b9fa7b938402a65e6aa3f30f";
 static volatile Intent pending;
 static volatile boolean preparing;
 static WeakReference<MainActivity> visible=new WeakReference<>(null);
 android.content.SharedPreferences prefs;
 TextView statusView;
 Button install;
 Button cancel;
 static void changed(){
  MainActivity a=visible.get();
  if(a!=null) a.runOnUiThread(()->{a.refresh();a.confirmPending();});
 }
 public void onCreate(Bundle b){
  super.onCreate(b);
  prefs=getSharedPreferences("diagnostic",0);
  if(prefs.getBoolean("busy",false)&&!preparing){
   int state=prefs.getInt("status",-99);
   PackageInstaller.SessionInfo info=getPackageManager().getPackageInstaller().getSessionInfo(prefs.getInt("session",-1));
   if(state==-97&&(info==null||!info.isSealed()))
    prefs.edit().putBoolean("busy",false).putInt("status",-98).putString("message","Предыдущий диалог или подготовка прерваны. Повторите установку.").commit();
  }
  LinearLayout content=new LinearLayout(this);
  content.setOrientation(1);content.setPadding(28,36,28,24);content.setBackgroundColor(Color.rgb(250,247,238));
  TextView title=new TextView(this);title.setText("Проверка установки Джарвиса");title.setTextSize(24);title.setTextColor(Color.rgb(16,63,38));content.addView(title);
  TextView info=new TextView(this);info.setText("\nВнутри находится Джарвис 0.4.3. Этот установщик покажет причину отказа Android. Он не удаляет старое приложение и его данные.\n");info.setTextSize(16);content.addView(info);
  install=new Button(this);install.setText("Установить новую версию");install.setOnClickListener(v->begin());content.addView(install);
  cancel=new Button(this);cancel.setText("Отменить проверку");cancel.setOnClickListener(v->{
   try{
    getPackageManager().getPackageInstaller().abandonSession(prefs.getInt("session",-1));
    pending=null;prefs.edit().putInt("status",PackageInstaller.STATUS_FAILURE_ABORTED).putString("message","Проверка отменена пользователем").putBoolean("busy",false).commit();refresh();
   }catch(Exception e){failure(e);}
  });content.addView(cancel);
  Button share=new Button(this);share.setText("Отправить отчёт");share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,report());startActivity(Intent.createChooser(i,"Отправить отчёт"));});content.addView(share);
  statusView=new TextView(this);statusView.setTextSize(16);statusView.setTextColor(Color.rgb(16,63,38));
  ScrollView scroll=new ScrollView(this);scroll.addView(statusView);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(content);refresh();
 }
 public void onResume(){super.onResume();visible=new WeakReference<>(this);refresh();confirmPending();}
 public void onPause(){visible.clear();super.onPause();}
 void confirmPending(){
  Intent i=pending;
  if(i!=null&&visible.get()==this){pending=null;try{startActivity(i);}catch(Exception e){failure(e);}}
 }
 String statusName(int s){
  switch(s){
   case PackageInstaller.STATUS_SUCCESS:return "Установка завершена";
   case PackageInstaller.STATUS_PENDING_USER_ACTION:return pending==null?"Ожидание результата установки Android":"Подтвердите установку в Android";
   case PackageInstaller.STATUS_FAILURE_CONFLICT:return "Конфликт с установленным приложением";
   case PackageInstaller.STATUS_FAILURE_BLOCKED:return "Установка заблокирована системой";
   case PackageInstaller.STATUS_FAILURE_INVALID:return "Android отклонил APK";
   case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:return "Несовместимость с устройством";
   case PackageInstaller.STATUS_FAILURE_STORAGE:return "Недостаточно места";
   case PackageInstaller.STATUS_FAILURE_ABORTED:return "Установка отменена";
   case -99:return "Установка ещё не запускалась";
   case -98:return "Ошибка установщика";
   case -97:return "Подготовка APK";
   default:return "Ошибка установки";
  }
 }
 String report(){
  int s=prefs.getInt("status",-99);
  return "Проверка установки Джарвиса\nВерсия APK: 0.4.3\nSHA256: "+APK_SHA+"\nУстройство: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\nАрхитектура: "+String.join(",",Build.SUPPORTED_ABIS)+"\nСессия: "+prefs.getInt("session",-1)+"\nСтатус: "+s+" — "+statusName(s)+"\nОтвет Android:\n"+prefs.getString("message","");
 }
 void refresh(){statusView.setText("\n"+report());install.setEnabled(!prefs.getBoolean("busy",false));cancel.setEnabled(prefs.getBoolean("busy",false)&&!preparing&&prefs.getInt("session",-1)>=0);}
 void failure(Exception e){
  prefs.edit().putInt("status",-98).putString("message",e.getClass().getSimpleName()+": "+e.getMessage()).putBoolean("busy",false).commit();changed();
 }
 void begin(){
  if(prefs.getBoolean("busy",false))return;
  if(!getPackageManager().canRequestPackageInstalls()){
   prefs.edit().putString("message","Разрешите установку из этого приложения, вернитесь сюда и нажмите кнопку установки ещё раз.").apply();refresh();
   try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception e){failure(e);}return;
  }
  pending=null;preparing=true;
  prefs.edit().putBoolean("busy",true).putInt("status",-97).putString("message","Проверка файла и подготовка установки").commit();refresh();
  new Thread(()->{
   File apk=new File(getCacheDir(),"jarvis.apk");int sessionId=-1;
   try{
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=getAssets().open("jarvis.apk");OutputStream out=new FileOutputStream(apk)){
     byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);digest.update(buf,0,n);}
    }
    StringBuilder hash=new StringBuilder();for(byte x:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",x&255));
    if(!APK_SHA.equals(hash.toString()))throw new IOException("SHA256 APK не совпадает");
    PackageInstaller installer=getPackageManager().getPackageInstaller();
    int old=prefs.getInt("session",-1);
    if(old>=0)try{installer.abandonSession(old);}catch(Exception ignored){}
    PackageInstaller.SessionParams p=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    p.setAppPackageName("ru.pulat.jarvis");p.setSize(apk.length());
    if(Build.VERSION.SDK_INT>=31)p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
    sessionId=installer.createSession(p);prefs.edit().putInt("session",sessionId).commit();
    try(PackageInstaller.Session session=installer.openSession(sessionId);
        InputStream in=new FileInputStream(apk);
        OutputStream out=session.openWrite("base.apk",0,apk.length())){
     byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);session.fsync(out);
    }
    Intent callback=new Intent(this,InstallResult.class).setAction(getPackageName()+".INSTALL_RESULT");
    PendingIntent pi=PendingIntent.getBroadcast(this,sessionId,callback,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
    try(PackageInstaller.Session session=installer.openSession(sessionId)){session.commit(pi.getIntentSender());}
   }catch(Exception e){
    if(sessionId>=0)try{getPackageManager().getPackageInstaller().abandonSession(sessionId);}catch(Exception ignored){}
    failure(e);
   }finally{preparing=false;apk.delete();changed();}
  },"apk-install-check").start();
 }
}
