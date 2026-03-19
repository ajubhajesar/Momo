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
                return newFixedLengthResponse(Response.Status.OK,
                    "text/html; charset=utf-8", buildHtml());

            case "/status": {
                long buffered = 0;
                if (sc.videoFile != null && sc.duration > 0 && !sc.fileName.isEmpty()) {
                    long fileSize = sc.videoFile.length();
                    float prog = FileLoader.getInstance(sc.currentAccount)
                        .getBufferedProgressFromPosition(
                            sc.position / (float) sc.duration, sc.fileName);
                    buffered = (long)(prog * sc.duration);
                }
                String json = String.format(
                    "{\"title\":\"%s\",\"hasNext\":%b,\"hasPrev\":%b," +
                    "\"duration\":%d,\"buffered\":%d,\"speed\":%.2f}",
                    escapeJson(sc.videoTitle), sc.hasNext, sc.hasPrev,
                    sc.duration, buffered, sc.speed);
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
                        String posStr = extractJson(body, "position");
                        try {
                            long posMs = Long.parseLong(posStr);
                            sc.onBrowserSeek(posMs);
                        } catch (Exception ignored) {}
                    } else if ("stop".equals(action)) {
                        sc.stopStreaming();
                    } else if ("updatepos".equals(action)) {
                        String posStr = extractJson(body, "position");
                        try { sc.position = Long.parseLong(posStr); }
                        catch (Exception ignored) {}
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
            // cap end to actual current file size (partial downloads)
            if (end >= fileSize) end = fileSize - 1;
        }

        long contentLength = end - start + 1;
        try {
            FileInputStream fis = new FileInputStream(file);
            fis.getChannel().position(start);
            java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis, 256 * 1024);
            String mime = file.getName().endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            Response r = newFixedLengthResponse(status, mime, bis, contentLength);
            r.addHeader("Accept-Ranges", "bytes");
            r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            r.addHeader("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response serveThumb(File file) {
        try {
            Bitmap bmp = ThumbnailUtils.createVideoThumbnail(
                file.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            if (bmp == null)
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no thumb");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            byte[] bytes = baos.toByteArray();
            Response tr = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                new ByteArrayInputStream(bytes), bytes.length);
            tr.addHeader("Cache-Control", "max-age=10");
            return tr;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private String buildHtml() {
        return "<!DOCTYPE html><html lang='en'><head>" +
        "<meta charset='utf-8'>" +
        "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<title>📡 Momogram Stream</title>" +
        "<style>" +
        "*{margin:0;padding:0;box-sizing:border-box}" +
        "body{font-family:'Segoe UI',sans-serif;background:#030712;color:#fff;min-height:100vh;display:flex;flex-direction:column}" +
        "nav{background:rgba(3,7,18,0.85);backdrop-filter:blur(12px);border-bottom:1px solid #1f2937;padding:0 20px;height:56px;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:100;flex-shrink:0}" +
        ".nav-logo{font-size:16px;font-weight:700;display:flex;align-items:center;gap:8px}" +
        ".nav-title{font-size:13px;color:#9ca3af;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:55vw}" +
        "#ov{position:fixed;inset:0;background:#030712;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:16px;z-index:9999}" +
        "#ov.h{display:none}" +
        ".spinner{width:36px;height:36px;border:3px solid #1f2937;border-top-color:#3b82f6;border-radius:50%;animation:spin 0.8s linear infinite}" +
        "@keyframes spin{to{transform:rotate(360deg)}}" +
        "#ov p{color:#9ca3af;font-size:15px;text-align:center;max-width:280px;line-height:1.5}" +
        "#vw{background:#000;width:100%;position:relative}" +
        "video{width:100%;display:block;max-height:56vw}" +
        "@media(orientation:landscape){#vw{flex:1}video{max-height:100vh}}" +
        ".controls{background:#0d1117;border-bottom:1px solid #1f2937;padding:10px 16px;display:flex;align-items:center;gap:10px;flex-wrap:wrap}" +
        ".seekwrap{width:100%;display:flex;align-items:center;gap:8px;margin-bottom:4px}" +
        "#seekbar{flex:1;-webkit-appearance:none;appearance:none;height:4px;border-radius:2px;outline:none;cursor:pointer;background:linear-gradient(to right,#3b82f6 0%,#3b82f6 var(--prog,0%),#1e3a5f var(--prog,0%),#1e3a5f var(--buf,0%),#374151 var(--buf,0%),#374151 100%)}" +
        "#seekbar::-webkit-slider-thumb{-webkit-appearance:none;width:14px;height:14px;border-radius:50%;background:#fff;cursor:pointer}" +
        "#seekbar::-moz-range-thumb{width:14px;height:14px;border-radius:50%;background:#fff;border:none;cursor:pointer}" +
        ".time{font-size:11px;color:#6b7280;white-space:nowrap}" +
        ".btn{padding:8px 14px;border:none;border-radius:6px;cursor:pointer;font-weight:600;font-size:13px;display:inline-flex;align-items:center;gap:6px;transition:all 0.15s;-webkit-tap-highlight-color:transparent}" +
        ".btn-primary{background:#3b82f6;color:#fff}.btn-primary:hover{background:#2563eb}" +
        ".btn-ghost{background:#1f2937;color:#e5e7eb;border:1px solid #374151}.btn-ghost:hover{background:#374151}" +
        ".btn-ghost.dim{opacity:0.35;pointer-events:none}" +
        ".btn svg{width:16px;height:16px;fill:currentColor;display:block;pointer-events:none}" +
        ".spacer{flex:1}" +
        ".spd{padding:4px 8px;border:none;border-radius:5px;cursor:pointer;font-size:12px;font-weight:600;background:#1f2937;color:#9ca3af;border:1px solid #374151;-webkit-tap-highlight-color:transparent}" +
        ".spd.active{background:#3b82f6;color:#fff;border-color:#3b82f6}" +
        ".now-playing{padding:16px}" +
        ".section-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.08em;color:#6b7280;margin-bottom:10px}" +
        ".np-card{background:#1f2937;border:1px solid #374151;border-radius:12px;overflow:hidden;display:flex;gap:14px;padding:14px;align-items:center;box-shadow:0 0 20px rgba(59,130,246,0.2)}" +
        ".np-thumb{width:80px;height:52px;background:#111827;border-radius:6px;flex-shrink:0;overflow:hidden;display:flex;align-items:center;justify-content:center}" +
        ".np-thumb img{width:100%;height:100%;object-fit:cover}" +
        ".np-thumb .icon{font-size:24px}" +
        ".np-info{flex:1;min-width:0}" +
        ".np-title{font-size:14px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
        ".np-sub{font-size:12px;color:#6b7280;margin-top:3px}" +
        ".live-dot{display:inline-block;width:7px;height:7px;background:#10b981;border-radius:50%;margin-right:5px;animation:pulse 1.5s ease-in-out infinite}" +
        "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}" +
        ".queue{padding:0 16px 24px}" +
        ".nav-cards{display:flex;gap:10px}" +
        ".nav-card{flex:1;background:#1f2937;border:1px solid #374151;border-radius:10px;overflow:hidden;cursor:pointer;transition:border-color 0.15s,transform 0.1s;display:flex;flex-direction:column}" +
        ".nav-card:hover{border-color:#3b82f6;transform:translateY(-2px)}" +
        ".nav-card.dim{opacity:0.3;cursor:default;pointer-events:none}" +
        ".nc-thumb{width:100%;aspect-ratio:16/9;background:#111827;display:flex;align-items:center;justify-content:center;font-size:28px}" +
        ".nc-label{font-size:10px;font-weight:700;text-transform:uppercase;color:#6b7280;letter-spacing:.06em;padding:8px 10px 2px}" +
        ".nc-title{font-size:12px;color:#d1d5db;padding:0 10px 10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
        "</style></head><body>" +
        "<div id='ov'><div class='spinner'></div><p id='om'>Connecting to phone…</p></div>" +
        "<nav><div class='nav-logo'>📡 <span>Momogram</span></div><span class='nav-title' id='ntitle'></span></nav>" +
        "<div id='vw'><video id='v' playsinline controls preload='auto'></video></div>" +
        "<div class='controls'>" +
        "  <div class='seekwrap'>" +
        "    <span class='time' id='cur'>0:00</span>" +
        "    <input id='seekbar' type='range' min='0' max='1000' value='0'>" +
        "    <span class='time' id='dur'>0:00</span>" +
        "  </div>" +
        "  <button class='btn btn-ghost dim' id='pvb' onclick='prevVideo()'>" +
        "    <svg viewBox='0 0 24 24'><path d='M6 6h2v12H6zm3.5 6 8.5 6V6z'/></svg>Prev" +
        "  </button>" +
        "  <button class='btn btn-ghost dim' id='nxb' onclick='nextVideo()'>" +
        "    Next<svg viewBox='0 0 24 24'><path d='M6 18l8.5-6L6 6v12zm8.5-6L21 18V6z'/></svg>" +
        "  </button>" +
        "  <div class='spacer'></div>" +
        "  <div id='speeds' style='display:flex;gap:4px'></div>" +
        "  <button class='btn btn-ghost' onclick='toggleFS()'>" +
        "    <svg viewBox='0 0 24 24'><path d='M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z'/></svg>" +
        "  </button>" +
        "</div>" +
        "<div class='now-playing'>" +
        "  <div class='section-label'>Now Playing</div>" +
        "  <div class='np-card'>" +
        "    <div class='np-thumb'><img id='nthumb' src='/thumb' onerror=\"this.style.display='none';this.nextSibling.style.display='block'\"><span class='icon' style='display:none'>🎬</span></div>" +
        "    <div class='np-info'><div class='np-title' id='nptitle'>Loading…</div><div class='np-sub'><span class='live-dot'></span>Live Stream</div></div>" +
        "  </div>" +
        "</div>" +
        "<div class='queue'>" +
        "  <div class='section-label'>Up Next</div>" +
        "  <div class='nav-cards'>" +
        "    <div class='nav-card dim' id='pvc' onclick='prevVideo()'><div class='nc-thumb'>⏮</div><div class='nc-label'>← Previous</div><div class='nc-title'>—</div></div>" +
        "    <div class='nav-card dim' id='nxc' onclick='nextVideo()'><div class='nc-thumb'>⏭</div><div class='nc-label'>Next →</div><div class='nc-title'>—</div></div>" +
        "  </div>" +
        "</div>" +
        "<script>" +
        "const v=document.getElementById('v');" +
        "const ov=document.getElementById('ov'),om=document.getElementById('om');" +
        "const ntitle=document.getElementById('ntitle');" +
        "const nptitle=document.getElementById('nptitle');" +
        "const nthumb=document.getElementById('nthumb');" +
        "const pvb=document.getElementById('pvb'),nxb=document.getElementById('nxb');" +
        "const pvc=document.getElementById('pvc'),nxc=document.getElementById('nxc');" +
        "const seekbar=document.getElementById('seekbar');" +
        "const curEl=document.getElementById('cur'),durEl=document.getElementById('dur');" +
        "let lastTitle='',totalDur=0,dragging=false,curSpeed=1;" +

        // Speed buttons
        "const SPEEDS=[0.25,0.5,0.75,1,1.25,1.5,1.75,2,2.5,3];" +
        "const spdDiv=document.getElementById('speeds');" +
        "SPEEDS.forEach(s=>{" +
        "  const b=document.createElement('button');" +
        "  b.className='spd'+(s===1?' active':'');" +
        "  b.textContent=s+'x';" +
        "  b.onclick=()=>{v.playbackRate=s;curSpeed=s;" +
        "    document.querySelectorAll('.spd').forEach(x=>x.classList.toggle('active',parseFloat(x.textContent)===s));" +
        "  };" +
        "  spdDiv.appendChild(b);" +
        "});" +

        "function fmt(ms){if(!ms||ms<0)return'0:00';const s=Math.floor(ms/1000),m=Math.floor(s/60),sec=s%60;return m+':'+(sec<10?'0':'')+sec;}" +

        // Seekbar
        "seekbar.addEventListener('mousedown',()=>dragging=true);" +
        "seekbar.addEventListener('touchstart',()=>dragging=true,{passive:true});" +
        "seekbar.addEventListener('input',()=>{if(!totalDur)return;const ms=parseInt(seekbar.value)/1000*totalDur;curEl.textContent=fmt(ms);});" +
        "seekbar.addEventListener('change',()=>{" +
        "  dragging=false;" +
        "  if(!totalDur)return;" +
        "  const ms=parseInt(seekbar.value)/1000*totalDur;" +
        "  v.currentTime=ms/1000;" +
        "  cmd('seek',ms);" +
        "});" +

        // Update seekbar from video progress
        "v.addEventListener('timeupdate',()=>{" +
        "  if(dragging||!totalDur)return;" +
        "  const ms=v.currentTime*1000;" +
        "  seekbar.value=Math.round(ms/totalDur*1000);" +
        "  curEl.textContent=fmt(ms);" +
        "  // report position to phone every 5s\n" +
        "  if(!v._lastReport||Date.now()-v._lastReport>5000){v._lastReport=Date.now();cmd('updatepos',Math.round(ms));}" +
        "});" +

        "function toggleFS(){" +
        "  const el=document.documentElement;" +
        "  if(!document.fullscreenElement&&!document.webkitFullscreenElement){" +
        "    (el.requestFullscreen||el.webkitRequestFullscreen).call(el);" +
        "    if(screen.orientation&&screen.orientation.lock)screen.orientation.lock('landscape').catch(()=>{});" +
        "  }else{" +
        "    (document.exitFullscreen||document.webkitExitFullscreen).call(document);" +
        "  }" +
        "}" +

        "if('mediaSession' in navigator){" +
        "  navigator.mediaSession.setActionHandler('previoustrack',prevVideo);" +
        "  navigator.mediaSession.setActionHandler('nexttrack',nextVideo);" +
        "}" +

        "document.addEventListener('keydown',e=>{" +
        "  if(e.code==='KeyF')toggleFS();" +
        "  else if(e.code==='KeyN')nextVideo();" +
        "  else if(e.code==='KeyP')prevVideo();" +
        "  else if(e.code==='Space'){e.preventDefault();v.paused?v.play():v.pause();}" +
        "  else if(e.code==='ArrowLeft'){e.preventDefault();v.currentTime=Math.max(0,v.currentTime-10);cmd('seek',v.currentTime*1000);}" +
        "  else if(e.code==='ArrowRight'){e.preventDefault();v.currentTime+=10;cmd('seek',v.currentTime*1000);}" +
        "});" +

        "async function cmd(a,pos){" +
        "  const body=pos!==undefined?JSON.stringify({action:a,position:Math.round(pos)}):JSON.stringify({action:a});" +
        "  try{await fetch('/control',{method:'POST',headers:{'Content-Type':'application/json'},body});}catch(e){}" +
        "}" +

        "function prevVideo(){cmd('prev').then(()=>setTimeout(reload,500));}" +
        "function nextVideo(){cmd('next').then(()=>setTimeout(reload,500));}" +

        "function reload(){" +
        "  v.src='/video?t='+Date.now();" +
        "  nthumb.src='/thumb?t='+Date.now();" +
        "}" +

        "async function connect(){" +
        "  try{" +
        "    const r=await fetch('/status',{cache:'no-store'});" +
        "    if(r.ok){" +
        "      const d=await r.json();" +
        "      ov.classList.add('h');" +
        "      updateUI(d);" +
        "      if(lastTitle!==d.title){lastTitle=d.title;reload();}" +
        "      poll();" +
        "    }else{om.textContent='Error '+r.status;setTimeout(connect,2000);}" +
        "  }catch(e){om.textContent='Cannot reach phone. Same WiFi?';setTimeout(connect,2000);}" +
        "}" +

        "async function poll(){" +
        "  try{" +
        "    const r=await fetch('/status',{cache:'no-store'});" +
        "    const d=await r.json();" +
        "    if(d.title!==lastTitle){lastTitle=d.title;reload();}" +
        "    updateUI(d);" +
        "  }catch(e){}" +
        "  setTimeout(poll,3000);" +
        "}" +

        "function updateUI(d){" +
        "  ntitle.textContent=d.title;" +
        "  nptitle.textContent=d.title;" +
        "  totalDur=d.duration||0;" +
        "  durEl.textContent=fmt(totalDur);" +
        // Update buffered progress on seekbar
        "  if(totalDur>0){" +
        "    const bufPct=(d.buffered/totalDur*100).toFixed(1)+'%';" +
        "    const progPct=(v.currentTime*1000/totalDur*100).toFixed(1)+'%';" +
        "    seekbar.style.setProperty('--buf',bufPct);" +
        "    seekbar.style.setProperty('--prog',progPct);" +
        "  }" +
        // Sync speed from phone
        "  if(Math.abs(d.speed-curSpeed)>0.01){" +
        "    curSpeed=d.speed;v.playbackRate=d.speed;" +
        "    document.querySelectorAll('.spd').forEach(x=>x.classList.toggle('active',parseFloat(x.textContent)===d.speed));" +
        "  }" +
        "  if('mediaSession' in navigator)navigator.mediaSession.metadata=new MediaMetadata({title:d.title,artwork:[{src:'/thumb'}]});" +
        "  [pvb,pvc].forEach(el=>el.classList.toggle('dim',!d.hasPrev));" +
        "  [nxb,nxc].forEach(el=>el.classList.toggle('dim',!d.hasNext));" +
        "}" +

        "connect();" +
        "</script></body></html>";
    }

    private Response jsonOK(String json) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
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
