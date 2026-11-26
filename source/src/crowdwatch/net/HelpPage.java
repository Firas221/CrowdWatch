package crowdwatch.net;

/**
 * The public help page at GET /help, opened by visitors who scan a QR code in an area (the code
 * carries the area, so they do not have to say where they are). In English and Arabic. No sign-in:
 * reports go to POST /api/public/report, which allows only a few reports per phone, and every
 * report is marked "[visitor report]" so the control room knows where it came from.
 */
final class HelpPage {
    private HelpPage() { }

    static String page(String[][] areas, String preset, String venue) {
        StringBuilder opts = new StringBuilder();
        for (String[] a : areas)
            opts.append("<option value='").append(ApiServer.html(a[0])).append("'").append(a[0].equalsIgnoreCase(preset == null ? "" : preset) ? " selected" : "")
                .append('>').append(ApiServer.html(a[1])).append("</option>");
        return """
<!DOCTYPE html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Help - %s</title>
<style>
body{font-family:system-ui,Tahoma,Arial,sans-serif;margin:0;background:#F2F5FB;color:#0F1B33}
header{background:#1B3A5C;color:#fff;padding:14px 16px;display:flex;justify-content:space-between;align-items:center;gap:10px}
main{padding:16px;max-width:560px;margin:auto}
.kinds{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:12px 0}
.kinds button{padding:16px 8px;border-radius:12px;border:2px solid #C8D3E6;background:#fff;font:inherit;font-weight:700;font-size:16px;cursor:pointer}
.kinds button.on{border-color:#2F7CF6;background:#E8F0FE}
label{display:block;margin:12px 0 4px;font-weight:600}
select,textarea,input{width:100%%;box-sizing:border-box;padding:12px;border:1px solid #C8D3E6;border-radius:10px;font:inherit;background:#fff}
textarea{min-height:90px}
.send{margin-top:16px;width:100%%;padding:16px;border:0;border-radius:12px;background:#E11D48;color:#fff;font:inherit;font-weight:800;font-size:18px}
#out{margin-top:14px;padding:12px;border-radius:10px;display:none}
#out.ok{display:block;background:#DCFCE7;color:#166534}#out.bad{display:block;background:#FFE4E6;color:#9F1239}
.lang{background:#2C4F78;color:#fff;border:0;border-radius:8px;padding:6px 12px;font:inherit}
.note{font-size:13px;color:#55657F;margin-top:12px}
</style><script src='/i18n.js'></script></head><body>
<header><b>Help - %s</b><button class='lang' data-lang-btn>العربية</button></header>
<main>
<p><b>What do you need?</b></p>
<div class='kinds' role='group' aria-label='What do you need?'>
<button type='button' data-kind='lost' class='on'>I lost someone</button>
<button type='button' data-kind='medical'>Someone is unwell</button>
<button type='button' data-kind='found'>I found a lost person</button>
<button type='button' data-kind='help'>Other help</button>
</div>
<label for='area'>Where are you?</label>
<select id='area'>%s</select>
<label for='details'>Describe it</label>
<textarea id='details' maxlength='300' placeholder='Who, what they wear, what happened'></textarea>
<label for='contact'>Your phone number (so staff can call you)</label>
<input id='contact' maxlength='60' inputmode='tel' autocomplete='tel'>
<button class='send' id='send'>Send for help</button>
<div id='out' role='status'></div>
<p class='note'>In an emergency call the venue's staff or the emergency number. Your details are only used to help you and are deleted after 30 days.</p>
</main>
<script>
let kind='lost';
const T=s=>window.cwT?cwT(s):s;
document.querySelectorAll('[data-kind]').forEach(b=>b.onclick=()=>{kind=b.dataset.kind;document.querySelectorAll('[data-kind]').forEach(x=>x.classList.toggle('on',x===b));});
document.getElementById('send').onclick=async()=>{
 const o=document.getElementById('out');
 const body=new URLSearchParams({kind,area:document.getElementById('area').value,details:document.getElementById('details').value,
  contact:document.getElementById('contact').value,language:document.documentElement.lang==='ar'?'Arabic':'English'});
 try{const r=await fetch('/api/public/report',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});
  const j=await r.json();o.className=j.ok?'ok':'bad';o.textContent=T(j.message);if(j.ok)document.getElementById('details').value='';}
 catch(e){o.className='bad';o.textContent=T('No connection: please ask a member of staff.');}
};
</script></body></html>
""".formatted(ApiServer.html(venue), ApiServer.html(venue), opts);
    }
}
