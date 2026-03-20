package org.telegram.messenger;

import fi.iki.elonen.NanoHTTPD;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

public class LocalStreamServer extends NanoHTTPD {

    private static final int PORT = 39154;

    public LocalStreamServer() throws IOException { super(PORT); }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        int q = uri.indexOf('?');
        if (q >= 0) uri = uri.substring(0, q);
        StreamingController sc = StreamingController.getInstance();

        switch (uri) {
            case "/":
            case "/index.html":
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildHtml());

            case "/status": {
                long buffered = 0;
                if (sc.videoFile != null && sc.duration > 0 && !sc.fileName.isEmpty()) {
                    float prog = FileLoader.getInstance(sc.currentAccount)
                        .getBufferedProgressFromPosition(sc.position / (float) sc.duration, sc.fileName);
                    buffered = (long)(prog * sc.duration);
                }
                String json = String.format(
                    "{\"title\":\"%s\",\"hasNext\":%b,\"hasPrev\":%b,\"duration\":%d,\"position\":%d,\"buffered\":%d,\"speed\":%.2f,\"version\":%d}",
                    escapeJson(sc.videoTitle), sc.hasNext, sc.hasPrev,
                    sc.duration, sc.position, buffered, sc.speed, sc.version);
                return jsonOK(json);
            }

            case "/control": {
                if (!Method.POST.equals(session.getMethod())) break;
                try {
                    Map<String, String> files = new java.util.HashMap<>();
                    session.parseBody(files);
                    String body = files.get("postData");
                    if (body == null) body = "";
                    String action = extractJson(body, "action");
                    if ("next".equals(action) && sc.nextListener != null) {
                        sc.nextListener.run();
                    } else if ("prev".equals(action) && sc.prevListener != null) {
                        sc.prevListener.run();
                    } else if ("seek".equals(action)) {
                        try { sc.onBrowserSeek(Long.parseLong(extractJson(body, "position"))); } catch (Exception ignored) {}
                    } else if ("stop".equals(action)) {
                        sc.stopStreaming();
                    } else if ("updatepos".equals(action)) {
                        try { sc.position = Long.parseLong(extractJson(body, "position")); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                return jsonOK("{\"ok\":true}");
            }

            case "/video": {
                File f = sc.videoFile;
                if (f == null || !f.exists())
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no video");
                return serveFile(session, f);
            }

            case "/thumb": {
                File f = sc.videoFile;
                if (f == null || !f.exists())
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no thumb");
                return serveThumb(f);
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
    }

    private Response serveFile(IHTTPSession session, File file) {
        long fileSize = file.length();
        String range = session.getHeaders().get("range");
        long start = 0, end = fileSize - 1;
        Response.Status status = Response.Status.OK;
        if (range != null && range.startsWith("bytes=")) {
            status = Response.Status.PARTIAL_CONTENT;
            String[] parts = range.substring(6).split("-");
            try { start = Long.parseLong(parts[0].trim()); } catch (Exception ignored) {}
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                try { end = Long.parseLong(parts[1].trim()); } catch (Exception ignored) {}
            }
            if (end >= fileSize) end = fileSize - 1;
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            fis.getChannel().position(start);
            java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis, 256 * 1024);
            String mime = file.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            Response r = newFixedLengthResponse(status, mime, bis, end - start + 1);
            r.addHeader("Accept-Ranges", "bytes");
            r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response serveThumb(File file) {
        try {
            Bitmap bmp = ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            if (bmp == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no thumb");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            byte[] bytes = baos.toByteArray();
            Response tr = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
            tr.addHeader("Cache-Control", "max-age=10");
            return tr;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private String buildHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head>");
        sb.append("<meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Momogram Stream</title>");
        sb.append("<style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{font-family:'Segoe UI',sans-serif;background:#030712;color:#fff;min-height:100vh;display:flex;flex-direction:column}");
        sb.append("nav{background:rgba(3,7,18,0.9);border-bottom:1px solid #1f2937;padding:0 16px;height:52px;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:100}");
        sb.append(".nav-logo{font-size:15px;font-weight:700}");
        sb.append(".nav-title{font-size:12px;color:#9ca3af;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:60vw}");
        sb.append("#ov{position:fixed;inset:0;background:#030712;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:16px;z-index:9999}");
        sb.append("#ov.h{display:none}");
        sb.append(".spinner{width:36px;height:36px;border:3px solid #1f2937;border-top-color:#3b82f6;border-radius:50%;animation:spin 0.8s linear infinite}");
        sb.append("@keyframes spin{to{transform:rotate(360deg)}}");
        sb.append("#ov p{color:#9ca3af;font-size:14px;text-align:center;max-width:260px}");
        sb.append("#vw{background:#000;width:100%}");
        sb.append("video{width:100%;display:block;max-height:56vw}");
        sb.append("@media(orientation:landscape){video{max-height:90vh}}");
        sb.append(".ctrl{background:#0d1117;border-bottom:1px solid #1f2937;padding:10px 12px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}");
        sb.append(".btn{padding:7px 12px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px;display:inline-flex;align-items:center;gap:5px;-webkit-tap-highlight-color:transparent}");
        sb.append(".btn svg{width:14px;height:14px;fill:currentColor;pointer-events:none}");
        sb.append(".ghost{background:#1f2937;color:#e5e7eb;border:1px solid #374151}");
        sb.append(".ghost:hover{background:#374151}");
        sb.append(".ghost.dim{opacity:0.3;pointer-events:none}");
        sb.append(".stop{background:#dc2626;color:#fff}");
        sb.append(".sp{padding:5px 9px;border-radius:5px;border:1px solid #374151;background:#1f2937;color:#9ca3af;cursor:pointer;font-size:12px;font-weight:600;-webkit-tap-highlight-color:transparent}");
        sb.append(".sp.on{background:#3b82f6;color:#fff;border-color:#3b82f6}");
        sb.append(".card{background:#1f2937;border:1px solid #374151;border-radius:10px;padding:12px;margin:12px;display:flex;gap:12px;align-items:center}");
        sb.append(".thumb{width:72px;height:46px;background:#111827;border-radius:5px;overflow:hidden;flex-shrink:0;display:flex;align-items:center;justify-content:center;font-size:22px}");
        sb.append(".thumb img{width:100%;height:100%;object-fit:cover}");
        sb.append(".info{flex:1;min-width:0}");
        sb.append(".ttl{font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}");
        sb.append(".sub{font-size:11px;color:#6b7280;margin-top:2px}");
        sb.append(".dot{display:inline-block;width:6px;height:6px;background:#10b981;border-radius:50%;margin-right:4px;animation:pulse 1.5s infinite}");
        sb.append("@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}");
        sb.append(".nav2{display:flex;gap:8px;padding:0 12px 12px}");
        sb.append(".nc{flex:1;background:#1f2937;border:1px solid #374151;border-radius:8px;overflow:hidden;cursor:pointer;-webkit-tap-highlight-color:transparent}");
        sb.append(".nc:hover{border-color:#3b82f6}");
        sb.append(".nc.dim{opacity:0.3;pointer-events:none}");
        sb.append(".nct{aspect-ratio:16/9;background:#111827;display:flex;align-items:center;justify-content:center;font-size:24px}");
        sb.append(".ncl{font-size:10px;font-weight:700;text-transform:uppercase;color:#6b7280;padding:6px 8px 2px}");
        sb.append(".nctx{font-size:11px;color:#d1d5db;padding:0 8px 8px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}");
        sb.append(".kbd{font-size:11px;color:#6b7280;padding:8px 12px 12px;line-height:1.8}");
        sb.append(".k{display:inline-block;background:#1f2937;border:1px solid #374151;border-radius:4px;padding:1px 5px;font-family:monospace;font-size:10px;color:#9ca3af}");
        sb.append("</style></head><body>");
        sb.append("<div id='ov'><div class='spinner'></div><p id='om'>Connecting to phone...</p></div>");
        sb.append("<nav><div class='nav-logo'>&#128225; Momogram</div><span class='nav-title' id='nt'></span></nav>");
        sb.append("<div id='vw'><video id='v' playsinline controls preload='auto'></video></div>");
        sb.append("<div class='ctrl'>");
        sb.append("<button class='btn ghost dim' id='pvb' onclick='prevVideo()'><svg viewBox='0 0 24 24'><path d='M6 6h2v12H6zm3.5 6 8.5 6V6z'/></svg>Prev</button>");
        sb.append("<button class='btn ghost dim' id='nxb' onclick='nextVideo()'>Next<svg viewBox='0 0 24 24'><path d='M6 18l8.5-6L6 6v12zm8.5-6L21 18V6z'/></svg></button>");
        sb.append("<div style='flex:1'></div>");
        sb.append("<div id='spd' style='display:flex;gap:4px'></div>");
        sb.append("<button class='btn stop' onclick='stopStream()'>&#9632; Stop</button>");
        sb.append("</div>");
        sb.append("<div class='card'>");
        sb.append("<div class='thumb'><img id='th' src='/thumb' onerror=\"this.style.display='none';this.nextSibling.style.display='flex'\"><span style='display:none'>&#127916;</span></div>");
        sb.append("<div class='info'><div class='ttl' id='npt'>Loading...</div><div class='sub'><span class='dot'></span>Live Stream</div></div>");
        sb.append("</div>");
        sb.append("<div class='nav2'>");
        sb.append("<div class='nc dim' id='pvc' onclick='prevVideo()'><div class='nct'>&#9198;</div><div class='ncl'>Previous</div><div class='nctx'>-</div></div>");
        sb.append("<div class='nc dim' id='nxc' onclick='nextVideo()'><div class='nct'>&#9197;</div><div class='ncl'>Next</div><div class='nctx'>-</div></div>");
        sb.append("</div>");
        sb.append("<div class='kbd'>");
        sb.append("<span class='k'>Space</span>/<span class='k'>K</span> Play/Pause &nbsp;");
        sb.append("<span class='k'>&#8592;</span>/<span class='k'>J</span> -10s &nbsp;");
        sb.append("<span class='k'>&#8594;</span>/<span class='k'>L</span> +10s &nbsp;");
        sb.append("<span class='k'>&#8593;</span>/<span class='k'>&#8595;</span> Volume &nbsp;");
        sb.append("<span class='k'>M</span> Mute &nbsp;");
        sb.append("<span class='k'>F</span> Fullscreen &nbsp;");
        sb.append("<span class='k'>N</span>/<span class='k'>P</span> Next/Prev &nbsp;");
        sb.append("<span class='k'>,</span>/<span class='k'>.</span> Speed");
        sb.append("</div>");
        sb.append("<script>");
        sb.append("var v=document.getElementById('v');");
        sb.append("var ov=document.getElementById('ov'),om=document.getElementById('om');");
        sb.append("var nt=document.getElementById('nt'),npt=document.getElementById('npt'),th=document.getElementById('th');");
        sb.append("var pvb=document.getElementById('pvb'),nxb=document.getElementById('nxb');");
        sb.append("var pvc=document.getElementById('pvc'),nxc=document.getElementById('nxc');");
        sb.append("var lastTitle='',lastVersion=-1,curSpeed=2,userSpeed=false,userSpeedTimer=null;");
        sb.append("var SPEEDS=[0.25,0.5,0.75,1,1.25,1.5,1.75,2,2.5,3];");
        sb.append("var spdDiv=document.getElementById('spd');");
        sb.append("SPEEDS.forEach(function(s){");
        sb.append("  var b=document.createElement('button');");
        sb.append("  b.className='sp'+(s===2?' on':'');");
        sb.append("  b.textContent=s+'x';");
        sb.append("  b.onclick=function(){");
        sb.append("    v.playbackRate=s;curSpeed=s;userSpeed=true;");
        sb.append("    clearTimeout(userSpeedTimer);");
        sb.append("    userSpeedTimer=setTimeout(function(){userSpeed=false;},10000);");
        sb.append("    document.querySelectorAll('.sp').forEach(function(x){x.classList.toggle('on',parseFloat(x.textContent)===s);});");
        sb.append("  };");
        sb.append("  spdDiv.appendChild(b);");
        sb.append("});");
        sb.append("function applySpeed(){v.playbackRate=curSpeed;}");
        sb.append("v.addEventListener('play',applySpeed);");
        sb.append("function reload(pos){");
        sb.append("  v.src='/video?t='+Date.now();");
        sb.append("  th.src='/thumb?t='+Date.now();");
        sb.append("  v.load();");
        sb.append("  v.addEventListener('canplay',function oncp(){");
        sb.append("    v.removeEventListener('canplay',oncp);");
        sb.append("    if(pos>0)v.currentTime=pos/1000;");
        sb.append("    v.playbackRate=curSpeed;");
        sb.append("    v.play().catch(function(){});");
        sb.append("  });");
        sb.append("}");
        sb.append("async function cmd(a,p){");
        sb.append("  var b=p!==undefined?JSON.stringify({action:a,position:Math.round(p)}):JSON.stringify({action:a});");
        sb.append("  try{await fetch('/control',{method:'POST',headers:{'Content-Type':'application/json'},body:b});}catch(e){}");
        sb.append("}");
        sb.append("async function stopStream(){await cmd('updatepos',Math.round(v.currentTime*1000));await cmd('stop');}");
        sb.append("function prevVideo(){cmd('prev');}");
        sb.append("function nextVideo(){cmd('next');}");
        sb.append("if('mediaSession' in navigator){");
        sb.append("  try{navigator.mediaSession.setActionHandler('previoustrack',prevVideo);}catch(e){}");
        sb.append("  try{navigator.mediaSession.setActionHandler('nexttrack',nextVideo);}catch(e){}");
        sb.append("}");
        sb.append("document.addEventListener('keydown',function(e){");
        sb.append("  if(e.target.tagName==='INPUT')return;");
        sb.append("  if(e.code==='Space'||e.code==='KeyK'){e.preventDefault();v.paused?v.play():v.pause();}");
        sb.append("  else if(e.code==='ArrowRight'||e.code==='KeyL'){e.preventDefault();v.currentTime+=10;cmd('updatepos',v.currentTime*1000);}");
        sb.append("  else if(e.code==='ArrowLeft'||e.code==='KeyJ'){e.preventDefault();v.currentTime=Math.max(0,v.currentTime-10);cmd('updatepos',v.currentTime*1000);}");
        sb.append("  else if(e.code==='ArrowUp'){e.preventDefault();v.volume=Math.min(1,v.volume+0.1);}");
        sb.append("  else if(e.code==='ArrowDown'){e.preventDefault();v.volume=Math.max(0,v.volume-0.1);}");
        sb.append("  else if(e.code==='KeyM'){v.muted=!v.muted;}");
        sb.append("  else if(e.code==='KeyF'){if(v.requestFullscreen)v.requestFullscreen();}");
        sb.append("  else if(e.code==='KeyN')nextVideo();");
        sb.append("  else if(e.code==='KeyP')prevVideo();");
        sb.append("  else if(e.code==='Comma'){var i=SPEEDS.indexOf(curSpeed);if(i>0){curSpeed=SPEEDS[i-1];v.playbackRate=curSpeed;}}");
        sb.append("  else if(e.code==='Period'){var i=SPEEDS.indexOf(curSpeed);if(i<SPEEDS.length-1){curSpeed=SPEEDS[i+1];v.playbackRate=curSpeed;}}");
        sb.append("});");
        sb.append("v.addEventListener('timeupdate',function(){");
        sb.append("  if(!v._lr||Date.now()-v._lr>5000){v._lr=Date.now();cmd('updatepos',Math.round(v.currentTime*1000));}");
        sb.append("});");
        sb.append("async function poll(){");
        sb.append("  try{");
        sb.append("    var r=await fetch('/status',{cache:'no-store'});");
        sb.append("    var d=await r.json();");
        sb.append("    nt.textContent=d.title;npt.textContent=d.title;");
        sb.append("    if(d.version!==lastVersion){lastVersion=d.version;nt.textContent=d.title;npt.textContent=d.title;reload(d.position);}");
        sb.append("    if(!userSpeed&&d.speed&&Math.abs(d.speed-curSpeed)>0.01){");
        sb.append("      curSpeed=d.speed;v.playbackRate=d.speed;");
        sb.append("      document.querySelectorAll('.sp').forEach(function(x){x.classList.toggle('on',parseFloat(x.textContent)===d.speed);});");
        sb.append("    }");
        sb.append("    if('mediaSession' in navigator)try{navigator.mediaSession.metadata=new MediaMetadata({title:d.title,artwork:[{src:'/thumb'}]});}catch(e){}");
        sb.append("    [pvb,pvc].forEach(function(el){el.classList.toggle('dim',!d.hasPrev);});");
        sb.append("    [nxb,nxc].forEach(function(el){el.classList.toggle('dim',!d.hasNext);});");
        sb.append("  }catch(e){}");
        sb.append("  setTimeout(poll,1000);");
        sb.append("}");
        sb.append("async function connect(){");
        sb.append("  try{");
        sb.append("    var r=await fetch('/status',{cache:'no-store'});");
        sb.append("    if(r.ok){");
        sb.append("      var d=await r.json();");
        sb.append("      ov.classList.add('h');");
        sb.append("      lastTitle=d.title;lastVersion=d.version;");
        sb.append("      nt.textContent=d.title;npt.textContent=d.title;");
        sb.append("      reload(d.position);");
        sb.append("      poll();");
        sb.append("    }else{om.textContent='Error '+r.status;setTimeout(connect,2000);}");
        sb.append("  }catch(e){om.textContent='Cannot reach phone. Same WiFi?';setTimeout(connect,2000);}");
        sb.append("}");
        sb.append("connect();");
        sb.append("</script></body></html>");
        return sb.toString();
    }

    private Response jsonOK(String json) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String extractJson(String body, String key) {
        if (body == null) return "";
        String k = "\"" + key + "\"";
        int i = body.indexOf(k);
        if (i < 0) return "";
        i = body.indexOf(":", i + k.length());
        if (i < 0) return "";
        i++;
        while (i < body.length() && (body.charAt(i) == ' ' || body.charAt(i) == '"')) i++;
        int end = i;
        while (end < body.length() && body.charAt(end) != '"' && body.charAt(end) != ',' && body.charAt(end) != '}') end++;
        return body.substring(i, end).trim();
    }
}
