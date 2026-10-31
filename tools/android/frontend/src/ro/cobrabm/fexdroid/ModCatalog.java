// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Small reviewed catalog; no scraping, user credentials, arbitrary download URLs or executable mods. */
final class ModCatalog {
    static final int MAX_CATALOG=256*1024;
    static final long MAX_DOWNLOAD=512L<<20;
    static final String CATALOG_URL="https://raw.githubusercontent.com/emerytech/Bloodborne-Android/codex/integrated-thor-companion/tools/thor/assets/mod-catalog.json";
    static final class Entry {
        final String id,title,author,category,summary,page,url,filename,sha256;
        final long bytes;
        Entry(Map<?,?> m) throws IOException {
            id=field(m,"id",80); title=field(m,"title",160); author=field(m,"author",120);
            category=field(m,"category",60); summary=field(m,"summary",1200); page=field(m,"page",1000);
            if (!id.matches("[a-z0-9-]+") || !pageAllowed(page)) throw new IOException("Invalid catalog entry");
            url=optional(m,"url");filename=optional(m,"filename");sha256=optional(m,"sha256");
            Object n=m.get("bytes");bytes=n instanceof Number ? ((Number)n).longValue() : 0;
            if (!url.isEmpty() && (!downloadAllowed(url) || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.(zip|7z)")
                || !sha256.matches("[a-f0-9]{64}") || bytes<1 || bytes>MAX_DOWNLOAD)) throw new IOException("Invalid download metadata");
            if (url.isEmpty() && (!filename.isEmpty() || !sha256.isEmpty() || bytes!=0)) throw new IOException("Incomplete download metadata");
        }
        boolean matches(String query,boolean directOnly) {
            return (!directOnly || !url.isEmpty()) && (title+" "+author+" "+category+" "+summary).toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
        }
    }
    private static String optional(Map<?,?> m,String key) { Object v=m.get(key);return v instanceof String ? (String)v : ""; }
    private static String field(Map<?,?> m,String key,int limit) throws IOException {
        String v=optional(m,key);if(v.trim().isEmpty() || v.length()>limit)throw new IOException("Missing/oversized "+key);return v;
    }
    static List<Entry> parse(String text) throws IOException {
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_CATALOG)throw new IOException("Catalog too large");
        int depth=0;boolean quoted=false,escaped=false;
        for(char ch:text.toCharArray()){
            if(quoted){if(escaped)escaped=false;else if(ch=='\\')escaped=true;else if(ch=='"')quoted=false;}
            else if(ch=='"')quoted=true;else if(ch=='['||ch=='{'){if(++depth>16)throw new IOException("Catalog is too deeply nested");}else if(ch==']'||ch=='}')depth--;
        }
        Object value;
        try { value=MiniJson.parse(text); }catch(MiniJson.ParseException | RuntimeException e){throw new IOException("Invalid catalog JSON",e);}
        if(!(value instanceof Map))throw new IOException("Invalid catalog");
        Map<?,?> root=(Map<?,?>)value;Object version=root.get("schema"),items=root.get("entries");
        if(!(version instanceof Number) || ((Number)version).doubleValue()!=1.0 || !(items instanceof List) || ((List<?>)items).size()>100)throw new IOException("Unsupported catalog");
        List<Entry> out=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object item:(List<?>)items){if(!(item instanceof Map))throw new IOException("Invalid catalog row");Entry entry=new Entry((Map<?,?>)item);if(!ids.add(entry.id))throw new IOException("Duplicate mod ID");out.add(entry);}
        return Collections.unmodifiableList(out);
    }
    private static URI uri(String value) {
        try { URI u=new URI(value);return "https".equals(u.getScheme()) && u.getHost()!=null && u.getUserInfo()==null && u.getPort()==-1 && u.getFragment()==null ? u : null; }
        catch(URISyntaxException e){return null;}
    }
    static boolean pageAllowed(String value) {
        URI u=uri(value);if(u==null)return false;
        return ("www.nexusmods.com".equals(u.getHost()) && u.getPath().matches("/bloodborne/mods/[0-9]+"))
            || ("github.com".equals(u.getHost()) && u.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(/.*)?"));
    }
    static boolean downloadAllowed(String value) {
        URI u=uri(value);return u!=null && "github.com".equals(u.getHost()) && u.getQuery()==null
            && u.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+\\.(zip|7z)");
    }
    static boolean redirectAllowed(String value) {
        URI u=uri(value);return u!=null && Arrays.asList("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","raw.githubusercontent.com").contains(u.getHost());
    }
    interface Progress { void update(long done,long total); }
    static final class Network {
        private volatile HttpURLConnection connection;
        void cancel() { HttpURLConnection c=connection;if(c!=null)c.disconnect(); }
        private HttpURLConnection open(String address,BooleanSupplier cancelled) throws IOException {
            for(int i=0;i<6;i++) {
                if(cancelled.getAsBoolean())throw new CancellationException();
                if(!redirectAllowed(address))throw new IOException("Download host is not approved");
                HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();connection=c;
                if(cancelled.getAsBoolean()){c.disconnect();throw new CancellationException();}
                c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent","Bloodborne-Thor-Companion/0.5.0");c.setRequestProperty("Accept-Encoding","identity");
                int status;try{status=c.getResponseCode();}catch(IOException | RuntimeException e){c.disconnect();connection=null;throw e;}
                if(status==301||status==302||status==303||status==307||status==308){String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new IOException("Missing redirect");address=new URL(new URL(address),next).toString();continue;}
                if(status!=200){c.disconnect();throw new IOException("Server returned HTTP "+status);}
                return c;
            }
            throw new IOException("Too many redirects");
        }
        String catalog(BooleanSupplier cancelled) throws IOException {
            HttpURLConnection c=null;
            try { c=open(CATALOG_URL,cancelled);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;){if(cancelled.getAsBoolean())throw new CancellationException();if(out.size()+n>MAX_CATALOG)throw new IOException("Catalog too large");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }}finally{if(c!=null)c.disconnect();connection=null;}
        }
        void download(Entry entry,File target,BooleanSupplier cancelled,Progress progress) throws IOException {
            if(!downloadAllowed(entry.url))throw new IOException("Not a direct-download entry");
            HttpURLConnection c=null;boolean ok=false;
            try { c=open(entry.url,cancelled);long length=c.getContentLengthLong();if(length>=0 && length!=entry.bytes)throw new IOException("Download size changed; refresh the catalog");
                try(InputStream in=c.getInputStream()){verifiedCopy(in,target,entry.bytes,entry.sha256,cancelled,progress);}ok=true;
            }finally{if(c!=null)c.disconnect();connection=null;if(!ok)target.delete();}
        }
    }
    static void verifiedCopy(InputStream in,File target,long expected,String sha,BooleanSupplier cancelled,Progress progress) throws IOException {
        if(expected<1 || expected>MAX_DOWNLOAD)throw new IOException("Download exceeds limit");
        boolean ok=false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,last=0;byte[] b=new byte[65536];
            try(OutputStream out=new FileOutputStream(target)){
                for(int n;(n=in.read(b))!=-1;){if(cancelled.getAsBoolean())throw new CancellationException();count+=n;if(count>expected)throw new IOException("Download is larger than expected");out.write(b,0,n);digest.update(b,0,n);long now=System.nanoTime();if(now-last>150000000L){progress.update(count,expected);last=now;}}
            }
            if(cancelled.getAsBoolean())throw new CancellationException();
            StringBuilder hex=new StringBuilder();for(byte x:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",x&255));
            if(count!=expected || !hex.toString().equals(sha))throw new IOException("Download checksum/size mismatch; nothing installed");
            progress.update(count,expected);ok=true;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        finally{if(!ok)target.delete();}
    }
}
