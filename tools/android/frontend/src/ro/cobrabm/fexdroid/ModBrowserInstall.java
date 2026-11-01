// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Isolated preview, then hidden staging + disabled configuration before atomic publication. */
final class ModBrowserInstall {
    static final long MAX_UNPACKED=1L<<30;
    static void unpack(File archive,File target,long free,ModArchive.Progress progress,BooleanSupplier cancelled) throws IOException {
        ModArchive.extract(archive,target,free,MAX_UNPACKED,20000,progress,cancelled);
        ModManager.Mod check=new ModManager(target.getParentFile(),null).validate(target.getName());
        if(!check.valid || check.problem!=null){ModArchive.deleteTree(target);throw new IOException(check.problem==null ? "Not a usable data mod" : check.problem);}
    }
    static List<String> conflicts(File preview,ModManager manager) throws IOException {
        Set<String> paths=new HashSet<>();for(ModLoader.ModFile f:ModLoader.modFiles(preview))paths.add(FsUtil.casefold(f.relative));
        List<String> out=new ArrayList<>();ModManager.Listing listing=manager.list();if(listing.error!=null)throw new IOException(listing.error);
        for(ModManager.Mod mod:listing.mods){if(!mod.valid)continue;int count=0;for(ModLoader.ModFile f:ModLoader.modFiles(new File(manager.modsDir(),mod.name)))if(paths.contains(FsUtil.casefold(f.relative)))count++;
            if(count>0)out.add(mod.name+": "+count+" shared file(s)"+(mod.enabled ? " (enabled)" : " (disabled)"));}
        return out;
    }
    static String install(File archive,String name,File mods,File config,long free,ModArchive.Progress progress,BooleanSupplier cancelled) throws IOException {
        if(!mods.isDirectory() && !mods.mkdirs())throw new IOException("Cannot create mods folder");
        if(FsUtil.isSymlink(mods))throw new IOException("The mods folder must not be a symbolic link");
        File target=ModArchive.uniqueDir(mods,ModArchive.modName(name));
        File staging=new File(mods,".browser-"+UUID.randomUUID());boolean published=false;
        try {
            unpack(archive,staging,free,progress,cancelled);
            if(cancelled.getAsBoolean())throw new CancellationException();
            ModLoader.Settings settings=ModManager.readConfig(config);
            List<String> order=new ArrayList<>(settings.order);if(!order.contains(target.getName()))order.add(target.getName());
            Set<String> disabled=new LinkedHashSet<>(settings.disabled);disabled.add(target.getName());
            // A crash before rename leaves only an ignored hidden folder and a harmless disabled name.
            SafeWrite.write(config,"{\"order\":"+json(order)+",\"disabled\":"+json(disabled)+"}");
            if(cancelled.getAsBoolean())throw new CancellationException();
            if(target.exists() || !staging.renameTo(target))throw new IOException("Could not finish installing the mod");
            published=true;return target.getName();
        }finally{if(!published)ModArchive.deleteTree(staging);}
    }
    private static String json(Collection<String> values){StringBuilder b=new StringBuilder("[");for(String value:values){if(b.length()>1)b.append(',');b.append('"');for(char c:value.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}b.append('"');}return b.append(']').toString();}
}
