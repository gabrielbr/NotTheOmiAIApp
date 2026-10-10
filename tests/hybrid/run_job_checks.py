#!/usr/bin/env python3
"""Production JobService lifecycle tests with controlled Android/store/native doubles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/content/Context.java': '''package android.content;
public class Context { public Context getApplicationContext(){return this;} public <T> T getSystemService(Class<T> type) { return type==android.os.BatteryManager.class?type.cast(android.os.BatteryManager.INSTANCE):type.cast(android.app.job.JobScheduler.INSTANCE); } }''',
    'android/os/BatteryManager.java': '''package android.os; public class BatteryManager { public static final BatteryManager INSTANCE=new BatteryManager(); public static volatile boolean charging; public boolean isCharging(){return charging;} }''',
    'android/content/ComponentName.java': '''package android.content;
public class ComponentName { public ComponentName(Context c, Class<?> type) {} }''',
    'android/os/Looper.java': '''package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }''',
    'android/os/Handler.java': '''package android.os;
public class Handler {
 public static final java.util.Queue<Runnable> QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Handler(Looper l){} public boolean post(Runnable r){QUEUE.add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){return true;}
 public void removeCallbacks(Runnable r){QUEUE.remove(r);}
 public static void drain(){Runnable r; while((r=QUEUE.poll())!=null)r.run();}
}''',
    'android/os/Process.java': '''package android.os; public class Process { public static final int THREAD_PRIORITY_BACKGROUND=10,THREAD_PRIORITY_DEFAULT=0;
 public static volatile int priority; public static void setThreadPriority(int p){priority=p;} }''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }''',
    'android/app/job/JobParameters.java': '''package android.app.job; public class JobParameters {
 private final int id; public JobParameters(int n){id=n;} public int getJobId(){return id;} }''',
    'android/app/job/JobInfo.java': '''package android.app.job; public class JobInfo {
 public static final int NETWORK_TYPE_NONE=0,NETWORK_TYPE_ANY=1,NETWORK_TYPE_UNMETERED=2,BACKOFF_POLICY_EXPONENTIAL=1;
 public static volatile int lastNetwork=-1;
 public static class Builder { public Builder(int id,android.content.ComponentName c){}
 public Builder setRequiredNetworkType(int n){lastNetwork=n;return this;} public Builder setRequiresStorageNotLow(boolean b){return this;}
 public Builder setMinimumLatency(long n){return this;} public Builder setRequiresCharging(boolean b){return this;} public Builder setBackoffCriteria(long n,int p){return this;}
 public JobInfo build(){return new JobInfo();} }
}''',
    'android/app/job/JobScheduler.java': '''package android.app.job; public class JobScheduler {
 public static final JobScheduler INSTANCE=new JobScheduler(); public static final int RESULT_SUCCESS=1;
 public int schedules; public int schedule(JobInfo i){schedules++;return RESULT_SUCCESS;} }''',
    'android/app/job/JobService.java': '''package android.app.job; public class JobService extends android.content.Context {
 public final java.util.List<JobParameters> finished = new java.util.ArrayList<>();
 public final java.util.List<Boolean> retry = new java.util.ArrayList<>();
 public boolean onStartJob(JobParameters p){return false;} public boolean onStopJob(JobParameters p){return false;}
 public void onDestroy(){} public void jobFinished(JobParameters p,boolean r){finished.add(p);retry.add(r);}
}''',
    'app/nottheomi/ai/CaptureService.java': '''package app.nottheomi.ai; public class CaptureService { public static volatile boolean active; }''',
    'app/nottheomi/ai/OmiSettingsActivity.java': '''package app.nottheomi.ai; public class OmiSettingsActivity { static String language(android.content.Context c){return "pt";} static String vocabulary(android.content.Context c){return "Gabriel, Ana";} static volatile boolean better; static boolean betterWhileCharging(android.content.Context c){return better;} static volatile boolean mobile; static boolean mobileDownloads(android.content.Context c){return mobile;} }''',
    'app/nottheomi/ai/OmiCaptureService.java': '''package app.nottheomi.ai; public class OmiCaptureService { public static volatile boolean active; }''',
    'app/nottheomi/ai/ModelInstaller.java': '''package app.nottheomi.ai; public class ModelInstaller {
 public static final class WaitingForNetwork extends java.io.IOException { WaitingForNetwork(String m){super(m);} }
 public interface Progress { void update(long done, long total); }
 static volatile boolean offline; static final java.util.List<String> downloads=new java.util.concurrent.CopyOnWriteArrayList<>();
 static java.io.File fetch(String name,Progress p)throws java.io.IOException{if(offline)throw new WaitingForNetwork(name);downloads.add(name);p.update(50,100);p.update(100,100);return new java.io.File(name);}
 public static java.io.File prepare(android.content.Context c,java.util.function.BooleanSupplier stop,boolean metered,Progress p)throws java.io.IOException{return fetch("fake-model",p);}
 public static java.io.File prepareVad(android.content.Context c,java.util.function.BooleanSupplier stop){return new java.io.File("fake-vad");}
 public static java.io.File prepareSmall(android.content.Context c,java.util.function.BooleanSupplier stop,boolean metered,Progress p)throws java.io.IOException{return fetch("fake-small",p);} }''',
    'app/nottheomi/ai/Recordings.java': '''package app.nottheomi.ai;
import java.util.*;
public class Recordings {
 static final Recordings INSTANCE=new Recordings(); static volatile int commits,completes,failures;
 static final Refinement ENTRY=new Refinement();
 public static final String SMALL="small", MEDIUM="medium";
 public static class Refinement { public String id="synthetic",state="pending"; public long totalBytes=2,offsetBytes=0; public boolean finalPass; }
 static final Refinement FINAL=new Refinement(); static { FINAL.finalPass=true; FINAL.state="none"; }
 static volatile String completedModel; static volatile int finalCommits,finalCompletes,finalFailures;
 public List<Refinement> pendingFinals(){return FINAL.state.equals("pending")?List.of(FINAL):List.of();}
 public Refinement finalRefinement(String id){return FINAL;}
 public void commitFinalBatch(String id,long before,long after,String text){finalCommits++;FINAL.offsetBytes=after;}
 public void completeFinal(String id){finalCompletes++;FINAL.state="none";}
 public void failFinal(String id){finalFailures++;FINAL.state="none";}
 interface Consumer{void accept(byte[] b)throws Exception;}
 public static Recordings get(android.content.Context c){return INSTANCE;}
 public List<Refinement> pendingRefinements(){return ENTRY.state.equals("pending")?List.of(ENTRY):List.of();}
 public Refinement refinement(String id){return ENTRY;}
 public void forEachPcm(String id,Consumer c)throws Exception{c.accept(new byte[]{1,0});}
 static volatile boolean stale;
 public static final class StaleCheckpointException extends IllegalStateException { StaleCheckpointException(){super("stale");} }
 public void commitRefinementBatch(String id,long before,long after,String text){if(stale){stale=false;throw new StaleCheckpointException();}commits++;ENTRY.offsetBytes=after;}
 public void completeRefinement(String id,String model){completes++;completedModel=model;ENTRY.state="complete";if(SMALL.equals(model)){FINAL.state="pending";FINAL.offsetBytes=0;}}
 public void failRefinement(String id){failures++;ENTRY.state="failed";}
}''',
    'app/nottheomi/ai/WhisperNative.java': '''package app.nottheomi.ai; public class WhisperNative { public static final String BUILD="fast"; static volatile int pins; public static int pinFastCores(){pins++;return 4;} }''',
    'app/nottheomi/ai/RefinementService.java': '''package app.nottheomi.ai; public class RefinementService extends android.content.Context {
 static volatile boolean allowed; static volatile int starts; volatile int dones;
 static boolean start(android.content.Context c){starts++;return allowed;}
 void done(){dones++;} }''',
    'app/nottheomi/ai/ReadyNotifier.java': '''package app.nottheomi.ai; public class ReadyNotifier { static volatile int ready; static void refined(android.content.Context c,java.util.List<String> ids){ready+=ids.size();} }''',
    'app/nottheomi/ai/WhisperModel.java': '''package app.nottheomi.ai;
import java.util.concurrent.*;
public class WhisperModel {
 static final CountDownLatch ENTERED=new CountDownLatch(1), RELEASE=new CountDownLatch(1), CLOSED=new CountDownLatch(1);
 static volatile int owners,maxOwners,opens,cancels,threads; static volatile Thread inferenceThread,closeThread;
 static volatile String vad;
 static final java.util.List<String> models=new java.util.concurrent.CopyOnWriteArrayList<>();
 public WhisperModel(String p,String v){vad=v;models.add(p);opens++;owners++;maxOwners=Math.max(owners,maxOwners);}
 public String transcribe(short[] s,int n,String language,String prompt)throws Exception{if(!"pt".equals(language)||!"Gabriel, Ana".equals(prompt))throw new AssertionError("language/vocabulary");threads=n;inferenceThread=Thread.currentThread();ENTERED.countDown();if(!RELEASE.await(5,TimeUnit.SECONDS))throw new AssertionError("test release timeout");return "synthetic decoded";}
 public void cancel(){cancels++;}
 public int progress(){return 40;}
 public void close(){closeThread=Thread.currentThread();owners--;CLOSED.countDown();}
}'''
}
TEST = '''package app.nottheomi.ai;
import android.app.job.*; import android.os.Handler; import java.util.concurrent.TimeUnit;
public class RefinementJobHostTest {
 static int assertions; static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static void settled()throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){Handler.drain();java.lang.reflect.Field f=RefinementJobService.class.getDeclaredField("owner");f.setAccessible(true);if(f.get(null)==null)return;Thread.sleep(5);}throw new AssertionError("worker did not exit");}
 public static void main(String[] args)throws Exception{
  String mode=args[0];RefinementJobService service=new RefinementJobService();JobParameters start=new JobParameters(812);
  if(mode.equals("capture-active"))CaptureService.active=true;
  if(mode.equals("in-process")){
   // While capturing, schedule() runs the worker in this process: no job, no time limit.
   CaptureService.active=true;RefinementJobService.schedule(service);
   check(JobScheduler.INSTANCE.schedules==0,"no job while capturing");check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"in-process worker entered");
   CaptureService.active=false; // capture ends mid-window: the window still finishes and is saved
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"worker closes native model");settled();
   check(Recordings.commits==1&&Recordings.completes==1&&WhisperModel.cancels==0,"window finished after the capture ended, never discarded");
   check(service.finished.isEmpty(),"no job to finish");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("service")){
   // The foreground service hosts the worker: no job, normal priority, pinned to the fast cores, no time limit.
   RefinementService.allowed=true;RefinementJobService.schedule(service);
   check(RefinementService.starts==1&&JobScheduler.INSTANCE.schedules==0&&WhisperModel.opens==0,"asks for the service, no job, no worker yet");
   RefinementService host=new RefinementService();check(RefinementJobService.host(host),"service takes the work");
   check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"hosted worker entered");
   check(android.os.Process.priority==android.os.Process.THREAD_PRIORITY_DEFAULT,"normal priority in the foreground");
   check(WhisperNative.pins==1&&RefinementProgress.cores==4&&RefinementJobService.fastCores==4,"pinned to the fast cores");
   check(RefinementJobService.threads(8,false,4)==4&&RefinementJobService.threads(8,false,2)==2&&RefinementJobService.threads(8,true,4)==4&&RefinementJobService.threads(4,true,2)==2,"one thread per fast core");
   check(RefinementProgress.engine(RefinementProgress.get()).startsWith("fast build, 4 fast cores"),RefinementProgress.engine(RefinementProgress.get()));
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"closed");settled();
   check(Recordings.completes==1&&host.dones==1&&service.finished.isEmpty(),"finishes, then the service stops itself");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("job-promotes")){
   // Android starts the job: it moves the work to the service (big cores) instead of running it.
   RefinementService.allowed=true;check(service.onStartJob(start),"async start");Handler.drain();
   check(RefinementService.starts==1&&WhisperModel.opens==0,"no Whisper in the job");
   check(service.finished.contains(start)&&!service.retry.get(0),"job done without retry");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("handoff")){
   // A plain-thread worker (service refused) hands over to the service after its window.
   CaptureService.active=true;RefinementJobService.schedule(service);check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"plain worker entered");
   RefinementService.allowed=true;RefinementService host=new RefinementService();check(RefinementJobService.host(host),"service waits for it");
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"closed");settled();
   check(Recordings.commits==1&&Recordings.completes==1&&WhisperModel.cancels==0,"its window finished");
   check(host.dones==1&&WhisperModel.opens==1,"the service took over (nothing left) and stopped");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("offline")){
   // The model isn't on the phone and there's no Wi-Fi: wait for an unmetered network, no Whisper.
   RefinementService.allowed=true;ModelInstaller.offline=true;RefinementService host=new RefinementService();
   check(RefinementJobService.host(host),"service takes the work");settled();
   check(WhisperModel.opens==0&&Recordings.commits==0&&"pending".equals(Recordings.ENTRY.state),"nothing transcribed, recording still queued");
   check(JobInfo.lastNetwork==JobInfo.NETWORK_TYPE_UNMETERED&&JobScheduler.INSTANCE.schedules>=1,"job waits for Wi-Fi");
   check(host.dones==1,"service stops while waiting");
   check(RefinementProgress.get().waiting!=null&&RefinementProgress.get().waiting.contains("Wi-Fi"),"says it waits for Wi-Fi");
   OmiSettingsActivity.mobile=true;check(RefinementJobService.host(new RefinementService()),"again, mobile data allowed");settled();
   check(JobInfo.lastNetwork==JobInfo.NETWORK_TYPE_ANY,"any network when mobile data is allowed");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("download-now")){
   // Settings › Download now: fetch the models with nothing to transcribe; medium only with the accurate setting on.
   Recordings.ENTRY.state="complete";RefinementService.allowed=true;OmiSettingsActivity.better=false;
   RefinementJobService.downloadNow(service);RefinementService host=new RefinementService();check(RefinementJobService.host(host),"service takes the work");settled();
   check(ModelInstaller.downloads.equals(java.util.List.of("fake-small"))&&WhisperModel.opens==0,"small only, no Whisper: "+ModelInstaller.downloads);
   check(!RefinementJobService.downloadRequested&&host.dones==1,"request done, service stops");
   OmiSettingsActivity.better=true;RefinementJobService.downloadNow(service);check(RefinementJobService.host(new RefinementService()),"again");settled();
   check(ModelInstaller.downloads.equals(java.util.List.of("fake-small","fake-small","fake-model")),"medium too with the accurate setting: "+ModelInstaller.downloads);
   check(RefinementProgress.get().waiting==null,"download line cleared when done");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("describe")){
   long[] now={1_000_000L};RefinementProgress.clock=()->now[0];RefinementProgress.reset();
   int[] window={0};long total=41L*60*1000*RefinementProgress.BYTES_PER_MS; // 41 minutes of audio
   RefinementProgress.begin("r1",0,total,()->window[0]);
   long w=30_000*RefinementProgress.BYTES_PER_MS;RefinementProgress.window(0,w);
   now[0]+=60_000;window[0]=50;RefinementProgress.Snapshot mid=RefinementProgress.get();
   check(mid.done==w/2&&mid.percent()==0,"half a window counts toward the total");
   now[0]+=60_000;RefinementProgress.saved(w); // 30 s of audio took 2 min: 0.25x
   RefinementProgress.Snapshot after=RefinementProgress.get();
   check(Math.abs(after.speed-0.25)<1e-9,"speed from finished windows");
   String line=RefinementProgress.describe(after,now[0]+20_000);
   check(line.equals("Refining · 1% · 0:30 of 41:00 · about 2 h 42 min left · updated 20 s ago"),line);
   check(!RefinementProgress.stalled(after,now[0]+RefinementProgress.STALL_MS-1)&&RefinementProgress.stalled(after,now[0]+RefinementProgress.STALL_MS),"stall after 5 minutes without movement");
   RefinementProgress.window(w,w);now[0]+=400_000;window[0]=10;RefinementProgress.get();now[0]+=1;window[0]=20;
   check(RefinementProgress.get().lastChangeAt==now[0],"a window's own progress counts as movement");
   check(RefinementProgress.clock(3_725_000).equals("1:02:05")&&RefinementProgress.span(59_000).equals("59 s"),"formats");
   RefinementProgress.idle("Waiting for Android to start it");check(RefinementProgress.get().id==null&&"Waiting for Android to start it".equals(RefinementProgress.get().waiting),"idle reason");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("accurate")){
   // Quick pass with small, then (plugged in, setting on) the accurate pass with medium; one model at a time.
   OmiSettingsActivity.better=true;android.os.BatteryManager.charging=true;
   check(service.onStartJob(start),"async start");
   check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"quick pass entered");WhisperModel.RELEASE.countDown();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline&&Recordings.finalCompletes==0)Thread.sleep(5);
   settled();
   check(WhisperModel.models.size()==2&&WhisperModel.models.get(0).endsWith("fake-small")&&WhisperModel.models.get(1).endsWith("fake-model"),"small first, then medium: "+WhisperModel.models);
   check(WhisperModel.maxOwners==1&&WhisperModel.owners==0,"one model at a time");
   check("small".equals(Recordings.completedModel)&&Recordings.finalCompletes==1&&Recordings.finalFailures==0,"quick transcript recorded as small, then swapped for the accurate one");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("unplugged")){
   // On battery the accurate pass waits: only the quick pass runs, medium never loads.
   OmiSettingsActivity.better=true;android.os.BatteryManager.charging=false;
   check(service.onStartJob(start),"async start");check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"quick entered");
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"closed");settled();
   check(WhisperModel.models.size()==1&&WhisperModel.models.get(0).endsWith("fake-small"),"medium not loaded on battery: "+WhisperModel.models);
   check("pending".equals(Recordings.FINAL.state)&&Recordings.finalCommits==0,"accurate pass queued, waiting for the charger");
   check(RefinementProgress.get().waiting!=null&&RefinementProgress.get().waiting.contains("charges"),"says it waits for charging");
   check("fast build".equals(RefinementProgress.build),"reports which native build runs");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  if(mode.equals("stale")){
   Recordings.stale=true;check(service.onStartJob(start),"async start");check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"entered");
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"closed");settled();
   check(Recordings.failures==0&&"pending".equals(Recordings.ENTRY.state),"a recording set to refine again is skipped, not failed");
   check(service.finished.contains(start)&&JobScheduler.INSTANCE.schedules==1,"and picked up again right away");
   System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");return;
  }
  {
   check(service.onStartJob(start),"async start");check(WhisperModel.ENTERED.await(5,TimeUnit.SECONDS),"real worker entered double");
   if(mode.equals("stop")){
    JobParameters stop=new JobParameters(812);check(start!=stop,"distinct Binder objects");check(service.onStopJob(stop),"reschedule requested");check(WhisperModel.cancels>0,"same job ID cancelled immediately");
    service.onStartJob(new JobParameters(812));Handler.drain();check(WhisperModel.opens==1&&WhisperModel.owners==1,"blocked older worker retains sole native ownership");check(!service.finished.contains(start),"stopped job not finished early");
   } else if(mode.equals("different-job")) {service.onStopJob(new JobParameters(999));check(WhisperModel.cancels==0,"different job not cancelled");}
   else if(mode.equals("destroy")){service.onDestroy();check(WhisperModel.cancels>0,"destroy cancels without freeing");check(WhisperModel.owners==1,"destroy retains native owner");}
   else if(mode.equals("capture-start")){CaptureService.active=true;RefinementJobService.captureStarted(service);check(WhisperModel.cancels==0&&WhisperModel.opens==1,"a capture starting neither cancels nor duplicates the running job");}
   else if(mode.equals("capture-active")){int cores=Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()));check(WhisperModel.threads==RefinementJobService.threads(Runtime.getRuntime().availableProcessors(),true),"capture thread count");check(RefinementJobService.threads(8,true)==4&&RefinementJobService.threads(4,true)==2&&RefinementJobService.threads(6,false)==4&&RefinementJobService.threads(2,true)==2,"4 threads while capturing on 8-core phones, 2 on smaller ones");check(android.os.Process.priority==RefinementJobService.WORKER_PRIORITY&&RefinementJobService.WORKER_PRIORITY<android.os.Process.THREAD_PRIORITY_BACKGROUND,"mild priority, not the background (little-core) group");}
   else if(mode.equals("progress")){RefinementProgress.Snapshot snap=RefinementProgress.get();check("synthetic".equals(snap.id)&&snap.total==2,"progress names the recording being refined");check(snap.done==0,"a 2-byte window at 40% rounds down to nothing saved yet");}
   else if(mode.equals("success")){check(WhisperModel.threads==Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())),"all cores when idle");}
   else throw new IllegalArgumentException(mode);
   WhisperModel.RELEASE.countDown();check(WhisperModel.CLOSED.await(5,TimeUnit.SECONDS),"worker closes native model");settled();
   check(WhisperModel.vad!=null&&WhisperModel.vad.endsWith("fake-vad"),"VAD model passed to Whisper");
   check(WhisperModel.maxOwners==1&&WhisperModel.owners==0,"serialized native ownership");check(WhisperModel.inferenceThread==WhisperModel.closeThread,"inference thread owns close");
   boolean cancelled=mode.equals("stop")||mode.equals("destroy");
   check(Recordings.commits==(cancelled?0:1),"cancelled inference never publishes checkpoint");check(Recordings.completes==(cancelled?0:1),"cancelled inference never completes");check(Recordings.failures==0,"cancellation is not durable failure");check(ReadyNotifier.ready==(cancelled?0:1),"ready notification only for completed refinement");
   if(mode.equals("stop")||mode.equals("destroy"))check(!service.finished.contains(start),"no jobFinished after platform stop");
   else {check(service.finished.contains(start),"live job finishes");check(!service.retry.get(service.finished.indexOf(start)),"completed pass needs no retry");}
  }
  System.out.println("RefinementJobHostTest PASS "+mode+": "+assertions+" assertions; production service with controlled doubles");
 }
}'''
with tempfile.TemporaryDirectory(prefix='nottheomi-hybrid-job-') as directory:
    work = Path(directory)
    sources = []
    for name, text in {**STUBS, 'app/nottheomi/ai/RefinementJobHostTest.java': TEST}.items():
        path = work/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        sources.append(path)
    sources += [ROOT/'app/src/main/java/app/nottheomi/ai'/name for name in ('RefinementJobService.java', 'RefinementEngine.java', 'RefinementProgress.java')]
    subprocess.run(['javac', '--release', '17', '-d', str(work), *map(str, sources)], check=True)
    scenarios = ['stop', 'different-job', 'destroy', 'capture-start', 'capture-active', 'progress', 'success', 'in-process', 'stale', 'describe', 'accurate', 'unplugged', 'service', 'job-promotes', 'handoff', 'offline', 'download-now']
    for case in scenarios:
        subprocess.run(['java', '-cp', str(work), 'app.nottheomi.ai.RefinementJobHostTest', case], check=True, timeout=15)
    print(f'JobService lifecycle PASS: {len(scenarios)} scenarios; Android/native/store doubles, not device lifecycle acceptance.')
