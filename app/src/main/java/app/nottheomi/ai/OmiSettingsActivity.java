package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

/** Discovery never connects or records. Controls borrow only the live capture connection. */
public final class OmiSettingsActivity extends Activity {
    public static final String CONNECT_AFTER_SELECTION="connect_after_selection";
    private boolean connectAfterSelection, initialScan;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> seen=new HashSet<>();
    private LinearLayout page, devices;
    private TextView selected, scanStatus, live, led, desired, presses;
    private Button scanButton, forgetButton, readButton, applyButton, singleButton, doubleButton, batteryButton, languageButton;
    private SeekBar brightness;
    private OmiBle scanner;
    private boolean resumed, scanning;
    private int scanGeneration;
    private final Runnable ticker=new Runnable(){public void run(){if(resumed){refresh();main.postDelayed(this,750);}}};

    public static SharedPreferences preferences(Context context){return context.getSharedPreferences("omi",MODE_PRIVATE);}
    public static boolean permitted(Context context){
        if(Build.VERSION.SDK_INT>=31)return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            &&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    public static String[] permissions(){return Build.VERSION.SDK_INT>=31
        ?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}
        :new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION};}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        connectAfterSelection=getIntent().getBooleanExtra(CONNECT_AFTER_SELECTION,false);
        initialScan=connectAfterSelection&&state==null;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=Ui.page(this);setContentView(root);root.addView(Ui.backBar(this,null));root.addView(Ui.divider(this));
        ScrollView scroll=new ScrollView(this);page=Ui.column(this);page.setPadding(dp(20),dp(20),dp(20),dp(28));scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        page.addView(Ui.title(this,connectAfterSelection?"Connect your Omi":"Your Omi","Omi"));
        label("Close other Omi apps before connecting.",15,Ui.MUTED);
        selected=label("",17,Ui.INK);selected.setTypeface(Ui.font(this,true));
        scanButton=addButton("Find nearby Omi",this::scan);Ui.style(this,scanButton,Ui.Style.DARK);
        forgetButton=addButton("Forget this Omi",()->{
            if(OmiCaptureService.active)return;
            preferences(this).edit().remove("address").remove("name").apply();refresh();
        });
        Ui.style(this,forgetButton,Ui.Style.DANGER);
        batteryButton=addButton("Allow background recording",()->Battery.request(this,0));Ui.icon(this,batteryButton,R.drawable.ic_settings,Ui.INK);
        scanStatus=label(connectAfterSelection?"Pick your Omi. Recording starts once it connects.":"Pick your Omi, then tap Connect Omi on Home.",14,Ui.MUTED);
        devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);page.addView(devices);
        live=label("Disconnected",14,Ui.MUTED);
        section("Light");
        label("While recording, if your Omi supports it.",14,Ui.MUTED);
        led=label("",15,Ui.INK);readButton=addButton("Read current brightness",OmiCaptureService::readLedBrightness);
        brightness=new SeekBar(this);brightness.setMax(100);brightness.setProgress(50);brightness.setContentDescription("Desired Omi light brightness");page.addView(brightness);
        desired=label("Brightness: 50%",15,Ui.INK);
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){desired.setText("Brightness: "+value+"%");}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        applyButton=addButton("Apply brightness",()->{
            int value=brightness.getProgress();
            new AlertDialog.Builder(this).setTitle("Set Omi light to "+value+"%?")
                .setMessage((value==0?"This requests minimum brightness; firmware-owned indicators may remain. ":"")+"Audio recording continues, with a visible phone notification. Readback confirms the current value, not persistence after power-off.")
                .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->OmiCaptureService.setLedBrightness(value)).show();
        });
        section("Button");
        label("What a press does while recording. Set it before you start.",14,Ui.MUTED);
        singleButton=addButton("",()->chooseAction("single_action","Single press","bookmark"));
        doubleButton=addButton("",()->chooseAction("double_action","Double press","stop"));
        presses=label("",14,Ui.MUTED);
        section("Transcripts");
        label("The language you speak. Whisper rewrites each saved recording in it; the live draft stays Portuguese.",14,Ui.MUTED);
        languageButton=addButton("",this::chooseLanguage);
        refresh();
    }
    @Override protected void onResume(){super.onResume();resumed=true;main.post(ticker);if(initialScan){initialScan=false;main.post(this::scan);}}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(ticker);stopScan();super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size,int color){TextView t=Ui.text(this,text,size,color,false);t.setPadding(0,dp(8),0,dp(4));page.addView(t);return t;}
    private void section(String title){Ui.gap(page,28);page.addView(Ui.divider(this));Ui.gap(page,20);page.addView(Ui.text(this,title,22,Ui.INK,true));}
    private Button button(String title,Runnable action){Button b=Ui.button(this,title,Ui.Style.QUIET,v->action.run());b.setPadding(0,0,dp(8),0);return b;}
    private Button addButton(String title,Runnable action){Button b=button(title,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);page.addView(b,p);return b;}
    private void refresh(){
        SharedPreferences p=preferences(this);String address=p.getString("address","");
        selected.setText(address.isEmpty()?"No Omi selected yet":p.getString("name","Omi"));
        boolean active=OmiCaptureService.active;
        scanButton.setEnabled(!active);scanButton.setText(scanning?"Stop scanning":"Find nearby Omi");
        forgetButton.setEnabled(!active&&!address.isEmpty());
        live.setText(OmiCaptureService.transport+(OmiCaptureService.battery>=0?" · Battery "+OmiCaptureService.battery+"%":""));
        OmiBle.LedState value=OmiCaptureService.ledState;
        led.setText(value==null?"Connect to adjust.":value.message+(value.brightness>=0?" · now "+value.brightness+"%":""));
        readButton.setEnabled(active&&value!=null&&!value.busy);
        boolean writable=active&&value!=null&&value.supported&&!value.busy&&value.brightness>=0;
        applyButton.setEnabled(writable);brightness.setEnabled(writable);
        singleButton.setText("Single press · "+actionLabel(p.getString("single_action","bookmark")));
        doubleButton.setText("Double press · "+actionLabel(p.getString("double_action","stop")));
        singleButton.setEnabled(!active);doubleButton.setEnabled(!active);
        presses.setText(OmiCaptureService.lastButton);
        languageButton.setText("Language · "+languageLabel(language(this)));
        batteryButton.setVisibility(Battery.unrestricted(this)?android.view.View.GONE:android.view.View.VISIBLE);
    }
    /** Whisper's language for saved transcripts: "pt" (default), "en", or "auto" (detected per 30 s). */
    static String language(Context context){
        String value=preferences(context).getString("language","pt");
        return "en".equals(value)||"auto".equals(value)?value:"pt";
    }
    private static String languageLabel(String value){return "en".equals(value)?"English":"auto".equals(value)?"Detect (mixed languages)":"Portuguese";}
    private void chooseLanguage(){
        String[] values={"pt","en","auto"}, labels={"Portuguese","English","Detect (mixed languages, less accurate)"};
        String current=language(this);int index=0;for(int i=0;i<values.length;i++)if(values[i].equals(current))index=i;
        new AlertDialog.Builder(this).setTitle("Transcript language").setSingleChoiceItems(labels,index,(d,w)->{preferences(this).edit().putString("language",values[w]).apply();d.dismiss();refresh();}).setNegativeButton("Cancel",null).show();
    }
    private static String actionLabel(String value){return "bookmark".equals(value)?"Bookmark":"stop".equals(value)?"Stop & save":"No action";}
    private void chooseAction(String key,String title,String fallback){
        String[] values={"bookmark","stop","ignore"}, labels={"Bookmark in transcript","Stop & save","No action"};
        String current=preferences(this).getString(key,fallback);int index=0;for(int i=0;i<values.length;i++)if(values[i].equals(current))index=i;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,index,(d,w)->{preferences(this).edit().putString(key,values[w]).apply();d.dismiss();refresh();}).setNegativeButton("Cancel",null).show();
    }
    private void scan(){
        if(scanning){stopScan();scanStatus.setText("Scan stopped.");return;}
        if(OmiCaptureService.active){scanStatus.setText("Stop & save before changing devices.");return;}
        if(!permitted(this)){
            new AlertDialog.Builder(this).setTitle(Build.VERSION.SDK_INT>=31?"Find your Omi":"Allow Bluetooth discovery")
                .setMessage(Build.VERSION.SDK_INT>=31?"Nearby devices permission is needed to find and connect to your Omi. It is not used for location tracking.":"Android 8–11 requires Location permission and Location enabled for Bluetooth scans. This app does not collect your location.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->requestPermissions(permissions(),31)).show();return;
        }
        BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){scanStatus.setText("Bluetooth LE is unavailable on this device.");return;}
        try{
            if(!adapter.isEnabled()){scanStatus.setText("Turn Bluetooth on, then scan again.");startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
            if(Build.VERSION.SDK_INT<31){LocationManager location=getSystemService(LocationManager.class);if(location==null||(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!location.isProviderEnabled(LocationManager.NETWORK_PROVIDER))){scanStatus.setText("Android requires Location enabled for discovery. Enable it in phone settings, then scan again.");return;}}
            int generation=++scanGeneration;scanning=true;seen.clear();devices.removeAllViews();
            scanner=new OmiBle(this,new OmiBle.Listener(){
                public void onDevice(String address,String name){main.post(()->{
                    if(!resumed||!scanning||generation!=scanGeneration||!seen.add(address))return;
                    Button choose=button(name,()->{
                        if(OmiCaptureService.active)return;
                        preferences(OmiSettingsActivity.this).edit().putString("address",address).putString("name",name).apply();stopScan();
                        if(connectAfterSelection){setResult(RESULT_OK);finish();}
                        else{scanStatus.setText("Saved. Tap Connect Omi on Home to start.");refresh();}
                    });choose.setContentDescription("Choose "+name+" "+address);Ui.icon(OmiSettingsActivity.this,choose,R.drawable.ic_plus,Ui.INK);devices.addView(choose,new LinearLayout.LayoutParams(-1,-2));
                });}
                public void onPcm(short[] samples){}
                public void onStatus(String status){main.post(()->{if(resumed&&generation==scanGeneration)scanStatus.setText(status);});}
                public void onGap(){}
                public void onButton(int event){}
            });
            scanner.scan();scanStatus.setText("Looking for your Omi…");refresh();
            main.postDelayed(()->{if(scanning&&generation==scanGeneration){stopScan();scanStatus.setText(seen.isEmpty()?"No Omi found. Wake it, keep it close and try again.":"Pick your Omi above.");}},15000);
        }catch(SecurityException denied){stopScan();scanStatus.setText("Bluetooth permission was removed. Allow Nearby devices in app settings, then try again.");}
        catch(RuntimeException failure){stopScan();scanStatus.setText("Bluetooth discovery could not start. Check Nearby devices permission and Bluetooth.");}
    }
    private void stopScan(){++scanGeneration;scanning=false;if(scanner!=null){scanner.stop();scanner=null;}if(scanButton!=null)refresh();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==31){if(permitted(this)){initialScan=true;if(resumed){initialScan=false;main.post(this::scan);}}else scanStatus.setText("Permission denied. Enable it in app settings to use your Omi.");}}
}
