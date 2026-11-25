package crowdwatch.net;

/**
 * The page for the devices issued to scouts and response units, served at {@code GET /scout}.
 * <p>
 * Works in any phone browser on the venue network. The unit enters its ID once (kept on the
 * device). The page asks for the unit's task every 3 seconds and shows it in large type with
 * the walking route. On a new task, SOS or evacuation order the phone vibrates and beeps.
 * Buttons: Acknowledge, On scene, Resolved, and a red SOS button that sends the unit's own
 * location as an SOS.
 * <p>
 * The key field takes either the shared scout key or the phone's own device code (issued by an
 * officer for one unit; with a device code the phone can only act on that unit's tasks). The
 * page can be switched to Arabic (right to left) with the button in its header (see i18n.js).
 */
final class ScoutPage {
    // utility class, no instances
    private ScoutPage() {}

    /**
     * @param needsKey true if changes need the API key (the device then asks for it once)
     * @return the complete HTML page
     */
    static String page(boolean needsKey) {
        return """
<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>CrowdWatch Scout</title>
<style>
body{font-family:system-ui,Arial,sans-serif;margin:0;background:#10202f;color:#fff}
header{padding:10px 14px;background:#1B3A5C;font-weight:600;display:flex;justify-content:space-between;gap:8px}
#setup,#main{padding:14px}#main{display:none}
input,button{font:inherit;padding:12px;border-radius:8px;border:0;width:100%%;box-sizing:border-box;margin:4px 0}
#task{border-radius:12px;padding:16px;margin:8px 0;background:#26394d;font-size:18px;line-height:1.4}
#task.CRITICAL{background:#8b1a1a}#task.HIGH{background:#9a5a00}#task.none{background:#26394d;color:#9fb3c8}
.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px}
.ack{background:#2e7d32;color:#fff}.scene{background:#1565c0;color:#fff}.done{background:#455a64;color:#fff}
#sos{background:#d50000;color:#fff;font-size:22px;font-weight:700;padding:18px;margin-top:14px}
#out{min-height:22px;color:#ffe082;margin:6px 0}#msgs{font-size:14px;color:#c8d6e5}#msgs div{border-bottom:1px solid #26394d;padding:6px 0}
#evac{display:none;background:#ff6f00;color:#000;font-weight:700;padding:12px;border-radius:8px;margin:8px 0}
#look div{background:#4a2a6b;border-radius:10px;padding:12px;margin:8px 0;font-size:16px}.found{background:#7c3aed;color:#fff;font-weight:700}
#foundTask{display:none}
#map{display:none;margin:8px 0}#map svg{width:100%%;height:auto;border-radius:10px;background:#0b1322}
html[dir=rtl] body{font-family:Tahoma,'Noto Sans Arabic',system-ui,sans-serif}
</style><script src='/i18n.js'></script></head><body>
<header><span id='who'>CrowdWatch Scout</span><span id='state'></span><button data-lang-btn style='width:auto;padding:4px 10px;margin:0;background:#26394d;color:#fff'>العربية</button></header>
<div id='setup'><p>Enter your unit ID (and the scout key you were given) to start.</p><input id='uid' placeholder='Unit ID, e.g. A03' autocapitalize='characters'>%s
<button class='ack' onclick='start()'>Start</button></div>
<div id='main'>
<div id='evac'></div>
<div id='task' class='none'>No task. Stand by.</div>
<div id='map'></div>
<button id='foundTask' class='found' onclick='found(task.id)'>Person FOUND (I am with them)</button>
<div id='look'></div>
<div class='row'><button class='ack' onclick='act("/api/ack")'>Acknowledge</button><button class='scene' onclick='act("/api/onscene")'>On scene</button><button class='done' onclick='resolve()'>Resolved</button></div>
<div id='out'></div>
<button id='sos' onclick='sos()'>SOS</button>
<button class='found' onclick='foundPerson()'>I found someone who is lost</button>
<h3>Messages</h3><div id='msgs'></div>
<button class='done' onclick='localStorage.removeItem("cwUnit");location.reload()'>Change unit</button>
</div>
<script>
let unit=localStorage.getItem('cwUnit'),key=localStorage.getItem('cwKey')||'',task=null,seen=localStorage.getItem('cwSeen')||'';
const $=id=>document.getElementById(id);
const T=s=>window.cwT?cwT(s):s;
function start(){unit=$('uid').value.trim().toUpperCase();if(!unit)return;localStorage.setItem('cwUnit',unit);
 if($('key')){key=$('key').value;localStorage.setItem('cwKey',key);}show();}
function show(){$('setup').style.display='none';$('main').style.display='block';$('who').textContent='Unit '+unit;loadMap().then(poll);setInterval(poll,3000);}
function alarm(){try{navigator.vibrate&&navigator.vibrate([400,150,400,150,400]);const c=new AudioContext(),o=c.createOscillator();o.frequency.value=880;o.connect(c.destination);o.start();setTimeout(()=>{o.stop();c.close()},700);}catch(e){}}
async function poll(){try{
 const r=await fetch('/api/unit?id='+encodeURIComponent(unit),{headers:{'X-Api-Key':key}});
 if(r.status==401||r.status==403){$('out').textContent=T('Key not accepted: tap Change unit and enter the scout key.');return;}
 const u=await r.json();
 if(!u.ok){$('out').textContent=u.message;return;}
 $('who').textContent=u.id+' - '+u.name;$('state').textContent=T(u.status)+' @ '+u.area;
 task=u.task;const t=$('task');
 if(task){t.className=task.priority;t.innerHTML='<b>'+(task.category=='SOS'?T('SOS')+' ':'')+'I'+task.id+' '+T(task.priority)+'</b><br>'+esc(task.label)+'<br>'+T('Where')+': <b>'+esc(task.area)+'</b> '+esc(task.areaName)
  +'<br>'+T('Route:')+' '+esc(T(task.route))+'<br>'+(task.details?T('Details:')+' '+esc(task.details)+'<br>':'')+T('Status:')+' '+T(task.status)+(task.acknowledged?' '+T('(acknowledged)'):' - <b>'+T('PLEASE ACKNOWLEDGE')+'</b>');}
 else{t.className='none';t.textContent='No task. Stand by.';}
 $('foundTask').style.display=u.lostTask?'block':'none';
 drawMap(u);
 $('look').innerHTML=(u.lookouts||[]).filter(l=>!task||l.id!=task.id).map(l=>'<div><b>LOOKOUT I'+l.id+'</b> (missing '+l.minutes+' min, last seen '+esc(l.area)+')<br>'+esc(l.details)
  +'<button class="found" onclick="found('+l.id+')">Found - I am with them</button></div>').join('');
 const ev=$('evac');if(u.evacuation){ev.style.display='block';ev.textContent='EVACUATION IN PROGRESS: '+u.evacuation;}else ev.style.display='none';
 $('msgs').innerHTML=u.messages.map(m=>'<div><b>'+m.time.substring(11,16)+' '+m.kind+'</b> '+esc(m.text)+'</div>').join('');
 const newest=u.messages.length?u.messages[0].time+u.messages[0].kind+u.messages[0].text:'';
 if(newest&&newest!==seen){seen=newest;localStorage.setItem('cwSeen',seen);if(/TASK|SOS|EVACUATE|REMINDER|LOOKOUT|BACKUP|MOVE|HOLD|CHECK/.test(u.messages[0].kind))alarm();}
}catch(e){$('out').textContent='No connection - retrying';}}
const esc=s=>String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function post(path,data){const b=new URLSearchParams(data);if(key)b.set('key',key);
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b});
 let j;try{j=await r.json();}catch(e){j={message:'HTTP '+r.status};}$('out').textContent=j.message;poll();}
function act(path){if(path=='/api/ack')return post(path,{unit});if(!task){$('out').textContent='No task.';return;}post(path,{id:task.id});}
function resolve(){if(!task){$('out').textContent='No task.';return;}const n=prompt(T('Closing note (optional)'))??'';post('/api/resolve',{id:task.id,note:n});}
let layout=null,mapUrl=null;
async function loadMap(){try{const r=await fetch('/api/layout',{headers:{'X-Api-Key':key}});layout=await r.json();
 if(layout.map&&layout.image){const b=await fetch('/api/map',{headers:{'X-Api-Key':key}});if(b.ok)mapUrl=URL.createObjectURL(await b.blob());}}catch(e){layout=null;}}
function drawMap(u){const el=$('map');if(!layout||!layout.map||!u.task){el.style.display='none';return;}
 const c={};layout.areas.forEach(p=>c[p.id]=[p.x+p.w/2,p.y+p.h/2]);
 const m=/^\\[(.*)\\]/.exec(u.task.route||'');const ids=m?m[1].split(' -> '):[u.area,u.task.area];
 const pts=ids.map(i=>c[i]).filter(Boolean);
 // zoom to the unit, its task and the route between them
 const keep=new Set(ids.concat([u.area,u.task.area]));let x0=1e9,y0=1e9,x1=-1e9,y1=-1e9;
 layout.areas.forEach(p=>{if(keep.has(p.id)){x0=Math.min(x0,p.x);y0=Math.min(y0,p.y);x1=Math.max(x1,p.x+p.w);y1=Math.max(y1,p.y+p.h);}});
 if(x0>x1){x0=0;y0=0;x1=layout.w;y1=layout.h;}
 const pad=Math.max(120,(x1-x0)*0.25);x0=Math.max(0,x0-pad);y0=Math.max(0,y0-pad);x1=Math.min(layout.w,x1+pad);y1=Math.min(layout.h,y1+pad);
 let s='<svg viewBox="'+x0+' '+y0+' '+(x1-x0)+' '+(y1-y0)+'">'+(mapUrl?'<image href="'+mapUrl+'" width="'+layout.w+'" height="'+layout.h+'"/>':'');
 layout.areas.forEach(p=>{const t=p.id===u.task.area,me=p.id===u.area;
  s+='<rect x="'+p.x+'" y="'+p.y+'" width="'+p.w+'" height="'+p.h+'" rx="8" fill="'+(t?'#ff4d5e':me?'#2f7cf6':'#ffffff')+'" fill-opacity="'+(t||me?0.45:0.06)+'" stroke="'+(t?'#ff4d5e':me?'#4c9bff':'#3a4a66')+'" stroke-width="'+(t||me?5:1)+'"/>';
  if(t||me)s+='<text x="'+(p.x+8)+'" y="'+(p.y+28)+'" font-size="26" font-weight="700" fill="#fff">'+(t?'TASK ':'YOU ')+p.id+'</text>';});
 if(pts.length>1)s+='<polyline points="'+pts.map(q=>q[0]+','+q[1]).join(' ')+'" fill="none" stroke="#ffd166" stroke-width="10" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="22 14"/>';
 el.innerHTML=s+'</svg>';el.style.display='block';}
function found(id){if(!confirm('I'+id+' - '+T('Person FOUND (I am with them)')+'? '+T('Stay with the person.')))return;post('/api/found',{id,unit});}
function foundPerson(){const d=prompt(T('Describe the person you found (age, clothes, language)'));if(!d)return;const c=prompt(T('Their contact or companion (optional)'))??'';post('/api/foundperson',{unit,area:'',details:d,contact:c});}
function sos(){if(!confirm(T('Send SOS from your position?')))return;const t=prompt(T('What is happening? (optional)'))??'';post('/api/sos',{unit,text:t});}
if(unit)show();
</script></body></html>
""".formatted(needsKey ? "<input id='key' type='password' placeholder='Device code or scout key' autocapitalize='characters'>" : "");
    }
}
