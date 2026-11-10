package app.slipnet.vpnjantit

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * VPNJantit pe free SSH account banata hai.
 *
 * - ALAG PROCESS (:vj) mein chalta hai, taaki WebView/ads se crash ho to VPN aur main app na marein.
 * - WebView site ko bilkul normal browser ki tarah chalata hai (ads bhi load hote hain, koi blocking nahi).
 * - Cloudflare ka halka check khud "Success" hone tak ruka rehta hai, uske baad hi Create dabata hai.
 * - Asli captcha (hCaptcha / interactive Cloudflare) aaye to sirf wahi hissa screen pe dikhata hai, user tap karta hai.
 * - Captcha todne ka koi jugaad nahi hai.
 */
class VpnJantitActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var holder: FrameLayout
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var server = "in6"
    private var user = ""
    private var pass = ""

    private var startedAt = 0L
    private var filledAt = 0L
    private var clickedAt = 0L
    private var clicks = 0
    private var revealed = false
    private var done = false
    private var busy = false

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            tick()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = intent.getStringExtra(VpnJantit.EXTRA_SERVER)?.takeIf { it.isNotBlank() } ?: "in6"
        user = randomName()
        pass = randomPass()
        startedAt = SystemClock.elapsedRealtime()

        buildUi()
        setupWebView()

        if (intent.getBooleanExtra(VpnJantit.EXTRA_CLEAR_COOKIES, false)) {
            CookieManager.getInstance().removeAllCookies(null)
        }
        web.loadUrl(String.format(VpnJantit.CREATE_URL, server))
        ui.postDelayed(poll, 1500)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xAA000000.toInt())

        // WebView alag se rakha hai: chhupa hota hai (alpha 0), captcha ke waqt hi dikhta hai.
        holder = FrameLayout(this)
        holder.setBackgroundColor(Color.WHITE)
        holder.alpha = 0f
        web = WebView(this)
        holder.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM)
        hp.setMargins(dp(16), 0, dp(16), dp(90))
        root.addView(holder, hp)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(Color.WHITE)
        card.setPadding(dp(16), dp(16), dp(16), dp(12))
        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(Color.BLACK)
        status.text = "VPNJantit account bana raha hu..."
        card.addView(status)
        val cancel = Button(this)
        cancel.text = "Cancel"
        cancel.setOnClickListener { finishCancelled() }
        card.addView(cancel)
        val cp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        cp.setMargins(dp(16), dp(60), dp(16), 0)
        root.addView(card, cp)

        setContentView(root)
    }

    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        // Chhupa hone par touch na lage (galti se ad na khule)
        web.setOnTouchListener { _, _ -> !revealed }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Ads ke redirect se bacho: main page sirf vpnjantit.com par hi rahe
                if (!request.isForMainFrame) return false
                val host = request.url.host ?: return true
                return !(host == "vpnjantit.com" || host.endsWith(".vpnjantit.com"))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                finishFail("WebView band ho gaya (memory). Dobara try karo.", 0L)
                return true // app crash nahi hoga
            }
        }
    }

    // ───────────────────────── state machine ─────────────────────────

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt > 240_000L) {
            finishFail("Time out: account nahi bana", 0L)
            return
        }
        if (busy) return
        busy = true
        web.evaluateJavascript(JS_PROBE) { raw ->
            busy = false
            onProbe(raw)
        }
    }

    private fun onProbe(raw: String?) {
        if (done || raw.isNullOrEmpty() || raw == "null") return
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        when (o.optString("stage")) {
            "result" -> finishFromResult(o)
            "adblock" -> finishFail("Site ne ad-blocker pakda. DNS/ad-block band karke dobara try karo.", 0L)
            "form" -> handleForm(o)
            else -> {}
        }
    }

    private fun handleForm(o: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val hasInputs = o.optBoolean("hasInputs")
        val ts = o.optString("ts")
        val hc = o.optBoolean("hc")
        val hcDone = o.optBoolean("hcDone")
        val err = o.optString("err")

        if (err.isNotBlank() && (clicks > 0 || !hasInputs)) {
            val cooldown = Regex("minute|wait|too (many|fast|soon)|limit", RegexOption.IGNORE_CASE).containsMatchIn(err)
            finishFail(err, if (cooldown) VpnJantit.COOLDOWN_MS else 0L)
            return
        }
        if (!hasInputs) return

        if (filledAt == 0L) {
            run(fillJs(user, pass)) { }
            filledAt = now
            setStatus("Form bhar diya, Cloudflare check ho raha hai...")
            return
        }

        val canClick = ts == "ok" || hcDone || (ts == "none" && !hc && now - filledAt > 5000L)
        if (canClick) {
            if (clicks == 0 || (hcDone && clicks < 2)) {
                clicks++
                clickedAt = now
                run(CLICK_JS) { }
                hide()
                setStatus("Account ban raha hai...")
            } else if (clicks > 0 && now - clickedAt > 40_000L) {
                finishFail("Account nahi bana (site ne jawab nahi diya)", 0L)
            }
            return
        }

        // Cloudflare "Success" nahi aaya ya asli captcha aa gaya -> user ko sirf wahi hissa dikhao
        if (hc || now - filledAt > 12_000L) reveal()
    }

    private fun finishFromResult(o: JSONObject) {
        val host = o.optString("host").ifBlank { "$server.vpnjantit.com" }
        val ns = o.optString("ns").ifBlank { "ns-$server.vpnjantit.com" }
        val pub = o.optString("pub").trim()
        val u = o.optString("user").trim()
        val p = o.optString("pass")
        if (u.isBlank() || p.isBlank()) return
        if (pub.isBlank()) {
            finishFail("Account ban gaya ($u) par public key nahi mili", 0L)
            return
        }
        val exp = parseExpiry(o.optString("exp"))
        val data = Intent()
            .putExtra(VpnJantit.EXTRA_SERVER, server)
            .putExtra(VpnJantit.R_HOST, host)
            .putExtra(VpnJantit.R_NS, ns)
            .putExtra(VpnJantit.R_PUBKEY, pub)
            .putExtra(VpnJantit.R_USER, u)
            .putExtra(VpnJantit.R_PASS, p)
            .putExtra(VpnJantit.R_EXPIRES, exp)
        done = true
        setResult(RESULT_OK, data)
        finish()
    }

    private fun parseExpiry(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            // Site ka time GMT+7 hai
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT+07:00")
            f.parse(s.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ───────────────────────── UI helpers ─────────────────────────

    private fun setStatus(t: String) { status.text = t }

    private fun reveal() {
        if (revealed) return
        revealed = true
        holder.alpha = 1f
        setStatus("Captcha aaya hai: neeche wale box mein tap karo")
        run(REVEAL_JS) { }
        // Captcha widget baad mein load ho sakta hai, isliye 2 baar scroll
        ui.postDelayed({ if (!done && revealed) run(REVEAL_JS) { } }, 1500)
    }

    private fun hide() {
        if (!revealed) return
        revealed = false
        holder.alpha = 0f
    }

    private fun run(js: String, cb: (String?) -> Unit) {
        try { web.evaluateJavascript(js) { cb(it) } } catch (e: Exception) { }
    }

    private fun finishFail(msg: String, retryMs: Long) {
        if (done) return
        done = true
        val data = Intent().putExtra(VpnJantit.R_ERROR, msg).putExtra(VpnJantit.R_RETRY_MS, retryMs)
        setResult(RESULT_CANCELED, data)
        finish()
    }

    private fun finishCancelled() {
        if (done) return
        done = true
        setResult(RESULT_CANCELED)
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        finishCancelled()
    }

    override fun onDestroy() {
        done = true
        ui.removeCallbacksAndMessages(null)
        try {
            holder.removeAllViews()
            web.stopLoading()
            web.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
    }

    // ───────────────────────── random user/pass ─────────────────────────

    private fun randomName(): String {
        val r = Random()
        val abc = "abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder("jt")
        repeat(4) { sb.append(abc[r.nextInt(abc.length)]) }
        repeat(3) { sb.append(r.nextInt(10)) }
        return sb.toString()
    }

    private fun randomPass(): String {
        val r = Random()
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder()
        repeat(10) { sb.append(chars[r.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun fillJs(u: String, p: String): String =
        "(function(u,p){" +
            "var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){" +
            "var t=(e.type||'text').toLowerCase();" +
            "return (t==='text'||t==='password')&&!e.readOnly&&e.getBoundingClientRect().width>0;});" +
            "if(ins.length<2)return 'no';" +
            "function set(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
            "d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));" +
            "e.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "set(ins[0],u);set(ins[1],p);return 'ok';})(" +
            JSONObject.quote(u) + "," + JSONObject.quote(p) + ")"

    companion object {
        /** "Create Username SSH Tunnel" button (premium wala "360 Days" nahi). */
        private const val CLICK_JS = """
(function(){
  var c=[].slice.call(document.querySelectorAll('button,input[type=submit],input[type=button],a'));
  for(var i=0;i<c.length;i++){
    var t=((c[i].innerText||c[i].value||'')+'').replace(/\s+/g,' ').trim();
    if(/create username ssh tunnel/i.test(t)){ c[i].click(); return 'ok'; }
  }
  return 'no';
})()
"""

        private const val REVEAL_JS = """
(function(){
  var e=document.querySelector('iframe[src*="hcaptcha.com"],.h-captcha,.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  if(e){ e.scrollIntoView({block:'center'}); }
  return 'ok';
})()
"""

        /** Page ko padhkar batata hai: result / adblock / form + captcha ki halat. */
        private const val JS_PROBE = """
(function(){
  function vis(e){ if(!e) return false; var r=e.getBoundingClientRect(); var s=getComputedStyle(e);
    return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; }
  var body=(document.body&&document.body.innerText)||'';
  var toks=[];
  if(document.body){
    var w=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT,null,false);
    var n;
    while((n=w.nextNode())){
      if(n.nodeType===3){
        var pt=n.parentNode&&n.parentNode.tagName;
        if(pt==='SCRIPT'||pt==='STYLE'||pt==='NOSCRIPT') continue;
        var s=(n.nodeValue||'').replace(/\s+/g,' ').trim();
        if(s) toks.push(['t',s]);
      } else if(n.tagName==='INPUT'){
        var ty=(n.type||'text').toLowerCase();
        if(ty==='text'||ty==='password'||ty==='email') toks.push(['i',n.value||'']);
      }
    }
  }
  function inputAfter(label){
    for(var i=0;i<toks.length;i++){
      if(toks[i][0]==='t' && toks[i][1].toLowerCase().indexOf(label)===0){
        for(var j=i+1;j<toks.length && j<i+4;j++){ if(toks[j][0]==='i') return toks[j][1]; }
      }
    }
    return '';
  }
  var o={stage:'other'};
  var user=inputAfter('username ssh'), pass=inputAfter('password ssh');
  if(user && pass){
    o.stage='result'; o.user=user; o.pass=pass;
    o.host=inputAfter('server host ssh'); o.pub=inputAfter('public key');
    var m=body.match(/Name Server\s*\(NS\)\s*:\s*([A-Za-z0-9.\-]+)/i); o.ns=m?m[1]:'';
    var e=body.match(/Expiration Date\s*:\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})/i); o.exp=e?e[1]:'';
    return o;
  }
  if(/hold up/i.test(body) && /ad ?block|adguard/i.test(body)){ o.stage='adblock'; return o; }
  var ins=[].slice.call(document.querySelectorAll('input')).filter(function(e){
    var t=(e.type||'text').toLowerCase();
    return (t==='text'||t==='password') && !e.readOnly && vis(e); });
  o.stage='form'; o.hasInputs=ins.length>=2;
  var ts=document.querySelector('input[name="cf-turnstile-response"]');
  var tsEl=document.querySelector('.cf-turnstile,iframe[src*="challenges.cloudflare.com"]');
  o.ts = ts ? (ts.value ? 'ok' : 'wait') : (tsEl ? 'wait' : 'none');
  var hcr=document.querySelector('textarea[name="h-captcha-response"],input[name="h-captcha-response"]');
  var hcf=[].slice.call(document.querySelectorAll('iframe[src*="hcaptcha.com"]')).filter(vis);
  o.hcDone=!!(hcr && hcr.value);
  o.hc=hcf.length>0 && !o.hcDone;
  var err='';
  var lines=body.split('\n');
  for(var k=0;k<lines.length;k++){
    var L=lines[k].trim();
    if(L.length>3 && L.length<200 && /(server (is )?full|please wait|try again|too many|already (exist|taken|used)|not available|failed|limit reached|wait \d+ minute)/i.test(L)){ err=L; break; }
  }
  o.err=err;
  return o;
})()
"""
    }
}
