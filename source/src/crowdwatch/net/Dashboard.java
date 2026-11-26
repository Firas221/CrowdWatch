package crowdwatch.net;

import crowdwatch.core.CrowdMonitor;
import crowdwatch.model.Area;
import crowdwatch.model.Category;
import crowdwatch.ds.LinkedList;

/**
 * Builds the single-page web dashboard served at {@code GET /}.
 * <p>
 * The page is plain HTML with a small script that calls /api/summary, /api/areas,
 * /api/incidents and /api/alerts every 3 seconds, plus forms to report an incident and to
 * send a people count. All text from the monitor is HTML-escaped before it goes into the page.
 */
final class Dashboard {
    // utility class, no instances
    private Dashboard() {}

    /**
     * @param m        the monitor (the caller holds its lock)
     * @param needsKey true to add an API key field to the forms
     * @return the complete HTML page
     */
    static String page(CrowdMonitor m, boolean needsKey) { return page(m, needsKey, false); }

    /**
     * @param signedIn true if the viewer signed in with a user name (a Sign out button is added)
     */
    static String page(CrowdMonitor m, boolean needsKey, boolean signedIn) {
        return page0(m, needsKey).replace("<body>", "<body>" + (signedIn
            ? "<form method='post' action='/classic/logout' style='position:fixed;top:8px;right:12px;margin:0'><button>Sign out</button></form>" : ""));
    }

    /** @return the sign-in page of the plain dashboard (works without JavaScript) */
    static String loginPage(String msg) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>CrowdWatch - sign in</title>"
            + "<style>body{font-family:system-ui,Arial,sans-serif;background:#eef2f7;display:grid;place-items:center;min-height:100vh;margin:0}"
            + "form{background:#fff;padding:24px;border-radius:10px;display:grid;gap:10px;width:300px;box-shadow:0 4px 20px #0002}"
            + "input,button{font:inherit;padding:9px;border:1px solid #c5cfdc;border-radius:6px}button{background:#1B3A5C;color:#fff;border:0}"
            + "p{color:#b00020;margin:0}</style></head><body><form method='post' action='/classic/login'><b>CrowdWatch - plain dashboard</b>"
            + (msg == null ? "" : "<p>" + esc(msg) + "</p>")
            + "<label>User name<br><input name='user' autocomplete='username' required></label>"
            + "<label>Password<br><input name='password' type='password' autocomplete='current-password' required></label>"
            + "<label>Code from the authenticator app (if you use two-step sign-in)<br><input name='code' inputmode='numeric' autocomplete='one-time-code'></label>"
            + "<button>Sign in</button></form></body></html>";
    }

    // the page itself
    private static String page0(CrowdMonitor m, boolean needsKey) {
        StringBuilder areas = new StringBuilder(), cats = new StringBuilder();
        for (Area a : m.areaTree().inOrder()) areas.append("<option value='").append(esc(a.id)).append("'>").append(esc(a.id + " - " + a.name)).append("</option>");
        for (Category c : m.categoryTree().inOrder()) cats.append("<option value='").append(esc(c.code)).append("'>").append(esc(c.label + " (" + c.priority + ")")).append("</option>");
        String key = needsKey ? "<label>API key<input name='key' type='password'></label>" : "";
        StringBuilder zones = new StringBuilder();
        LinkedList<String> sectors = new LinkedList<>();
        for (Area a : m.areaTree().inOrder()) if (!sectors.contains(a.sector)) sectors.addLast(a.sector);
        for (String s : sectors) zones.append("<option value='").append(esc(s)).append("'>Sector: ").append(esc(s)).append("</option>");
        zones.append(areas);
        String ctlKey = needsKey ? "<label>API key (needed to see data; kept in this browser)<input id='ckey' type='password'></label>" : "";
        return """
<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>CrowdWatch - %s</title>
<style>
body{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:0;background:#f4f6f9;color:#1d2733}
header{background:#1B3A5C;color:#fff;padding:12px 18px;font-size:18px;font-weight:600}
main{display:grid;grid-template-columns:2fr 1fr;gap:14px;padding:14px}
section{background:#fff;border:1px solid #d6dde6;border-radius:8px;padding:12px}
h2{font-size:14px;margin:0 0 8px;text-transform:uppercase;letter-spacing:.04em;color:#44546a}
table{border-collapse:collapse;width:100%%;font-size:13px}td,th{padding:4px 6px;border-bottom:1px solid #eef1f5;text-align:left}
.LOW{background:#e3f4e6}.MODERATE{background:#fff4cc}.HIGH{background:#ffddbb}.CRITICAL{background:#f8c6c6}
pre{margin:0;font-size:13px;white-space:pre-wrap}form{display:grid;gap:6px;margin-bottom:12px}label{display:grid;font-size:12px;color:#44546a}
input,select,button{font:inherit;padding:5px}button{background:#1B3A5C;color:#fff;border:0;border-radius:5px;padding:7px;cursor:pointer}
#msg{padding:8px 18px;background:#fffbe6;border-bottom:1px solid #eee;font-size:14px}#msg:empty{display:none}
#banner div{padding:10px 18px;font-weight:700}.b-evac{background:#ff6f00;color:#000}.b-sup{background:#b00020;color:#fff}.b-rev{background:#fff4cc;color:#000}
.sos{background:#d50000}.row{display:flex;gap:6px}.row button{flex:1}#plan{font-size:12px}
@media(max-width:900px){main{grid-template-columns:1fr}}
</style></head><body>
<header>CrowdWatch - %s</header><div id='banner'></div><div id='msg'></div>
<main><div>
<section><h2>Summary</h2><pre id='summary'></pre></section><br>
<section><h2>Areas</h2><table><thead><tr><th>Area</th><th>Name</th><th>People</th><th>%%</th><th>Level</th><th>Trend /min</th><th>Critical in</th></tr></thead><tbody id='areas'></tbody></table></section><br>
<section><h2>Open incidents</h2><table><thead><tr><th>ID</th><th>Priority</th><th>Category</th><th>Area</th><th>Status</th><th>Unit</th><th>Min</th></tr></thead><tbody id='incidents'></tbody></table></section>
</div><div>
<section><h2>Report incident</h2><form method='post' action='/api/incidents'><input type='hidden' name='back' value='1'>
<label>Category<select name='category'>%s</select></label><label>Area<select name='area'>%s</select></label>
<label>Details<input name='details'></label><label>Contact<input name='contact'></label><label>Language<input name='language'></label>%s<button>Report</button></form>
<h2>People count</h2><form method='post' action='/api/occupancy'><input type='hidden' name='back' value='1'><input type='hidden' name='source' value='STAFF'>
<label>Area<select name='area'>%s</select></label><label>Count<input name='count' type='number' min='0' required></label>%s<button>Update</button></form></section><br>
<section><h2>Control room</h2>
<label>Officer name / badge<input id='officer'></label>%s
<div class='row'><button onclick='ctl("/api/checkin",{})'>Check in</button><button id='auto' onclick='ctl("/api/automation",{on:autoOn?0:1})'>Automation</button></div>
<div id='review'></div>
<h2>SOS</h2><label>Area<select id='sosArea'>%s</select></label><label>What is happening<input id='sosText'></label>
<button class='sos' onclick='ctl("/api/sos",{area:v("sosArea"),text:v("sosText")})'>SEND SOS</button>
<h2>Evacuation</h2><label>Zone<select id='zone'>%s</select></label>
<div class='row'><button class='sos' onclick='if(confirm("Evacuate "+v("zone")+"?"))ctl("/api/evacuate",{zone:v("zone")})'>Evacuate</button><button onclick='ctl("/api/evacuate/end",{})'>End evacuation</button></div>
<pre id='plan'></pre>
<h2>Message to all units</h2><label>Text<input id='bc'></label><button onclick='ctl("/api/broadcast",{text:v("bc")})'>Send to all units</button>
</section><br>
<section><h2>Alerts</h2><pre id='alerts'></pre></section>
</div></main>
<script>
const msg=new URLSearchParams(location.search).get('msg');if(msg)document.getElementById('msg').textContent=msg;
const e=s=>String(s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
const v=id=>document.getElementById(id).value;let autoOn=true;
const off=document.getElementById('officer');off.value=localStorage.getItem('cwOfficer')||'';off.onchange=()=>localStorage.setItem('cwOfficer',off.value);
const ck=document.getElementById('ckey');if(ck){ck.value=localStorage.getItem('cwKey')||'';ck.onchange=()=>{localStorage.setItem('cwKey',ck.value);load();};}
const K=()=>ck?ck.value:'';const H=()=>({headers:{'X-Api-Key':K()}});
async function get(p){const r=await fetch(p,H());if(r.status==401)throw 'key';return r;}
async function ctl(path,data){data.officer=off.value;if(ck)data.key=K();
 const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});
 let j;try{j=await r.json();}catch(x){j={message:'HTTP '+r.status};}document.getElementById('msg').textContent=j.message;load();}
function review(id,ok){ctl('/api/review',{id:id,decision:ok?'confirm':'false'});}
async function load(){try{
document.getElementById('summary').textContent=await (await get('/api/summary')).text();
const a=await (await get('/api/areas')).json();
document.getElementById('areas').innerHTML=a.map(x=>`<tr class='${x.level}'><td>${e(x.id)}</td><td>${e(x.name)}</td><td>${x.occupancy}/${x.capacity}</td><td>${x.percent}</td><td>${x.level}</td><td>${x.ratePerMinute>0?'+':''}${x.ratePerMinute}</td><td>${x.minutesToCritical>0?x.minutesToCritical+' min':(x.minutesToCritical==0?'now':'-')}</td></tr>`).join('');
const i=await (await get('/api/incidents')).json();
document.getElementById('incidents').innerHTML=i.map(x=>`<tr><td>I${x.id}</td><td>${x.priority}</td><td>${e(x.category)}</td><td>${e(x.area)}</td><td>${x.status}</td><td>${x.unit??'-'}</td><td>${x.minutesOpen}</td></tr>`).join('');
const s=await (await get('/api/status')).json();autoOn=s.automation;
document.getElementById('auto').textContent='Automation: '+(s.automation?'ON (switch off)':'OFF (switch on)');
let b='';if(s.evacuation)b+=`<div class='b-evac'>EVACUATION IN PROGRESS: ${e(s.evacuation)}</div>`;
if(s.overdue)b+=`<div class='b-sup'>SECURITY OFFICER CHECK-IN NEEDED (${e(s.officer)})</div>`;
if(s.review.length)b+=`<div class='b-rev'>${s.review.length} automatic dispatch(es) waiting for officer review</div>`;
document.getElementById('banner').innerHTML=b;
document.getElementById('review').innerHTML=s.review.map(x=>`<div>I${x.id} at ${e(x.area)} -> ${x.unit??'queued'}<br><small>${e(x.details)}</small><div class='row'><button onclick='review(${x.id},true)'>Confirm</button><button onclick='review(${x.id},false)'>False alarm</button></div></div>`).join('');
document.getElementById('plan').textContent=s.plan.length?'Evacuation plan:\\n'+s.plan.join('\\n'):'';
const l=await (await get('/api/alerts')).json();
document.getElementById('alerts').textContent=l.map(x=>x.time.substring(11,16)+'  '+x.kind+'  '+x.text).join('\\n');
}catch(err){document.getElementById('msg').textContent=err==='key'?'Enter the API key in the Control room section to see the data.':'Connection lost - retrying';}}
load();setInterval(load,3000);
</script></body></html>
""".formatted(esc(m.venueName()), esc(m.venueName()), cats, areas, key, areas, key, ctlKey, areas, zones);
    }

    /** @return the text with &amp;, &lt;, &gt; and ' escaped for HTML */
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;"); }
}
