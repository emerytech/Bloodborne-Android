package ro.cobrabm.fexdroid;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/** Backup and restore of the game's save data folder (savedata/&lt;user id&gt;/&lt;title id&gt;/...). Plain
 *  folder copies, no Android classes beyond java.io/nio, so it is tested on a desktop JDK. */
final class SaveBackup {
    private static final Pattern USER=Pattern.compile("[0-9]+");
    private static final Pattern TITLE=Pattern.compile("[A-Z]{4}[0-9]{5}(\\.memory)?");
    private SaveBackup() {}

    /** The app's save data folder under the runtime's --user directory. */
    static File saves(File base) { return new File(new File(base,"user"),"savedata"); }

    /** Whether the folder holds at least one save: &lt;numeric user id&gt;/&lt;title id like CUSA03173&gt;. */
    static boolean looksLikeSavedata(File dir) {
        File[] users=dir==null ? null : dir.listFiles();
        if (users==null) return false;
        for (File user : users) {
            if (!user.isDirectory() || !USER.matcher(user.getName()).matches()) continue;
            File[] titles=user.listFiles();
            if (titles==null) continue;
            for (File title : titles) if (TITLE.matcher(title.getName()).matches()) return true;
        }
        return false;
    }
    /** The picked folder itself, or its "savedata" subfolder (a backup folder made by {@link #backup}), when it is
     *  save data; null otherwise. */
    static File find(File picked) {
        if (picked==null) return null;
        if (looksLikeSavedata(picked)) return picked;
        File inside=new File(picked,"savedata");
        return looksLikeSavedata(inside) ? inside : null;
    }
    static boolean hasSaves(File savedata) { return looksLikeSavedata(savedata); }

    /** Copies the saves to downloads/Bloodborne-saves-STAMP/savedata and returns that backup folder. */
    static File backup(File savedata,File downloads,Date when) throws IOException {
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(when);
        File dest=new File(downloads,"Bloodborne-saves-"+stamp);
        for (int n=2; dest.exists(); n++) dest=new File(downloads,"Bloodborne-saves-"+stamp+"-"+n);
        File target=new File(dest,"savedata");
        try { copyTree(savedata,target); }
        catch (IOException | RuntimeException e) { deleteTree(dest); throw e; }
        return dest;
    }
    /** Replaces the saves with a copy of source: copied beside the folder first, so a failed copy leaves the
     *  current saves untouched. */
    static void restore(File savedata,File source) throws IOException {
        File parent=savedata.getParentFile();
        File fresh=new File(parent,"savedata.restoring"), old=new File(parent,"savedata.replaced");
        deleteTree(fresh); deleteTree(old);
        try { copyTree(source,fresh); }
        catch (IOException | RuntimeException e) { deleteTree(fresh); throw e; }
        if (savedata.exists() && !savedata.renameTo(old)) { deleteTree(fresh); throw new IOException("Cannot move the current saves aside"); }
        if (!fresh.renameTo(savedata)) {
            if (old.exists()) old.renameTo(savedata); // put the old saves back
            deleteTree(fresh);
            throw new IOException("Cannot put the restored saves in place");
        }
        deleteTree(old);
    }

    static void copyTree(File from,File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Cannot create "+to);
            File[] children=from.listFiles();
            if (children==null) throw new IOException("Cannot read "+from);
            for (File child : children) copyTree(child,new File(to,child.getName()));
            to.setLastModified(from.lastModified());
        } else {
            Files.copy(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
            to.setLastModified(from.lastModified());
        }
    }
    static void deleteTree(File f) {
        File[] children=f.isDirectory() && !Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (children!=null) for (File c : children) deleteTree(c);
        f.delete();
    }

    // ---- Auto-backup with rotation (separate from the manual "Bloodborne-saves-*" backups) ----
    private static final String AUTO_PREFIX="Bloodborne-autosave-";
    private static final Pattern AUTO=Pattern.compile(Pattern.quote(AUTO_PREFIX)+"[0-9]{8}-[0-9]{6}(-[0-9]+)?");

    /** Auto-backup with rotation. When the live saves differ from the newest auto-backup, copies them to a new
     *  Bloodborne-autosave-STAMP/savedata, then keeps only the newest {@code keep} auto-backups — the manual
     *  "Bloodborne-saves-*" backups are never touched. Returns the new backup folder, or null when nothing was
     *  written: no saves, or unchanged since the last auto-backup. keep &lt; 1 keeps them all. */
    static File autoBackup(File savedata,File downloads,Date when,int keep) throws IOException {
        if (!hasSaves(savedata)) return null;
        File[] existing=listAutoBackups(downloads);
        if (existing.length>0 && signature(savedata).equals(signature(new File(existing[existing.length-1],"savedata")))) return null;
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(when);
        File dest=new File(downloads,AUTO_PREFIX+stamp);
        for (int n=2; dest.exists(); n++) dest=new File(downloads,AUTO_PREFIX+stamp+"-"+n);
        try { copyTree(savedata,new File(dest,"savedata")); }
        catch (IOException | RuntimeException e) { deleteTree(dest); throw e; }
        prune(downloads,keep);
        return dest;
    }

    /** Existing auto-backup folders, oldest first (the timestamp name sorts chronologically). */
    static File[] listAutoBackups(File downloads) {
        File[] all=downloads==null ? null : downloads.listFiles();
        if (all==null) return new File[0];
        java.util.List<File> out=new java.util.ArrayList<>();
        for (File f : all) if (f.isDirectory() && AUTO.matcher(f.getName()).matches()) out.add(f);
        java.util.Collections.sort(out,(a,b) -> a.getName().compareTo(b.getName()));
        return out.toArray(new File[0]);
    }

    /** Deletes the oldest auto-backups until at most {@code keep} remain. keep &lt; 1 keeps them all. */
    static void prune(File downloads,int keep) {
        if (keep<1) return;
        File[] list=listAutoBackups(downloads);
        for (int i=0; i<list.length-keep; i++) deleteTree(list[i]);
    }

    /** A content signature of a save tree — file count, total size and newest mtime. Equal signatures mean the
     *  saves have not changed since a backup (copyTree preserves mtimes, so a fresh backup matches its source). */
    static String signature(File dir) {
        long[] acc={0,0,0}; // count, total size, newest mtime
        sign(dir,acc);
        return acc[0]+":"+acc[1]+":"+acc[2];
    }
    private static void sign(File f,long[] acc) {
        if (f==null) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids=f.listFiles();
            if (kids!=null) for (File k : kids) sign(k,acc);
        } else if (f.isFile()) {
            acc[0]++; acc[1]+=f.length(); long m=f.lastModified(); if (m>acc[2]) acc[2]=m;
        }
    }
}
