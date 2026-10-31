package ro.cobrabm.fexdroid;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class ModBrowserTests {
    static int checks;
    static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    interface Action{void run()throws Exception;}
    static void reject(Action action,String why)throws Exception{try{action.run();}catch(IOException|java.util.concurrent.CancellationException expected){checks++;return;}throw new AssertionError(why);}
    static String hash(byte[] b)throws Exception{StringBuilder out=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b))out.append(String.format("%02x",x&255));return out.toString();}
    static File zip(File root,String name,String path,String content)throws Exception{File f=new File(root,name);try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){z.putNextEntry(new ZipEntry(path));z.write(content.getBytes(StandardCharsets.UTF_8));z.closeEntry();}return f;}
    public static void main(String[] args)throws Exception{
        File temp=Files.createTempDirectory("thor-mod-tests").toFile();
        try{
            String catalog=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
            List<ModCatalog.Entry> entries=ModCatalog.parse(catalog);ok(entries.size()>=3,"real starter entries");
            ModCatalog.Entry direct=entries.get(0);ok(direct.matches("DOMMO",true),"case insensitive author search");ok(!direct.matches("no-such-mod",false),"empty results");ok(!entries.get(1).matches("",true),"direct filter excludes website entries");
            reject(()->ModCatalog.parse(catalog.replace("\"schema\": 1","\"schema\": 2")),"unknown schema");
            reject(()->ModCatalog.parse(catalog.replace(direct.sha256,"bad")),"checksum required");
            reject(()->ModCatalog.parse(catalog.replace(direct.filename,"../escape.zip")),"unsafe destination filename");
            reject(()->ModCatalog.parse(catalog.replace(direct.url,"http://github.com/a/b/releases/download/tag/mod.zip")),"HTTPS required");
            reject(()->ModCatalog.parse(catalog.replace(direct.url,"https://evil.example/mod.zip")),"unknown host rejected");
            reject(()->ModCatalog.parse(catalog.replace("\"schema\": 1","\"schema\": 1.5")),"fractional schema rejected");
            ok(!ModCatalog.redirectAllowed("https://github.com@evil.example/mod.zip"),"userinfo spoof rejected");
            ok(!ModCatalog.redirectAllowed("http://release-assets.githubusercontent.com/mod.zip"),"downgrade rejected");
            ok(!ModCatalog.redirectAllowed("https://localhost/mod.zip"),"localhost rejected");
            ok(ModCatalog.redirectAllowed("https://release-assets.githubusercontent.com/test?sig=x"),"signed GitHub asset URL accepted");
            reject(()->ModCatalog.parse(new String(new char[30]).replace("\0","[")+"0"+new String(new char[30]).replace("\0","]")),"deep catalog rejected before parser recursion");
            byte[] data="verified test archive".getBytes(StandardCharsets.UTF_8);File copied=new File(temp,"copy.zip");
            ModCatalog.verifiedCopy(new ByteArrayInputStream(data),copied,data.length,hash(data),()->false,(d,t)->{});ok(copied.isFile(),"verified bytes retained");
            reject(()->ModCatalog.verifiedCopy(new ByteArrayInputStream(data),copied,data.length,"00",()->false,(d,t)->{}),"checksum mismatch");ok(!copied.exists(),"mismatch removed");
            reject(()->ModCatalog.verifiedCopy(new ByteArrayInputStream(data),copied,data.length-1,hash(data),()->false,(d,t)->{}),"oversize");ok(!copied.exists(),"oversize removed");
            reject(()->ModCatalog.verifiedCopy(new ByteArrayInputStream(data),copied,data.length+1,hash(data),()->false,(d,t)->{}),"truncated stream");ok(!copied.exists(),"truncated removed");
            reject(()->ModCatalog.verifiedCopy(new ByteArrayInputStream(data),copied,data.length,hash(data),()->true,(d,t)->{}),"cancel");ok(!copied.exists(),"cancel removed");
            File mods=new File(temp,"mods");mods.mkdirs();File config=new File(temp,"mods.json");
            File previous=new File(mods,"Existing/dvdroot_ps4/menu/common.tpf.dcx");previous.getParentFile().mkdirs();Files.write(previous.toPath(),data);
            SafeWrite.write(config,"{\"order\":[\"Existing\"],\"disabled\":[]}");
            File archive=zip(temp,"New.zip","folder/dvdroot_ps4/menu/common.tpf.dcx","replacement");
            File preview=new File(temp,"preview");ModBrowserInstall.unpack(archive,preview,Long.MAX_VALUE,(d,t)->{},()->false);
            List<String> shared=ModBrowserInstall.conflicts(preview,new ModManager(mods,config));ok(shared.size()==1&&shared.get(0).contains("Existing"),"conflicts shown");
            String installed=ModBrowserInstall.install(archive,"New.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->false);
            ModLoader.Settings settings=ModManager.readConfig(config);ok(settings.disabled.contains(installed),"new mod persisted disabled");ok(!settings.disabled.contains("Existing"),"existing enabled choice retained");ok(settings.order.get(0).equals("Existing"),"load order retained");ok(Arrays.equals(data,Files.readAllBytes(previous.toPath())),"existing file untouched");
            ModManager.Mod row=new ModManager(mods,config).list().mods.stream().filter(m->m.name.equals(installed)).findFirst().get();ok(row.valid&&!row.enabled,"installed disabled after fresh listing");
            String second=ModBrowserInstall.install(archive,"New.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->false);ok(!second.equals(installed),"existing mod never overwritten");
            byte[] savedConfig=Files.readAllBytes(config.toPath());
            File bad=zip(temp,"Bad.zip","eboot.bin","not allowed");reject(()->ModBrowserInstall.install(bad,"Bad.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->false),"executable mod refused");ok(Arrays.equals(savedConfig,Files.readAllBytes(config.toPath())),"bad archive leaves settings unchanged");
            File traversal=zip(temp,"Escape.zip","../escape.txt","no");reject(()->ModBrowserInstall.install(traversal,"Escape.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->false),"zip slip rejected");
            reject(()->ModBrowserInstall.install(archive,"Cancel.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->true),"cancelled install");
            ok(Arrays.stream(mods.list()).noneMatch(n->n.startsWith(".browser-")),"failed/cancelled staging cleaned");
            SafeWrite.write(config,"broken json");reject(()->ModBrowserInstall.install(archive,"BrokenConfig.zip",mods,config,Long.MAX_VALUE,(d,t)->{},()->false),"do not overwrite broken config");ok(!new File(mods,"BrokenConfig").exists(),"no published mod on config failure");
            if(args.length>1){File realPreview=new File(temp,"real-preview");ModBrowserInstall.unpack(new File(args[1]),realPreview,Long.MAX_VALUE,(d,t)->{},()->false);ok(new ModManager(temp,null).validate("real-preview").valid,"real downloaded prompts archive data-compatible");}
            System.out.println("PASS: "+checks+" mod browser checks");
        }finally{ModArchive.deleteTree(temp);}
    }
}
