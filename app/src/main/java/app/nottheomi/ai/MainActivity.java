package app.nottheomi.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One local-first UI, no accounts, servers, trackers or hidden destinations. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page, content, libraryRows, historyRows;
    private TextView stateLabel,timer,preview,storageLabel,finalText,historyHint,liveTitle;
    private Button recordButton, sourceButton;
    private ImageButton deviceButton;
    private LinearLayout liveSection;
    private int recordStyle=-1;
    private boolean resumeStart, wasActive;
    private int historyGeneration;
    private long displayRevision=-1;
    private boolean displayWasOmi;
    private Meter meter;
    private LocalPlayback playback;
    private boolean library, resumed, destroyed, ready, startPending;
    private int viewGeneration, searchGeneration;
    private String query="", selectedId, exportId, exportKind;
    private long refinementRevision = -1;
    private TextView detailTranscript, detailRefinement, refiningLine;
    /** The recording shown in detail, for the live refinement line. */
    private Recordings.Session detailSession;
    private Button detailRetry;
    private final Runnable ticker=new Runnable(){public void run(){if(resumed){refreshCapture();refreshRefinement();refreshProgress();main.postDelayed(this,250);}}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); playback=new LocalPlayback(this);
        if(saved!=null){library=saved.getBoolean("library");query=saved.getString("query","");exportId=saved.getString("exportId");exportKind=saved.getString("exportKind");}
        draw();
        io.execute(() -> {
            try { if(!captureActive())Recordings.get(this).recoverInterrupted();
                RefinementJobService.schedule(this);
                main.post(() -> {if(destroyed)return;ready=true;if(library)loadLibrary();else{refreshCapture();loadHomeHistory();}resumeStart();openFromIntent();});
            } catch(Exception error){main.post(() -> error("Private library could not be opened. No files were deleted. Close and reopen the app."));}
        });
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(ready)openFromIntent();}
    /** From the "Transcript ready" notification: open that recording, or the library for several. */
    private void openFromIntent(){
        Intent intent=getIntent();String id=intent.getStringExtra(ReadyNotifier.EXTRA_OPEN);boolean all=intent.getBooleanExtra(ReadyNotifier.EXTRA_LIBRARY,false);
        intent.removeExtra(ReadyNotifier.EXTRA_OPEN);intent.removeExtra(ReadyNotifier.EXTRA_LIBRARY);
        if(id!=null)detail(id);else if(all){library=true;draw();}
    }
    @Override protected void onResume(){super.onResume();resumed=true;ReadyNotifier.clear(this);main.removeCallbacks(ticker);main.post(ticker);if(ready){if(library)loadLibrary();else loadHomeHistory();}main.post(this::resumeStart);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(ticker);playback.stop();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;viewGeneration++;playback.stop();io.shutdown();main.removeCallbacks(ticker);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("library",library);out.putString("query",query);out.putString("exportId",exportId);out.putString("exportKind",exportKind);super.onSaveInstanceState(out);}

    private int dp(float value){return Ui.dp(this,value);}
    private TextView text(String value,int size,int color,boolean bold){return Ui.text(this,value,size,color,bold);}
    private LinearLayout column(){return Ui.column(this);}
    private void gap(LinearLayout target,int height){Ui.gap(target,height);}
    private Button button(String label,Ui.Style style,View.OnClickListener click){return Ui.button(this,label,style,click);}
    private void addButton(LinearLayout target,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(12);target.addView(b,p);}

    private void draw(){
        viewGeneration++;selectedId=null;playback.stop();recordStyle=-1;
        page=Ui.page(this);setContentView(page);
        page.addView(Ui.header(this,Ui.iconButton(this,R.drawable.ic_info,"About and privacy",Ui.INK,v -> about())));
        page.addView(Ui.divider(this));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();content.setPadding(dp(20),dp(20),dp(20),dp(28));scroll.addView(content);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(library)drawLibrary();else drawCapture();
        page.addView(Ui.divider(this));
        LinearLayout tabs=Ui.row(this);tabs.setBackgroundColor(Ui.SURFACE);tabs.setPadding(dp(8),dp(4),dp(8),dp(4));
        tabs.addView(tab("Home",R.drawable.ic_home,!library,v -> {library=false;draw();}),new LinearLayout.LayoutParams(0,dp(60),1));
        tabs.addView(tab("Library",R.drawable.ic_library,library,v -> {library=true;draw();}),new LinearLayout.LayoutParams(0,dp(60),1));
        page.addView(tabs);
    }
    private View tab(String label,int icon,boolean active,View.OnClickListener click){
        LinearLayout t=column();t.setGravity(Gravity.CENTER);t.setOnClickListener(click);t.setContentDescription(label);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7),null,Ui.shape(this,Color.WHITE,4)));
        ImageView i=new ImageView(this);i.setImageResource(icon);i.setImageTintList(ColorStateList.valueOf(active?Ui.INK:Ui.MUTED));t.addView(i,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView l=text(label,13,active?Ui.INK:Ui.MUTED,active);l.setPadding(dp(6),dp(2),dp(6),dp(2));if(active)l.setBackgroundColor(Ui.MINT);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.topMargin=dp(4);t.addView(l,lp);return t;
    }
    private static boolean captureActive(){return CaptureService.active||OmiCaptureService.active;}
    private boolean omiSource(){return OmiCaptureService.active||(!CaptureService.active&&!"phone".equals(OmiSettingsActivity.preferences(this).getString("source","omi")));}
    private void chooseSource(){
        if(captureActive()||startPending)return;
        new AlertDialog.Builder(this).setTitle("Recording source")
            .setSingleChoiceItems(new String[]{"Omi wearable","Phone microphone"},omiSource()?0:1,(d,w)->{
                OmiSettingsActivity.preferences(this).edit().putString("source",w==0?"omi":"phone").apply();d.dismiss();draw();
            }).setNegativeButton("Cancel",null).show();
    }
    private void drawCapture(){
        displayRevision=-1;wasActive=captureActive();
        LinearLayout panel=column();panel.setBackground(Ui.shape(this,Ui.INK,10));panel.setPadding(dp(20),dp(8),dp(8),dp(20));
        LinearLayout top=Ui.row(this);
        sourceButton=button("",Ui.Style.QUIET,v -> chooseSource());sourceButton.setTextSize(14);sourceButton.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{Ui.ON_DARK_MUTED,Ui.ON_DARK_MUTED}));sourceButton.setPadding(0,0,dp(8),0);sourceButton.setMinHeight(dp(44));sourceButton.setContentDescription("Recording source");
        android.graphics.drawable.Drawable chevron=getDrawable(R.drawable.ic_chevron_down);if(chevron!=null){chevron=chevron.mutate();chevron.setTint(Ui.ON_DARK_MUTED);chevron.setBounds(0,0,dp(18),dp(18));sourceButton.setCompoundDrawablesRelative(null,null,chevron,null);sourceButton.setCompoundDrawablePadding(dp(4));}
        top.addView(sourceButton,new LinearLayout.LayoutParams(-2,dp(44)));top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        deviceButton=Ui.iconButton(this,R.drawable.ic_settings,"Omi device settings",Ui.ON_DARK_MUTED,v -> startActivity(new Intent(this,OmiSettingsActivity.class)));
        top.addView(deviceButton,new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(top);
        timer=text("00:00",56,Ui.ON_DARK,true);timer.setLetterSpacing(-0.03f);gap(panel,4);panel.addView(timer);
        stateLabel=text("Opening library…",15,Ui.ON_DARK_MUTED,false);gap(panel,6);panel.addView(stateLabel);
        meter=new Meter();gap(panel,14);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(36));mp.rightMargin=dp(12);panel.addView(meter,mp);
        recordButton=button("Connect Omi",Ui.Style.PRIMARY,v -> toggleRecording());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(56));rp.topMargin=dp(18);rp.rightMargin=dp(12);panel.addView(recordButton,rp);
        content.addView(panel);
        liveSection=column();gap(liveSection,32);liveTitle=text("Live",22,Ui.INK,true);liveSection.addView(liveTitle);gap(liveSection,12);
        finalText=text("",18,Ui.INK,false);finalText.setTextIsSelectable(true);finalText.setLineSpacing(dp(4),1f);liveSection.addView(finalText);
        preview=text("",18,Ui.MUTED,false);preview.setTextIsSelectable(true);preview.setLineSpacing(dp(4),1f);liveSection.addView(preview);
        content.addView(liveSection);
        gap(content,32);LinearLayout recentHead=Ui.row(this);
        recentHead.addView(text("Recent",22,Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button all=button("See all",Ui.Style.QUIET,v -> {library=true;draw();});all.setTextSize(14);all.setMinHeight(dp(40));recentHead.addView(all,new LinearLayout.LayoutParams(-2,dp(40)));
        content.addView(recentHead);
        refiningLine=text("",14,Ui.MUTED,false);refiningLine.setPadding(0,dp(10),0,0);refiningLine.setVisibility(View.GONE);
        refiningLine.setOnClickListener(v->{RefinementProgress.Snapshot p=RefinementProgress.get();if(p.id!=null)detail(p.id);});content.addView(refiningLine);
        historyHint=text("",15,Ui.MUTED,false);historyHint.setPadding(0,dp(12),0,0);content.addView(historyHint);
        historyRows=column();content.addView(historyRows);
        refreshCapture();if(ready)loadHomeHistory();
    }
    private static void setText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void refreshCapture(){
        if(library||selectedId!=null||timer==null)return;
        boolean active=captureActive(), omi=omiSource();
        String state=omi?OmiCaptureService.state:CaptureService.state;
        if(active)startPending=false;
        long started=omi?OmiCaptureService.startedAt:CaptureService.startedAt;
        setText(timer,duration(active&&started>0?SystemClock.elapsedRealtime()-started:0));
        sourceButton.setEnabled(!active&&!startPending);
        deviceButton.setVisibility(omi?View.VISIBLE:View.GONE);deviceButton.setEnabled(!startPending);
        String name=OmiSettingsActivity.preferences(this).getString("name","Omi wearable");
        setText(sourceButton,omi?(name.isEmpty()?"Omi wearable":name)+(active&&OmiCaptureService.battery>=0?" · "+OmiCaptureService.battery+"%":""):"Phone microphone");
        setText(stateLabel,!ready?"Opening library…":startPending?(omi?"Connecting…":"Starting…"):state==null||state.isEmpty()||"Stopped".equals(state)?(omi?"Ready to connect.":"Ready to record."):state);
        recordButton.setEnabled(ready&&!startPending);
        setText(recordButton,active?(omi?(started>0?"Stop & save":"Cancel"):"Stop & save"):(omi?"Connect Omi":"Start recording"));
        int style=active?1:0;
        if(style!=recordStyle){recordStyle=style;Ui.style(this,recordButton,active?Ui.Style.RECORDING:Ui.Style.PRIMARY);Ui.icon(this,recordButton,active?R.drawable.ic_stop:R.drawable.ic_mic,active?Color.WHITE:Ui.MINT_INK);}
        LiveTranscript.Snapshot display=(omi?OmiCaptureService.display:CaptureService.display).snapshot();
        if(display.revision!=displayRevision||omi!=displayWasOmi){
            displayRevision=display.revision;displayWasOmi=omi;
            setText(finalText,display.finalized);
        }
        finalText.setVisibility(display.finalized.isEmpty()?View.GONE:View.VISIBLE);
        setText(liveTitle,active?"Live":"Last session");
        setText(preview,active?(display.partial.isEmpty()?(started>0?"Listening…":omi?"Waiting for Omi audio…":"Preparing speech…"):display.partial):"");
        preview.setVisibility(active?View.VISIBLE:View.GONE);
        liveSection.setVisibility(active||!display.finalized.isEmpty()?View.VISIBLE:View.GONE);
        meter.level=active?(omi?OmiCaptureService.level:CaptureService.level):0;meter.active=active;meter.setVisibility(active?View.VISIBLE:View.GONE);meter.invalidate();
        if(wasActive&&!active&&ready)loadHomeHistory();
        wasActive=active;
    }
    private void loadHomeHistory(){
        if(destroyed||library||selectedId!=null||!ready)return;
        final int generation=viewGeneration, request=++historyGeneration;
        io.execute(()->{
            try{
                List<Recordings.Session> rows=Recordings.get(this).recent(3);
                main.post(()->{
                    if(destroyed||library||selectedId!=null||generation!=viewGeneration||request!=historyGeneration)return;
                    historyRows.removeAllViews();
                    setText(historyHint,rows.isEmpty()?"Nothing saved yet.":"");
                    historyHint.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
                    for(Recordings.Session s:rows){gap(historyRows,8);historyRows.addView(Ui.divider(this));historyRows.addView(sessionRow(s,2));}
                });
            }catch(Exception e){main.post(()->{if(!destroyed&&!library&&selectedId==null&&generation==viewGeneration&&request==historyGeneration){setText(historyHint,"History could not be read. Your files were not changed.");historyHint.setVisibility(View.VISIBLE);}});}
        });
    }
    /** One saved recording: title, one meta line, transcript excerpt. Rows, not cards. */
    private View sessionRow(Recordings.Session s,int excerptLines){
        LinearLayout r=column();r.setPadding(0,dp(14),0,dp(14));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7),null,Ui.shape(this,Color.WHITE,0)));
        LinearLayout top=Ui.row(this);top.addView(text(s.title,17,Ui.INK,true),new LinearLayout.LayoutParams(0,-2,1));
        String chip=statusChip(s);if(chip!=null){LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=dp(10);top.addView(Ui.chip(this,chip,alertChip(chip)),cp);}
        r.addView(top);
        TextView meta=text(date(s.createdAt)+" · "+duration(s.durationMs),13,Ui.MUTED,false);meta.setPadding(0,dp(6),0,0);r.addView(meta);
        if(!s.text.isEmpty()){TextView ex=text(s.text,15,Ui.INK,false);ex.setMaxLines(excerptLines);ex.setEllipsize(android.text.TextUtils.TruncateAt.END);ex.setPadding(0,dp(8),0,0);r.addView(ex);}
        r.setContentDescription("Open "+s.title);r.setOnClickListener(v->detail(s.id));return r;
    }
    private static String statusChip(Recordings.Session s){
        if("complete".equals(s.transcriptState)&&Recordings.SMALL.equals(s.model)){
            RefinementProgress.Snapshot p=RefinementProgress.get();
            return s.id.equals(p.id)&&p.accurate?"Improving "+p.percent()+"%":"Quick";
        }
        if("pending".equals(s.transcriptState)&&!"recording".equals(s.status)){
            RefinementProgress.Snapshot p=RefinementProgress.get();
            if(s.id.equals(p.id))return "Refining "+p.percent()+"%";
            return "Queued · "+(s.bytes<=0?0:s.refinedBytes*100/s.bytes)+"%";
        }
        switch(s.status){
            case "recording": return "Recording";
            case "audio_only": return "Audio only";
            case "cancelled": return "Cancelled";
            case "error": return "Error";
            case "interrupted": return "Interrupted";
            default: return "pending".equals(s.transcriptState)?"Refining":null;
        }
    }
    private static boolean alertChip(String chip){return "Recording".equals(chip)||"Error".equals(chip)||"Interrupted".equals(chip);}
    private void continueStart(){resumeStart=true;if(resumed)main.post(this::resumeStart);}
    private void resumeStart(){if(!resumeStart||!resumed||!ready||destroyed)return;resumeStart=false;if(!captureActive())toggleRecording();}
    private void toggleRecording(){
        if(captureActive()){startService(new Intent(this,OmiCaptureService.active?OmiCaptureService.class:CaptureService.class).setAction(OmiCaptureService.active?OmiCaptureService.ACTION_STOP:CaptureService.ACTION_STOP));return;}
        if(!ready||startPending)return;
        if(omiSource()){
            if(OmiSettingsActivity.preferences(this).getString("address","").isEmpty()){
                startActivityForResult(new Intent(this,OmiSettingsActivity.class).putExtra(OmiSettingsActivity.CONNECT_AFTER_SELECTION,true),30);return;
            }
            if(!OmiSettingsActivity.permitted(this)){requestPermissions(OmiSettingsActivity.permissions(),12);return;}
        }
        if(!omiSource()&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            new AlertDialog.Builder(this).setTitle("Use your phone microphone?").setMessage("Audio and transcripts stay encrypted on this phone. Allowing permission starts this recording. Stop from the app or its notification.")
                .setNegativeButton("Not now",null).setPositiveButton("Allow microphone",(d,w)->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10)).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!getPreferences(0).getBoolean("notificationAsked",false)){
            getPreferences(0).edit().putBoolean("notificationAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;
        }
        confirmNotificationAccess();
    }
    private boolean notificationsAvailable(){
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel(omiSource()?"omi_capture":"phone_capture");
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=android.app.NotificationManager.IMPORTANCE_NONE);
    }
    /** Asks once to run unrestricted, so Doze and OEM battery savers don't stop long recordings. */
    private boolean askBackgroundRunning(){
        if(Battery.unrestricted(this)||getPreferences(0).getBoolean("batteryAsked",false))return false;
        getPreferences(0).edit().putBoolean("batteryAsked",true).apply();
        new AlertDialog.Builder(this).setTitle("Keep recording with the screen off?")
            .setMessage("Android may pause GVoice to save battery, which can cut long recordings short. Allow it to run in the background.")
            .setNegativeButton("Not now",(d,w)->startCapture())
            .setPositiveButton("Allow",(d,w)->{if(!Battery.request(this,31))startCapture();})
            .setOnCancelListener(d->startCapture()).show();
        return true;
    }
    private void confirmNotificationAccess(){
        if(destroyed||!resumed||captureActive()||startPending)return;
        if(notificationsAvailable()){if(!askBackgroundRunning())startCapture();return;}
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Notification Stop is unavailable")
            .setMessage(omiSource()?"Enable notifications before recording from Omi. The phone must show recording status and a Stop control.":"Notifications are disabled. Recording can continue outside the app, but you must return here to Stop & save. Enable notifications for a visible Stop control.")
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Settings",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        if(!omiSource())dialog.setPositiveButton("Record anyway",(d,w)->startCapture());
        dialog.show();
    }
    private void startCapture(){
        if(destroyed||!resumed||captureActive()||startPending)return;
        playback.stop();startPending=true;refreshCapture();
        boolean omi=omiSource();
        try {startForegroundService(new Intent(this,omi?OmiCaptureService.class:CaptureService.class).setAction(omi?OmiCaptureService.ACTION_START:CaptureService.ACTION_START));}
        catch(RuntimeException e){startPending=false;error("Android could not start recording. Keep the app open and check permissions for the selected source.");}
        main.postDelayed(()->{if(!destroyed){startPending=false;refreshCapture();}},2500);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==10){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)continueStart();else error("Microphone permission is required to record. You can still browse your library.");}else if(code==11)continueStart();else if(code==12){if(OmiSettingsActivity.permitted(this))continueStart();else error("Nearby devices permission is required for Omi. Your phone microphone will not be used instead.");}}

    private void drawLibrary(){
        content.addView(Ui.title(this,"Your library","library"));
        storageLabel=text("",14,Ui.MUTED,false);storageLabel.setPadding(0,dp(10),0,0);content.addView(storageLabel);gap(content,20);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search transcripts");search.setTextSize(16);search.setTypeface(Ui.font(this,false));search.setTextColor(Ui.INK);search.setHintTextColor(Ui.MUTED);
        GradientDrawable field=Ui.shape(this,Ui.SURFACE,4);field.setStroke(Math.max(1,dp(1)),Ui.LINE);search.setBackground(field);search.setPadding(dp(14),0,dp(14),0);
        android.graphics.drawable.Drawable lens=getDrawable(R.drawable.ic_search);if(lens!=null){lens=lens.mutate();lens.setTint(Ui.MUTED);lens.setBounds(0,0,dp(20),dp(20));search.setCompoundDrawablesRelative(lens,null,null,null);search.setCompoundDrawablePadding(dp(10));}
        search.setText(query);search.setContentDescription("Search recordings");content.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){} public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();final int g=++searchGeneration;main.postDelayed(()->{if(g==searchGeneration&&library)loadLibrary();},250);}public void afterTextChanged(Editable s){}});
        gap(content,8);libraryRows=column();content.addView(libraryRows);if(ready)loadLibrary();else{gap(libraryRows,12);libraryRows.addView(text("Opening library…",15,Ui.MUTED,false));}
    }
    private void loadLibrary(){
        if(!library||selectedId!=null||destroyed)return;
        final int generation=viewGeneration, search=++searchGeneration;final String term=query;
        io.execute(()->{
            try {Recordings db=Recordings.get(this);List<Recordings.Session> rows=db.list(term);long bytes=db.totalBytes();
                main.post(()->{if(destroyed||!library||generation!=viewGeneration||search!=searchGeneration||selectedId!=null)return;
                    storageLabel.setText(size(bytes)+" of 2 GB used");
                    libraryRows.removeAllViews();
                    if(rows.isEmpty()){gap(libraryRows,28);libraryRows.addView(text(term.isEmpty()?"Nothing here yet.":"No matches.",20,Ui.INK,true));TextView hint=text(term.isEmpty()?"Recordings you save will show up here.":"Try another word.",15,Ui.MUTED,false);hint.setPadding(0,dp(8),0,0);libraryRows.addView(hint);}
                    for(Recordings.Session s:rows){gap(libraryRows,8);libraryRows.addView(Ui.divider(this));libraryRows.addView(sessionRow(s,2));}
                });
            }catch(Exception e){main.post(()->error("Could not read the encrypted library. Your files have not been changed."));}
        });
    }
    private void detail(String id){
        playback.stop();selectedId=id;final int generation=++viewGeneration;
        io.execute(()->{try{Recordings.Session s=Recordings.get(this).find(id);main.post(()->{if(!destroyed&&generation==viewGeneration)showDetail(s);});}catch(Exception e){main.post(()->error("This recording could not be opened. No data was changed."));}});
    }
    private void showDetail(Recordings.Session s){
        if(s==null){selectedId=null;draw();return;}
        boolean recording=(s.id.equals(CaptureService.sessionId)&&CaptureService.active)||(s.id.equals(OmiCaptureService.sessionId)&&OmiCaptureService.active);
        ImageButton more=Ui.iconButton(this,R.drawable.ic_more,"More actions",Ui.INK,v->moreActions(s));more.setVisibility(recording?View.INVISIBLE:View.VISIBLE);
        page.removeViewAt(0);page.addView(Ui.backBar(this,more),0);
        content.removeAllViews();
        TextView title=text(s.title,28,Ui.INK,true);title.setLetterSpacing(-0.02f);content.addView(title);
        LinearLayout meta=Ui.row(this);meta.setPadding(0,dp(10),0,0);meta.addView(text(date(s.createdAt)+" · "+duration(s.durationMs),14,Ui.MUTED,false));
        String chip=statusChip(s);if(chip!=null){LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=dp(10);meta.addView(Ui.chip(this,chip,alertChip(chip)),cp);}
        content.addView(meta);
        if(recording){TextView note=text("Still recording. Stop it to play or edit.",16,Ui.INK,false);note.setPadding(0,dp(20),0,0);content.addView(note);addButton(content,button("Go to recorder",Ui.Style.DARK,v->{library=false;draw();}));}
        else {
            LinearLayout actions=Ui.row(this);actions.setPadding(0,dp(22),0,0);
            Button play=button("Play",Ui.Style.PRIMARY,null);Ui.icon(this,play,R.drawable.ic_play,Ui.MINT_INK);
            play.setOnClickListener(v->{if(playback.playing){playback.stop();play.setText("Play");Ui.icon(this,play,R.drawable.ic_play,Ui.MINT_INK);}else {if(captureActive()){error("Stop recording before playback to prevent feedback.");return;}play.setText("Stop");Ui.icon(this,play,R.drawable.ic_stop,Ui.MINT_INK);playback.play(s.id,()->{if(!destroyed){play.setText("Play");Ui.icon(this,play,R.drawable.ic_play,Ui.MINT_INK);}},this::error);}});
            LinearLayout.LayoutParams full=new LinearLayout.LayoutParams(-1,dp(52));actions.addView(play,full);content.addView(actions);
        }
        gap(content,28);content.addView(Ui.divider(this));gap(content,20);
        detailSession=s;
        detailRefinement=text(refinementLabel(s),14,Ui.MUTED,false);detailRefinement.setVisibility(refinementLabel(s).isEmpty()?View.GONE:View.VISIBLE);content.addView(detailRefinement);
        detailRetry=button(retryLabel(s),Ui.Style.QUIET,v->refineAgain(detailSession));detailRetry.setTextSize(14);detailRetry.setPadding(0,0,dp(8),0);detailRetry.setMinHeight(dp(40));
        detailRetry.setVisibility(canRetry(s)?View.VISIBLE:View.GONE);content.addView(detailRetry,new LinearLayout.LayoutParams(-2,dp(40)));
        detailTranscript=text(transcriptText(s),18,Ui.INK,false);detailTranscript.setTextIsSelectable(true);detailTranscript.setLineSpacing(dp(5),1f);detailTranscript.setPadding(0,dp(8),0,0);content.addView(detailTranscript);
        final int generation=viewGeneration;ScrollView scroll=(ScrollView)content.getParent();
        scroll.post(()->{if(!destroyed&&generation==viewGeneration&&s.id.equals(selectedId))scroll.scrollTo(0,0);});
    }
    private void moreActions(Recordings.Session s){
        new AlertDialog.Builder(this).setItems(new String[]{"Export text","Export audio","Original live draft","Rename","Delete","Refine again"},(d,w)->{
            if(w==5){if(canRetry(s))refineAgain(s);else error("Refine it again once the recording is saved.");return;}
            if(w==0)confirmExport(s.id,"text");else if(w==1)confirmExport(s.id,"wav");
            else if(w==2)new AlertDialog.Builder(this).setTitle("Original live draft").setMessage(s.liveText.isEmpty()?"No live draft was saved.":s.liveText).setPositiveButton("Close",null).show();
            else if(w==3)rename(s);else delete(s);
        }).show();
    }
    private void rename(Recordings.Session s){EditText name=new EditText(this);name.setText(s.title);name.setSingleLine();name.setSelectAllOnFocus(true);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});new AlertDialog.Builder(this).setTitle("Rename recording").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{String title=name.getText().toString().trim();if(title.isEmpty()){error("Enter a title.");return;}io.execute(()->{try{Recordings.get(this).rename(s.id,title);main.post(()->{if(!destroyed)detail(s.id);});}catch(Exception e){main.post(()->error("Rename was not saved."));}});}).show();}
    private static String transcriptText(Recordings.Session s){return s.text.isEmpty()?"No speech was recognised. Your saved audio is still available.":s.text;}
    private static boolean canRetry(Recordings.Session s){return !"recording".equals(s.status)&&s.bytes>0&&!"corrupt".equals(s.transcriptState);}
    private static String retryLabel(Recordings.Session s){return "pending".equals(s.transcriptState)?"Start refining over":"Refine again";}
    private String refinementLabel(Recordings.Session s){
        if("complete".equals(s.transcriptState)&&Recordings.SMALL.equals(s.model)){
            RefinementProgress.Snapshot p=RefinementProgress.get();long now=RefinementProgress.clock.getAsLong();
            if(s.id.equals(p.id)&&p.accurate){
                String line=RefinementProgress.describe(p,now).replaceFirst("^Improving · ","Improving with the accurate model · ")+". Showing the quick transcript until it's done.";
                return RefinementProgress.stalled(p,now)?"No progress for "+RefinementProgress.span(now-p.lastChangeAt)+". "+line:line;
            }
            if(!OmiSettingsActivity.betterWhileCharging(this))return "Quick transcript (small model).";
            long done=s.improvingBytes>0&&s.bytes>0?s.improvingBytes*100/s.bytes:0;
            return "Quick transcript (small model). The accurate one is made the next time the phone charges"+(done>0?" · "+done+"% done":"")+".";
        }
        if("complete".equals(s.transcriptState))return "";
        if("pending".equals(s.transcriptState)){
            RefinementProgress.Snapshot p=RefinementProgress.get();long now=RefinementProgress.clock.getAsLong();
            if(s.id.equals(p.id)){
                String line=RefinementProgress.describe(p,now)+". Showing the live draft until it's done.";
                return RefinementProgress.stalled(p,now)?"No progress for "+RefinementProgress.span(now-p.lastChangeAt)+". "+line:line;
            }
            long percent=s.bytes<=0?0:s.refinedBytes*100/s.bytes;
            String why=p.id!=null?"Another recording is being refined first.":p.waiting!=null?p.waiting+".":"Waiting to start.";
            return "Queued · "+percent+"% refined so far. "+why+" Showing the live draft for now.";
        }
        if("failed".equals(s.transcriptState))return "Refinement failed. Showing the live draft.";
        return "Live draft. Not refined yet.";
    }
    /** Refine again: a failed or never-refined recording is queued; a refined (or running) one starts over. */
    private void refineAgain(Recordings.Session s){
        if(s==null)return;
        boolean complete="complete".equals(s.transcriptState), pending="pending".equals(s.transcriptState);
        if(!complete&&!pending){retryRefinement(s.id);return;}
        new AlertDialog.Builder(this).setTitle(complete?"Refine this recording again?":"Start refining over?")
            .setMessage(complete?"Whisper transcribes it again with the current language and words to expect. The refined transcript is replaced; the live draft is shown until the new one is ready."
                :"Whisper starts this recording again from the beginning. The audio and live draft are kept.")
            .setNegativeButton("Cancel",null).setPositiveButton(complete?"Refine again":"Start over",(d,w)->io.execute(()->{
                try{Recordings.get(this).restartRefinement(s.id);RefinementJobService.schedule(this);main.post(()->{if(!destroyed&&s.id.equals(selectedId))detail(s.id);});}
                catch(Exception failure){main.post(()->error("Couldn't start refining again. "+(failure instanceof IllegalStateException&&failure.getMessage()!=null?failure.getMessage():"The audio and draft were not changed.")));}
            })).show();
    }
    /** Every tick: the detail line and the home "Refining" line follow Whisper's live progress. */
    private void refreshProgress(){
        RefinementProgress.Snapshot p=RefinementProgress.get();long now=RefinementProgress.clock.getAsLong();
        if(refiningLine!=null){
            String head="Whisper"+(RefinementProgress.build!=null?" ("+RefinementProgress.build+")":"")+" · ";
            String described=p.id!=null?RefinementProgress.describe(p,now):null;
            String line=described!=null?head+Character.toLowerCase(described.charAt(0))+described.substring(1)+" ›":p.waiting!=null?head+p.waiting:"";
            setText(refiningLine,line);refiningLine.setVisibility(line.isEmpty()||library||selectedId!=null?View.GONE:View.VISIBLE);
            refiningLine.setTextColor(p.id!=null&&RefinementProgress.stalled(p,now)?Ui.CORAL_TEXT:Ui.MUTED);
        }
        Recordings.Session s=detailSession;
        if(s!=null&&selectedId!=null&&s.id.equals(selectedId)&&detailRefinement!=null
                &&("pending".equals(s.transcriptState)||Recordings.SMALL.equals(s.model))){
            setText(detailRefinement,refinementLabel(s));
            detailRefinement.setTextColor(s.id.equals(p.id)&&RefinementProgress.stalled(p,now)?Ui.CORAL_TEXT:Ui.MUTED);
        }
    }
    private void retryRefinement(String id){
        io.execute(()->{try{Recordings.get(this).retryRefinement(id);RefinementJobService.schedule(this);main.post(()->{if(!destroyed&&id.equals(selectedId))detail(id);});}
            catch(Exception failure){main.post(()->error("Whisper could not be queued. Original audio and draft were not removed."));}});
    }
    private void refreshRefinement(){
        long revision=RefinementJobService.revision;if(!ready||destroyed||refinementRevision==revision)return;refinementRevision=revision;
        if(selectedId==null){if(library)loadLibrary();else loadHomeHistory();return;}
        final String id=selectedId;final int generation=viewGeneration;
        io.execute(()->{try{Recordings.Session s=Recordings.get(this).find(id);main.post(()->{
            if(destroyed||s==null||generation!=viewGeneration||!id.equals(selectedId)||detailTranscript==null)return;
            detailSession=s;setText(detailTranscript,transcriptText(s));setText(detailRefinement,refinementLabel(s));detailRefinement.setVisibility(refinementLabel(s).isEmpty()?View.GONE:View.VISIBLE);detailRetry.setVisibility(canRetry(s)?View.VISIBLE:View.GONE);setText(detailRetry,retryLabel(s));
        });}catch(Exception ignored){/* Do not replace retained UI with a failed read. */}});
    }
    private void delete(Recordings.Session s){new AlertDialog.Builder(this).setTitle("Delete this recording?").setMessage("Permanently removes its audio and transcript from this app. Files you previously exported are not removed.").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->playback.stop(()->{if(destroyed)return;io.execute(()->{try{Recordings.get(this).delete(s.id);if(s.id.equals(OmiCaptureService.display.snapshot().sessionId))OmiCaptureService.display.reset(null);if(s.id.equals(CaptureService.display.snapshot().sessionId))CaptureService.display.reset(null);main.post(()->{if(!destroyed){selectedId=null;draw();}});}catch(Exception e){main.post(()->error("Deletion could not be completed. Reopen the library to check its state."));}});})).show();}
    private void confirmExport(String id,String kind){new AlertDialog.Builder(this).setTitle("Export an unencrypted copy?").setMessage("Only your chosen file will leave the private library. The destination you select may sync to a cloud service. Keep this copy somewhere you trust.").setNegativeButton("Cancel",null).setPositiveButton("Choose destination",(d,w)->{exportId=id;exportKind=kind;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind.equals("wav")?"audio/wav":"text/plain").putExtra(Intent.EXTRA_TITLE,"GVoice-"+id+(kind.equals("wav")?".wav":".txt"));try{startActivityForResult(intent,20);}catch(RuntimeException e){exportId=null;exportKind=null;error("No document picker is available on this phone.");}}).show();}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==30){if(result==RESULT_OK)continueStart();return;}if(code==31){continueStart();return;}if(code!=20)return;final String id=exportId,kind=exportKind;exportId=null;exportKind=null;if(result!=RESULT_OK||data==null||data.getData()==null||id==null||kind==null)return;Uri uri=data.getData();io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IllegalStateException();if(kind.equals("wav"))Recordings.get(this).exportWav(id,out);else Recordings.get(this).exportText(id,out);out.flush();main.post(()->toast("Export saved to your chosen destination."));}catch(Exception e){main.post(()->error("Export failed. The destination may contain a partial file; remove it before retrying."));}});}
    private String versionName(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "";}}
    private void about(){new AlertDialog.Builder(this).setTitle("GVoice "+versionName()).setMessage("Records your Omi or phone microphone and transcribes Portuguese and English on this phone.\n\n• No internet access, accounts or analytics.\n• Audio and transcripts are encrypted on this phone. Uninstalling deletes them, so export what matters.\n• Up to 2 GB of audio. Nothing is deleted automatically.\n• Record with everyone's permission.\n\nA fork of NotTheOmiAIApp. Not affiliated with Omi or Based Hardware.").setNegativeButton("Close",null).setPositiveButton("Licenses",(d,w)->licenses()).show();}
    private void licenses(){try{String value;try(java.io.InputStream in=getAssets().open("licenses/whisper.cpp-MIT.txt")){value=readUtf8(in);}try(java.io.InputStream in=getAssets().open("licenses/whisper-model-MIT.txt")){value+="\n\nWhisper model\n"+readUtf8(in);}for(String name:new String[]{"vosk","jna","concentus","omi"})try(java.io.InputStream in=getAssets().open("licenses/"+name+"-license.txt")){value+="\n\n"+name+"\n"+readUtf8(in);}try(java.io.InputStream in=getAssets().open("licenses/ubuntu-font-licence.txt")){value+="\n\nUbuntu Mono font\n"+readUtf8(in);}TextView t=text(value,12,Ui.INK,false);t.setPadding(dp(20),dp(10),dp(20),dp(10));ScrollView scroll=new ScrollView(this);scroll.addView(t);new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll).setPositiveButton("Close",null).show();}catch(Exception e){error("License files could not be opened.");}}
    private static String readUtf8(java.io.InputStream in) throws java.io.IOException { java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8); }
    private void error(String message){if(!destroyed&&!isFinishing())new AlertDialog.Builder(this).setTitle("Please check").setMessage(message).setPositiveButton("OK",null).show();}
    private void toast(String s){if(!destroyed)Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static String duration(long millis){long sec=Math.max(0,millis/1000);return sec>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,(sec/60)%60,sec%60):String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    private static String size(long bytes){return String.format(Locale.ROOT,"%.1f MB",bytes/1048576.0);}
    private static String date(long time){return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new java.util.Date(time));}
    @Override public void onBackPressed(){if(selectedId!=null){selectedId=null;draw();}else if(library){library=false;draw();}else super.onBackPressed();}
    private final class Meter extends View {
        float level;boolean active;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Meter(){super(MainActivity.this);setContentDescription("Live recording audio level");}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(active?Ui.MINT:0x33FFFFFF);float center=getHeight()/2f;int bars=32;float spacing=getWidth()/(float)bars;
            for(int i=0;i<bars;i++){float envelope=(float)Math.sin((i+1)*Math.PI/(bars+1));float height=dp(3)+Math.min(1,level)*(getHeight()-dp(3))*envelope;c.drawRect(i*spacing,center-height/2,i*spacing+Math.max(dp(2),spacing-dp(4)),center+height/2,paint);}}
    }
}
