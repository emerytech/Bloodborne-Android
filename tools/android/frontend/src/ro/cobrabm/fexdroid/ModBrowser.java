// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Catalog UI built with the upstream theme/widgets. Never enables a mod or restarts the game. */
final class ModBrowser {
    interface Host { ModManager manager(); File config(); boolean destroyed(); void installed(); void importArchive(); }
    private final Activity owner;private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ModCatalog.Network catalogNetwork=new ModCatalog.Network();
    private volatile boolean closed;private boolean loading;
    private AlertDialog dialog,review;private EditText search;private CheckBox direct;private LinearLayout rows;private TextView status;
    private List<ModCatalog.Entry> entries=Collections.emptyList();
    private Job active;
    private static final class Job {
        final ModCatalog.Entry entry;final String title,filename;final File folder,archive,preview;
        final AtomicBoolean cancel=new AtomicBoolean();final ModCatalog.Network network=new ModCatalog.Network();
        Job(ModCatalog.Entry entry,File cache){this(entry,cache,entry.title,entry.filename);}
        Job(ModCatalog.Entry entry,File cache,String title,String filename){this.entry=entry;this.title=title;this.filename=filename;folder=new File(cache,"mod-browser-"+UUID.randomUUID());archive=new File(folder,filename);preview=new File(folder,"preview");}
    }
    ModBrowser(Activity owner,Host host){this.owner=owner;this.host=host;}
    private boolean live(){return !closed && !owner.isFinishing() && !host.destroyed();}
    private int dp(int n){return Math.round(n*owner.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(owner);t.setText(value);t.setTextSize(size);t.setTextColor(0xffe8dfc9);t.setPadding(0,dp(4),0,dp(4));return t;}
    void show(){
        LinearLayout box=new LinearLayout(owner);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);
        box.addView(text("Bloodborne 1.09 data mods · Thor gameplay compatibility unverified. Downloads install switched off.",13));
        search=new EditText(owner);search.setSingleLine();search.setHint("Search mods, authors or categories");box.addView(search);
        direct=new CheckBox(owner);direct.setText("Direct downloads only");box.addView(direct);
        status=text("Loading catalog…",12);box.addView(status);
        rows=new LinearLayout(owner);rows.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(owner);scroll.addView(rows);box.addView(scroll,new LinearLayout.LayoutParams(-1,dp(280)));
        dialog=new AlertDialog.Builder(owner).setTitle("Browse mods").setView(box).setPositiveButton("Refresh catalog",null).setNeutralButton("Import archive…",null).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->close());keys(dialog);dialog.show();
        dialog.getButton(-1).setOnClickListener(v->refresh());
        dialog.getButton(-3).setOnClickListener(v->{if(active==null && !loading){close();host.importArchive();}});
        dialog.getButton(-2).setOnClickListener(v->{if(active!=null){active.cancel.set(true);active.network.cancel();status.setText("Cancelling…");}else close();});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){render();}public void afterTextChanged(Editable e){}});
        direct.setOnCheckedChangeListener((button,on)->render());
        loading=true;buttons();worker.execute(()->{
            String problem=null;List<ModCatalog.Entry> result;
            try{File cache=new File(owner.getFilesDir(),"mod-catalog-v1.json");try(InputStream in=cache.isFile()?new FileInputStream(cache):owner.getAssets().open("mod-catalog.json")){result=ModCatalog.parse(read(in));}}
            catch(Exception e){try(InputStream in=owner.getAssets().open("mod-catalog.json")){result=ModCatalog.parse(read(in));problem="Cached catalog unavailable; showing bundled list.";}catch(Exception failure){result=Collections.emptyList();problem="Catalog unavailable. Import archive still works.";}}
            final List<ModCatalog.Entry> loaded=result;final String note=problem;main.post(()->{if(!live())return;entries=loaded;loading=false;status.setText(note==null?"Bundled/cached catalog · Refresh checks for additions":note);render();});
        });
    }
    private void keys(AlertDialog window){window.setOnKeyListener((d,code,event)->{
        if(code==android.view.KeyEvent.KEYCODE_BUTTON_B || event.getScanCode()==305){if(event.getAction()==android.view.KeyEvent.ACTION_UP)window.getButton(-2).performClick();return true;}
        if(code==android.view.KeyEvent.KEYCODE_BUTTON_A || event.getScanCode()==304){if(event.getAction()==android.view.KeyEvent.ACTION_UP){android.view.View focus=window.getCurrentFocus();if(focus!=null)focus.performClick();}return true;}
        return false;
    });}
    private static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>ModCatalog.MAX_CATALOG)throw new IOException("Catalog too large");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    private void buttons(){if(dialog==null)return;boolean idle=!loading && active==null;dialog.getButton(-1).setEnabled(idle);dialog.getButton(-3).setEnabled(idle);dialog.getButton(-2).setText(active==null?"Close":"Cancel download");search.setEnabled(idle);direct.setEnabled(idle);}
    private void render(){if(!live())return;buttons();rows.removeAllViews();int found=0;
        for(ModCatalog.Entry entry:entries){if(!entry.matches(search.getText().toString(),direct.isChecked()))continue;found++;
            LinearLayout card=new LinearLayout(owner);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(0,dp(8),0,dp(12));
            card.addView(text(entry.title,17));card.addView(text(entry.author+" · "+entry.category+" · Not Thor-tested",12));card.addView(text(entry.summary,13));
            LinearLayout actions=new LinearLayout(owner);Button details=new Button(owner);details.setAllCaps(false);details.setText("Author page");details.setOnClickListener(v->openPage(entry));actions.addView(details);
            if(!entry.url.isEmpty()){Button download=new Button(owner);download.setAllCaps(false);download.setText("Download · "+(entry.bytes/(1024*1024))+" MB");download.setEnabled(active==null&&!loading);download.setOnClickListener(v->confirmDownload(entry));actions.addView(download);}
            else card.addView(text("Website download · return here and choose Import archive",12));
            card.addView(actions);rows.addView(card);
        }
        if(found==0)rows.addView(text(loading?"Loading…":"No matches. Try another search or turn off the direct-download filter.",14));
    }
    private void openPage(ModCatalog.Entry entry){try{owner.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(entry.page)));}catch(android.content.ActivityNotFoundException e){status.setText("No browser available: "+entry.page);}}
    private void refresh(){if(loading||active!=null)return;loading=true;status.setText("Checking catalog…");render();worker.execute(()->{try{String data=catalogNetwork.catalog(()->closed);List<ModCatalog.Entry> result=ModCatalog.parse(data);if(closed)return;SafeWrite.write(new File(owner.getFilesDir(),"mod-catalog-v1.json"),data);main.post(()->{if(!live())return;entries=result;loading=false;status.setText("Catalog updated · all entries remain unverified for Thor gameplay");render();});}catch(Exception e){main.post(()->{if(!live())return;loading=false;status.setText("Could not refresh; existing list kept. "+message(e));render();});}});}
    private void confirmDownload(ModCatalog.Entry entry){if(active!=null||loading)return;
        review=new AlertDialog.Builder(owner).setTitle(entry.title).setMessage("By "+entry.author+"\n\n"+entry.summary+"\n\nThor gameplay compatibility is unverified. This downloads from GitHub, checks the pinned checksum, and previews the archive. Installation is a separate step; the mod stays off afterward.")
            .setNegativeButton("Cancel",null).setPositiveButton("Download & preview",(d,w)->download(entry)).show();keys(review);
    }
    private void download(ModCatalog.Entry entry){
        Job job=new Job(entry,owner.getCacheDir());active=job;status.setText("Downloading "+entry.title+"…");render();
        worker.execute(()->{boolean ready=false;try{
            if(!job.folder.mkdirs())throw new IOException("Cannot create download folder");
            if(new StatFs(job.folder.getPath()).getAvailableBytes()<entry.bytes+(32L<<20))throw new IOException("Not enough free space to download");
            job.network.download(entry,job.archive,job.cancel::get,(done,total)->progress(job,"Downloading",done,total));
            ModBrowserInstall.unpack(job.archive,job.preview,new StatFs(job.folder.getPath()).getAvailableBytes(),(done,total)->progress(job,"Checking archive",done,total),job.cancel::get);
            List<String> conflicts=ModBrowserInstall.conflicts(job.preview,host.manager());
            ModManager.Mod checked=new ModManager(job.folder,null).validate("preview");
            if(job.cancel.get()||closed)throw new CancellationException();ready=true;
            main.post(()->{if(!live()||job.cancel.get()){discard(job);return;}showReview(job,checked,conflicts);});
        }catch(Exception e){main.post(()->{if(!live())return;if(active==job)active=null;status.setText(job.cancel.get()?"Download cancelled; nothing installed.":message(e));render();});}
        finally{if(!ready||closed)ModArchive.deleteTree(job.folder);}});
    }
    void importArchive(Uri uri) {
        String filename="Imported-mod.zip";
        try(android.database.Cursor c=owner.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst()&&c.getString(0)!=null) filename=ModArchive.modName(c.getString(0))+".zip";
        }catch(Exception ignored){}
        Job job=new Job(null,owner.getCacheDir(),"Imported archive",filename);active=job;status.setText("Reading local archive…");buttons();
        worker.execute(()->{boolean ready=false;try{
            if(!job.folder.mkdirs())throw new IOException("Cannot create preview folder");
            long available=new StatFs(job.folder.getPath()).getAvailableBytes()-(32L<<20);
            long limit=Math.min(ModCatalog.MAX_DOWNLOAD,available);if(limit<=0)throw new IOException("Not enough storage for preview");
            try(InputStream in=owner.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(job.archive)){
                if(in==null)throw new IOException("Cannot open archive");byte[] buffer=new byte[65536];long count=0;
                for(int n;(n=in.read(buffer))!=-1;){if(job.cancel.get()||closed)throw new CancellationException();count+=n;if(count>limit)throw new IOException("Archive exceeds preview storage limit (maximum 512 MB). Use the original Install mod action for larger archives.");out.write(buffer,0,n);}
            }
            ModBrowserInstall.unpack(job.archive,job.preview,new StatFs(job.folder.getPath()).getAvailableBytes(),(done,total)->progress(job,"Checking archive",done,total),job.cancel::get);
            List<String> conflicts=ModBrowserInstall.conflicts(job.preview,host.manager());
            ModManager.Mod checked=new ModManager(job.folder,null).validate("preview");
            if(job.cancel.get()||closed)throw new CancellationException();ready=true;
            main.post(()->{if(!live()||job.cancel.get()){discard(job);return;}showReview(job,checked,conflicts);});
        }catch(Exception e){main.post(()->{if(!live())return;if(active==job)active=null;status.setText(job.cancel.get()?"Import cancelled; nothing installed.":message(e));render();});}
        finally{if(!ready||closed)ModArchive.deleteTree(job.folder);}});
    }
    private void progress(Job job,String phase,long done,long total){main.post(()->{if(live()&&active==job&&!job.cancel.get())status.setText(phase+" · "+(total>0?Math.min(100,done*100/total)+"%":done+" bytes"));});}
    private void showReview(Job job,ModManager.Mod check,List<String> conflicts){
        String overlap=conflicts.isEmpty()?"No shared files with installed mods.":"Shared files (later enabled mods win):\n"+String.join("\n",conflicts);
        status.setText("Download verified. Review before installation.");
        review=new AlertDialog.Builder(owner).setTitle("Review · "+job.title)
            .setMessage((job.entry==null?"Local archive inspected · ":"SHA-256 verified · ")+check.files+" data file(s)\n\nThor gameplay compatibility: unverified\n\n"+overlap+"\n\nInstall into a separate mod folder, switched OFF. Original game files and saves are unchanged. Enable it later in Mods, then choose Apply & restart.")
            .setCancelable(false).setNegativeButton("Cancel",(d,w)->discard(job)).setPositiveButton("Install disabled",(d,w)->install(job)).show();keys(review);
    }
    private void install(Job job){status.setText("Installing switched off…");worker.execute(()->{try{
        String name=ModBrowserInstall.install(job.archive,job.filename,host.manager().modsDir(),host.config(),new StatFs(host.manager().modsDir().getPath()).getAvailableBytes(),(done,total)->progress(job,"Installing",done,total),job.cancel::get);
        main.post(()->{if(!live())return;active=null;status.setText(name+" installed OFF. Enable in Mods when ready.");host.installed();render();});
    }catch(Exception e){main.post(()->{if(!live())return;active=null;status.setText(job.cancel.get()?"Install cancelled; no mod enabled.":message(e));render();});}finally{ModArchive.deleteTree(job.folder);}});}
    private void discard(Job job){job.cancel.set(true);job.network.cancel();if(active==job)active=null;if(!closed)worker.execute(()->ModArchive.deleteTree(job.folder));if(live()){status.setText("Preview discarded; nothing installed.");render();}}
    private static String message(Exception e){return e.getMessage()==null?"Operation failed; nothing enabled.":e.getMessage();}
    void close(){if(closed)return;closed=true;catalogNetwork.cancel();Job job=active;if(job!=null){job.cancel.set(true);job.network.cancel();worker.execute(()->ModArchive.deleteTree(job.folder));}if(review!=null)review.dismiss();if(dialog!=null)dialog.dismiss();worker.shutdown();}
}
