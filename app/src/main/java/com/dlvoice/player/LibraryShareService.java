package com.dlvoice.player;

import android.app.*;
import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicitly enabled Wi-Fi library sharing. No paths supplied by a client are opened. */
public final class LibraryShareService extends Service {
    static final int PORT=17856;
    static volatile LibraryShareService live;
    static volatile String lastError="";
    private volatile ServerSocket server;
    private volatile String code="",address="";
    private volatile long codeUntil;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Long> tokens=new ConcurrentHashMap<>();
    private final Map<String,Library.Track> tracks=new ConcurrentHashMap<>();
    private final Map<String,String> owners=new ConcurrentHashMap<>();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(6,6,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12));
    private SharedPreferences prefs;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private int badPairs;
    private final Handler lifecycle=new Handler(Looper.getMainLooper());
    private final Runnable checkWifi=new Runnable(){public void run(){if(live!=LibraryShareService.this)return;if(!address.equals(wifiAddress(LibraryShareService.this))){lastError=UiLanguage.text(LibraryShareService.this, R.string.text_wi_fi_changed_restart_sharing);stopSelf();return;}lifecycle.postDelayed(this,5000);}};
    private final AtomicLong revision=new AtomicLong(System.currentTimeMillis());
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,key)->{
        if(key!=null&&(key.startsWith("metadata:")||key.startsWith("favorite:")||key.startsWith("directoryIndex:")||key.equals("directories")||key.startsWith("cover")||key.equals("lyricTransparency")||key.equals("workSort")||key.equals("reduceMotion")))revision.incrementAndGet();
    };
    static String wifiAddress(Context context){
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
            LinkProperties props=cm.getLinkProperties(n);if(props==null)continue;
            for(LinkAddress a:props.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress())return a.getAddress().getHostAddress();
        }return "";
    }
    static String status(Context context){LibraryShareService s=live;return s==null?(lastError.isEmpty()?context.getString(R.string.text_sharing_is_off):lastError):"http://"+s.address+":"+PORT+context.getString(R.string.text_pairing_code)+(System.currentTimeMillis()<s.codeUntil?s.code:context.getString(R.string.text_expired_refresh_the_code))+context.getString(R.string.text_paired)+s.tokens.size()+context.getString(R.string.text_devices);}
    static void refreshCode(){LibraryShareService s=live;if(s!=null)s.newCode();}
    private synchronized void newCode(){code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));codeUntil=System.currentTimeMillis()+300_000;badPairs=0;}
    @Override public void onCreate(){
        super.onCreate();prefs=getSharedPreferences("library",MODE_PRIVATE);prefs.registerOnSharedPreferenceChangeListener(changed);
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("libraryShare",UiLanguage.text(LibraryShareService.this, R.string.text_local_network_sharing),NotificationManager.IMPORTANCE_LOW);channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);manager.createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if("stop".equals(intent==null?null:intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(server!=null)return START_NOT_STICKY;
        address=wifiAddress(this);if(address.isEmpty()){lastError=UiLanguage.text(LibraryShareService.this, R.string.text_connect_to_wi_fi_first);stopSelf();return START_NOT_STICKY;}
        try{
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,41,new Intent(this,LibraryShareService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
            startForeground(41,new Notification.Builder(this,"libraryShare").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.app_name) + UiLanguage.text(LibraryShareService.this, R.string.text_local_network_sharing_2)).setContentText(UiLanguage.text(LibraryShareService.this, R.string.text_pc_can_access_the_library_tap_to_return)).setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel,UiLanguage.text(LibraryShareService.this, R.string.text_stop_sharing),stop).setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true).setOnlyAlertOnce(true).build());
            server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName(address),PORT),12);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Yinqi:LibraryShare");wake.acquire();
            WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"YinqiShare");wifi.acquire();
            newCode();lastError="";live=this;lifecycle.postDelayed(checkWifi,5000);
            new Thread(()->{while(server!=null&&!server.isClosed()){try{Socket socket=server.accept();socket.setSoTimeout(15000);sockets.add(socket);try{workers.execute(()->serve(socket));}catch(RejectedExecutionException busy){sockets.remove(socket);socket.close();}}catch(IOException end){break;}}},"YinqiShare").start();
        }catch(Exception e){lastError=UiLanguage.text(LibraryShareService.this, R.string.text_could_not_start_sharing_check_wi_fi_and_port_ava);stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    private static String id(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(int i=0;i<16;i++)out.append(String.format(Locale.ROOT,"%02x",bytes[i]));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private synchronized JSONObject library()throws Exception{
        long snapshotRevision=revision.get();
        UiLanguage language=UiLanguage.current(this);
        JSONArray roots=new JSONArray(),works=new JSONArray();Map<String,Library.Track> latest=new HashMap<>();Map<String,String> latestOwners=new HashMap<>();
        JSONArray dirs=new JSONArray(prefs.getString("directories","[]"));
        for(int i=0;i<dirs.length();i++){
            String root=dirs.getString(i),rootId=id(root),name=Uri.parse(root).getLastPathSegment();int colon=name==null?-1:Math.max(name.lastIndexOf(':'),name.lastIndexOf('/'));if(name==null)name=UiLanguage.text(LibraryShareService.this, R.string.text_audio_folders);else if(colon>=0)name=name.substring(colon+1);
            roots.put(new JSONObject().put("id",rootId).put("name",name));
            for(Library.Work work:LibraryIndex.decode(prefs.getString("directoryIndex:"+root,"[]"))){
                String number=Metadata.onlineId(work.id),key=id(root+"|"+work.identity+"|"+work.source);JSONObject info=Metadata.saved(prefs,work.id);String translated=Translation.cached(info, language),title=translated.isEmpty()?info.optString("title",number):translated;if(title.trim().isEmpty())title=number;if(Library.UNNAMED.equals(work.id))title=UiLanguage.text(LibraryShareService.this, R.string.text_untitled_work);
                JSONArray audio=new JSONArray();
                for(Library.Track track:work.tracks){String trackId=id(track.uri);latest.put(trackId,track);latestOwners.put(trackId,work.id);int slash=track.name.lastIndexOf('/');audio.put(new JSONObject().put("id",trackId).put("name",slash<0?track.name:track.name.substring(slash+1)).put("path",track.name).put("folder",slash<0?"":track.name.substring(0,slash)).put("source",key).put("work",number).put("lrc",!track.lrc.isEmpty()).put("position",prefs.getInt("position:"+track.uri,0)));}
                works.put(new JSONObject().put("key",key).put("id",number).put("root",rootId).put("title",title).put("resume",id(prefs.getString("work:"+work.id,""))).put("original",info.optString("title")).put("circle",info.optString("circle")).put("actors",info.optString("actors")).put("favorite",prefs.getBoolean("favorite:"+number,false)).put("addedAt",work.addedAt).put("modifiedAt",work.modifiedAt).put("tracks",audio));
            }
        }
        tracks.keySet().retainAll(latest.keySet());owners.keySet().retainAll(latestOwners.keySet());tracks.putAll(latest);owners.putAll(latestOwners);
        return new JSONObject().put("protocol",1).put("revision",snapshotRevision).put("roots",roots).put("works",works).put("settings",settings());
    }
    private JSONObject settings()throws JSONException{return new JSONObject().put("coverBackground",prefs.getBoolean("coverBackground",true)).put("coverOpacity",prefs.getInt("coverOpacity",48)).put("coverBrightness",prefs.getInt("coverBrightness",40)).put("lyricTransparency",prefs.getInt("lyricTransparency",0)).put("reduceMotion",prefs.getBoolean("reduceMotion",false)).put("workSort",prefs.getInt("workSort",0));}
    private void serve(Socket socket){
        try{
            Socket client=socket;
            InputStream in=client.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int b,state=0;
            while((b=in.read())!=-1){header.write(b);if(header.size()>16384)throw new IOException("Header too big");state=(state==0&&b==13)?1:(state==1&&b==10)?2:(state==2&&b==13)?3:(state==3&&b==10)?4:0;if(state==4)break;}
            String[] lines=header.toString("ISO-8859-1").split("\r\n");if(lines.length==0)return;String[] first=lines[0].split(" ");if(first.length!=3)return;
            String method=first[0];URI target=new URI(first[1]);String path=target.getPath();Map<String,String> headers=new HashMap<>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0)headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
            int size=Integer.parseInt(headers.getOrDefault("content-length","0"));if(size<0||size>131072){send(client,413,"text/plain",new byte[0],false);return;}
            byte[] body=new byte[size];int read=0,n;while(read<size&&(n=in.read(body,read,size-read))>0)read+=n;if(read!=size)throw new EOFException();
            JSONObject payload=size==0?new JSONObject():new JSONObject(new String(body,StandardCharsets.UTF_8));
            if("/pair".equals(path)&&"POST".equals(method)){pair(client,payload);return;}
            String auth=headers.getOrDefault("authorization","");String token=auth.startsWith("Bearer ")?auth.substring(7):"";
            if(!tokens.containsKey(token)){json(client,401,new JSONObject().put("error",UiLanguage.text(LibraryShareService.this, R.string.text_pair_again)));return;}
            if(!method.equals("GET")&&!method.equals("HEAD")&&!method.equals("POST")){json(client,405,new JSONObject().put("error","Method"));return;}
            if(path.equals("/api/library")&&method.equals("GET")){json(client,200,library());return;}
            if(path.equals("/api/state")&&method.equals("GET")){json(client,200,new JSONObject().put("revision",revision.get()).put("address",address));return;}
            if(path.equals("/api/session")){
                if(method.equals("POST")){JSONArray queue=payload.getJSONArray("queue");if(queue.length()>2000)throw new IOException("Queue limit");for(int i=0;i<queue.length();i++)if(!queue.getString(i).matches("[a-f0-9]{32}"))throw new IOException("Invalid track");prefs.edit().putString("desktopSession",payload.toString()).apply();}
                json(client,200,new JSONObject(prefs.getString("desktopSession","{}")));return;
            }
            if(path.equals("/api/favorite")&&method.equals("POST")){String work=payload.getString("id");if(!work.matches("RJ[0-9]+|unidentified"))throw new IOException("Invalid work");prefs.edit().putBoolean("favorite:"+work,payload.getBoolean("favorite")).apply();json(client,200,new JSONObject().put("ok",true));return;}
            if(path.equals("/api/metadata")&&method.equals("POST")){
                String work=payload.getString("id");if(!work.matches("RJ[0-9]+"))throw new IOException("Invalid work");
                synchronized(Metadata.class){JSONObject info=Metadata.saved(prefs,work);for(String key:new String[]{"title","circle","actors"})if(payload.has(key)){String value=payload.getString(key).trim();if(value.length()>2000)throw new IOException("Field too long");info.put(key,value).put("manual_"+key,true);}prefs.edit().putString(Metadata.key(work),info.toString()).apply();}
                json(client,200,new JSONObject().put("ok",true));return;
            }
            if(path.equals("/api/settings")&&method.equals("POST")){
                SharedPreferences.Editor edit=prefs.edit();for(String key:new String[]{"coverOpacity","coverBrightness","lyricTransparency","workSort"})if(payload.has(key))edit.putInt(key,Math.max(0,Math.min(key.equals("workSort")?5:100,payload.getInt(key))));for(String key:new String[]{"coverBackground","reduceMotion"})if(payload.has(key))edit.putBoolean(key,payload.getBoolean(key));edit.apply();json(client,200,settings());return;
            }
            if(path.startsWith("/cover/")&&(method.equals("GET")||method.equals("HEAD"))){String work=path.substring(7);if(!work.matches("RJ[0-9]+|unidentified")){json(client,404,new JSONObject());return;}File file=Metadata.cover(this,work);if(!file.isFile()){send(client,404,"text/plain",new byte[0],method.equals("HEAD"));return;}send(client,200,"image/jpeg",java.nio.file.Files.readAllBytes(file.toPath()),method.equals("HEAD"));return;}
            String trackId=path.substring(path.lastIndexOf('/')+1);Library.Track track=tracks.get(trackId);if(track==null){json(client,404,new JSONObject().put("error",UiLanguage.text(LibraryShareService.this, R.string.text_track_removed_refresh_the_library)));return;}
            if(path.startsWith("/audio/")&&(method.equals("GET")||method.equals("HEAD"))){audio(client,track,headers.get("range"),method.equals("HEAD"));return;}
            if(path.startsWith("/lyrics/")&&method.equals("GET")){JSONArray cues=new JSONArray();for(Lrc.Cue cue:Lrc.read(getContentResolver(),track.lrc))cues.put(new JSONObject().put("time",cue.time).put("text",cue.text));json(client,200,new JSONObject().put("cues",cues));return;}
            if(path.startsWith("/art/")&&(method.equals("GET")||method.equals("HEAD"))){Bitmap bitmap=Artwork.embedded(this,track.uri,1024);if(bitmap==null){send(client,404,"text/plain",new byte[0],false);return;}ByteArrayOutputStream image=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,88,image);bitmap.recycle();send(client,200,"image/jpeg",image.toByteArray(),method.equals("HEAD"));return;}
            if(path.startsWith("/progress/")){
                if(method.equals("POST")){int pos=Math.max(0,payload.getInt("position"));String work=owners.get(trackId);prefs.edit().putInt("position:"+track.uri,pos).putString("last",track.uri).putString("work:"+work,track.uri).apply();}
                json(client,200,new JSONObject().put("position",prefs.getInt("position:"+track.uri,0)));return;
            }
            json(client,404,new JSONObject().put("error","Not found"));
        }catch(Exception failure){try{json(socket,500,new JSONObject().put("error",UiLanguage.text(LibraryShareService.this, R.string.text_could_not_read_the_file_check_folder_access_on_t)));}catch(Exception ignored){}}
        finally{sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    }
    private synchronized void pair(Socket socket,JSONObject payload)throws Exception{
        if(System.currentTimeMillis()>codeUntil||badPairs>=10||!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),payload.optString("code").getBytes(StandardCharsets.UTF_8))){badPairs++;json(socket,403,new JSONObject().put("error",UiLanguage.text(LibraryShareService.this, R.string.text_invalid_or_expired_code_refresh_it_on_the_phone)));return;}
        if(tokens.size()>=8){json(socket,429,new JSONObject().put("error",UiLanguage.text(LibraryShareService.this, R.string.text_connection_limit_reached_restart_sharing)));return;}
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP|android.util.Base64.URL_SAFE);tokens.put(token,System.currentTimeMillis());json(socket,200,new JSONObject().put("token",token).put("name",getString(R.string.app_name) + UiLanguage.text(LibraryShareService.this, R.string.text_mobile_library)).put("protocol",1));
    }
    private void audio(Socket client,Library.Track track,String range,boolean head)throws Exception{
        try(AssetFileDescriptor afd=getContentResolver().openAssetFileDescriptor(Uri.parse(track.uri),"r")){
            if(afd==null)throw new FileNotFoundException();long length=afd.getLength();if(length<0)length=afd.getParcelFileDescriptor().getStatSize();long start=0,end=length-1;
            if(range!=null&&length>0){try{if(!range.matches("bytes=[0-9]*-[0-9]*"))throw new Exception();String[] parts=range.substring(6).split("-",-1);if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new Exception();start=Math.max(0,length-suffix);}else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}if(start>end||start>=length)throw new Exception();}catch(Exception invalid){headers(client,416,"application/octet-stream",0,"Content-Range: bytes */"+length+"\r\n");return;}}
            long count=length<0?-1:end-start+1;
            String name=track.name.toLowerCase(Locale.ROOT),mime=name.endsWith(".mp3")?"audio/mpeg":name.endsWith(".wav")?"audio/wav":name.endsWith(".flac")?"audio/flac":name.endsWith(".ogg")||name.endsWith(".opus")?"audio/ogg":name.endsWith(".aac")?"audio/aac":"audio/mp4";
            try(InputStream input=afd.createInputStream()){
                long skip=start;while(skip>0){long step=input.skip(skip);if(step<=0){if(input.read()<0)throw new EOFException();step=1;}skip-=step;}
                headers(client,range!=null&&length>0?206:200,mime,count,"Accept-Ranges: bytes\r\n"+(range!=null&&length>0?"Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n":""));
                if(head)return;OutputStream output=client.getOutputStream();byte[] buffer=new byte[64*1024];int read;while((count<0||count>0)&&(read=input.read(buffer,0,count<0?buffer.length:(int)Math.min(buffer.length,count)))!=-1){output.write(buffer,0,read);if(count>=0)count-=read;}output.flush();
            }
        }
    }
    private static void headers(Socket c,int status,String type,long length,String extra)throws IOException{
        String head="HTTP/1.1 "+status+" "+(status==200?"OK":status==206?"Partial Content":"Response")+"\r\nContent-Type: "+type+"\r\n"+(length>=0?"Content-Length: "+length+"\r\n":"")+"Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"+extra+"\r\n";c.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
    }
    private static void send(Socket c,int status,String type,byte[] bytes,boolean head)throws IOException{headers(c,status,type,bytes.length,"");if(!head)c.getOutputStream().write(bytes);c.getOutputStream().flush();}
    private static void json(Socket c,int status,JSONObject json)throws IOException{send(c,status,"application/json; charset=utf-8",json.toString().getBytes(StandardCharsets.UTF_8),false);}
    static long libraryRevision(){LibraryShareService s=live;return s==null?0:s.revision.get();}
    @Override public void onDestroy(){
        lifecycle.removeCallbacksAndMessages(null);if(live==this)live=null;tokens.clear();prefs.unregisterOnSharedPreferenceChangeListener(changed);
        try{if(server!=null)server.close();}catch(IOException ignored){}for(Socket s:sockets)try{s.close();}catch(IOException ignored){}workers.shutdownNow();
        if(wake!=null&&wake.isHeld())wake.release();if(wifi!=null&&wifi.isHeld())wifi.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
