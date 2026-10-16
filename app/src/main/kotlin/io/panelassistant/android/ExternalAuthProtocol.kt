package io.panelassistant.android

import android.webkit.WebView
import io.panelassistant.android.dashboard.InjectionScript
import org.json.JSONObject

/**
 * Pure reply-builders for the HA frontend's external-auth / external-bus JS contract. Null = no
 * reply (never evaluate anything for a payload we don't recognise — callback names in particular
 * are validated against the frontend's fixed constants, so an attacker-supplied function name is
 * dropped rather than executed).
 */
object ExternalAuthProtocol {

    private const val MAX_AUTH_PAYLOAD_CHARS = 4 * 1024

    /** Build the dashboard URL: `<haUrl>/<path>?external_auth=1`. [path] is an optional local dashboard
     *  path (e.g. `my-panel/dash` or `/lovelace/0`); leading/trailing route slashes are normalised,
     *  query/fragment state is preserved, and blank means the HA root. `external_auth=1` tells the
     *  frontend to authenticate via our JS bridge. */
    fun dashboardUrl(haUrl: String, path: String): String {
        val base = haUrl.trim().trimEnd('/')
        val p = normalizeDashboardTarget(path)
        val fragmentAt = p.indexOf('#').let { if (it >= 0) it else p.length }
        val resource = p.substring(0, fragmentAt)
        val fragment = p.substring(fragmentAt)
        val separator = when {
            '?' !in resource -> "?"
            resource.endsWith('?') || resource.endsWith('&') -> ""
            else -> "&"
        }
        return "$base/$resource${separator}external_auth=1$fragment"
    }

    /**
     * JS that writes the HA frontend's own per-device theme store (`selectedTheme` in localStorage —
     * exactly what the profile page's Auto/Light/Dark radio writes). This is the lever that actually
     * re-renders HA: the WebView force-dark route is a no-op on HA because WEB_THEME_DARKENING_ONLY
     * only acts on pages whose `color-scheme` meta declares dark support, and HA only declares it
     * AFTER it has already gone dark (verified live on an Android 8.1 panel, 2026-07-10 — the media
     * query never flips, so force-dark alone never darkened HA at all). [onlyIfAbsent] = seed a
     * DEFAULT without stomping a theme the user picked in HA; false = a deliberate dark-mode toggle,
     * which overrides the stored value the way Home Assistant's own radio does.
     *
     * Like that radio, it changes exactly one field. The store holds `ThemeSettings`, so a write that
     * stringified a fresh `{dark:X}` would take the user's named theme and custom colours down with
     * it — invisibly, because the frontend falls back to `default_dark_theme`/`default_theme` when
     * `theme` is absent and the panel goes on rendering. It therefore runs the same read-modify-write
     * the `dashboard_theme` policy uses (see [InjectionScript.readThemeStoreJs]).
     *
     * The seed is guarded on the RAW stored string rather than on the parse, so a panel that already
     * has an entry is left alone even when that entry is unparseable — seeding is for a fresh panel,
     * and rewriting a value ha-paneld cannot read is not seeding, it is stomping.
     */
    fun selectedThemeJs(dark: Boolean, onlyIfAbsent: Boolean): String {
        val key = JSONObject.quote(InjectionScript.SELECTED_THEME_KEY)
        val body = InjectionScript.readThemeStoreJs(key) +
            (if (onlyIfAbsent) "if(r)return;" else "") +
            InjectionScript.RECOVER_THEME_STORE +
            InjectionScript.writeThemeDarkJs(key, dark)
        return "(()=>{${InjectionScript.TOP_FRAME_GUARD}try{$body}catch(e){}})();"
    }

    /**
     * Document-start JavaScript implementing the `dashboard_theme` policy.
     *
     * [forcedDark] null means Follow Home Assistant and produces the HAND-BACK script; true/false
     * produce the FORCE script. Both are self-gating and idempotent, so they can run on every page
     * load, and both must run before Home Assistant's bundle: the frontend captures its
     * `prefers-color-scheme` MediaQueryList at MODULE scope, and reads `localStorage.selectedTheme` once
     * the socket exists but before the user's server-side theme arrives.
     *
     * Forcing is two layers, because neither alone covers the cases this setting exists for:
     *
     *  1. A NARROW `matchMedia` shim. The frontend resolves `themeSettings?.dark === undefined ?
     *     darkPreferred : themeSettings.dark`, so the shim is what decides for every "Auto" user. It
     *     also decides the FIRST PAINT, before Home Assistant has connected at all. It shims only
     *     `prefers-color-scheme` queries and delegates every other query to the real implementation —
     *     answering all of them would break the frontend's layout breakpoints.
     *  2. A read-modify-write of the `dark` field of `selectedTheme`. This is what beats an EXPLICIT
     *     stored light/dark for a user with no server-side theme, which the shim cannot touch because
     *     `themeSettings.dark` is then defined. It is required rather than optional on Android 9-
     *     panels, where ha-paneld's own `dark_mode` seed has already planted an explicit `dark` there.
     *
     * An explicit theme saved on the Home Assistant user arrives over the socket after connect and
     * replaces both; [dashboardThemeSocketJs] is the third layer that handles it. None of the three
     * ever writes Home Assistant's server-side per-user preference, which may be shared with other
     * panels or be a person's own.
     */
    fun dashboardThemePolicyJs(forcedDark: Boolean?): String {
        val key = JSONObject.quote(InjectionScript.SELECTED_THEME_KEY)
        val marker = JSONObject.quote(InjectionScript.FORCED_THEME_MARKER_KEY)
        val body = if (forcedDark == null) handBackThemeJs(key, marker) else forceThemeJs(forcedDark, key, marker)
        return "(()=>{${InjectionScript.TOP_FRAME_GUARD}try{$body}catch(e){}})();"
    }

    /**
     * Document-start WebSocket wrapper that lets a forcing `dashboard_theme` beat an explicit Light or
     * Dark saved on the Home Assistant user, without ever writing that user's saved theme.
     *
     * After connecting, the frontend subscribes to the user's server-side theme
     * (`frontend/subscribe_user_data`, key `theme`), REPLACES its selected theme with each non-null
     * value, and resolves `dark` from that value before it looks at `prefers-color-scheme`. So neither
     * the shim nor the local store can beat a `dark` that arrives this way. This wrapper sets `dark` to
     * the policy's value in those frames only, in this WebView only; null (no saved theme) is left
     * alone. The user's theme name and colours pass through untouched.
     *
     * The reverse path matters as much. The profile page on this panel saves the WHOLE selected theme
     * back with `frontend/set_user_data`, so a colour change would otherwise save the forced `dark` to
     * an account that may be shared with other panels or be a person's own. Outbound, the wrapper puts
     * back the last `dark` the server sent, or removes it when the server sent none: the panel owns
     * `dark` while its policy forces, and nothing it forced is ever saved.
     *
     * It keys only on the public WebSocket API, never on minified frontend names. If a future frontend
     * resolves the theme differently, the panel stops winning and `theme_overridden` reports it. It must
     * be registered after every other WebSocket wrapper: the entity filter's `send` does not chain, so
     * only the outermost wrapper is sure to see each outbound frame.
     */
    fun dashboardThemeSocketJs(dark: Boolean, haUrl: String, documentOrigins: Collection<String>): String {
        val (targetWsOrigins, targetWsPath) = InjectionScript.wsTargets(haUrl, documentOrigins)
        return """
            (()=>{
              ${InjectionScript.TOP_FRAME_GUARD}
              const Parent=window.WebSocket;
              if(!Parent||Parent.__haPaneldThemeSocket)return;
              const targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath,forced=$dark;
              let serverDark;
              const themeCommand=(m,type)=>m&&m.type===type&&m.key==='theme';
              function force(m,ids){
                if(!m||m.type!=='event'||!ids.has(m.id)||!m.event)return false;
                const v=m.event.value;
                if(!v||typeof v!=='object'||Array.isArray(v))return false;
                serverDark=typeof v.dark==='boolean'?v.dark:undefined;
                v.dark=forced;
                return true;
              }
              function ThemeWebSocket(url,protocols){
                const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
                try{
                  const u=new URL(String(url),location.href);
                  if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
                    const ids=new Set(),send=socket.send;
                    Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                      let outgoing=data;
                      if(typeof data==='string'&&data.indexOf('"theme"')>=0)try{
                        const m=JSON.parse(data);
                        if(themeCommand(m,'frontend/subscribe_user_data'))ids.add(m.id);
                        else if(themeCommand(m,'frontend/set_user_data')&&m.value&&typeof m.value==='object'){
                          if(serverDark===undefined)delete m.value.dark;else m.value.dark=serverDark;
                          outgoing=JSON.stringify(m);
                        }
                      }catch(e){}
                      return send.call(this,outgoing);
                    }});
                    socket.addEventListener('message',event=>{
                      if(!ids.size||typeof event.data!=='string'||event.data.indexOf('"value"')<0)return;
                      let m;try{m=JSON.parse(event.data)}catch(e){return}
                      let changed=false;
                      if(Array.isArray(m)){for(const item of m)if(force(item,ids))changed=true}
                      else changed=force(m,ids);
                      if(changed)Object.defineProperty(event,'data',{configurable:true,value:JSON.stringify(m)});
                    });
                  }
                }catch(e){}
                return socket;
              }
              Object.setPrototypeOf(ThemeWebSocket,Parent);
              ThemeWebSocket.prototype=Parent.prototype;
              for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(ThemeWebSocket,k,{value:Parent[k]});
              Object.defineProperty(ThemeWebSocket,'__haPaneldThemeSocket',{value:true});
              window.WebSocket=ThemeWebSocket;
            })();
        """.trimIndent()
    }

    /** Take ownership of the `dark` field, recording what it held so it can be handed back exactly. */
    private fun forceThemeJs(dark: Boolean, key: String, marker: String): String = (
        // The shim answers a `dark` query with the policy and a `light` query with its negation, so a
        // frontend asking either way gets one coherent answer. `no-preference` stays false, which is
        // what a real browser reports once a preference is stated.
        "var n=window.matchMedia&&window.matchMedia.bind(window);" +
            "if(n){window.matchMedia=function(q){var s=String(q);" +
            "var c=/^\\s*\\(\\s*prefers-color-scheme\\s*:\\s*(dark|light)\\s*\\)\\s*$/i.exec(s);" +
            "if(!c)return n(s);" +
            "var m=c[1].toLowerCase()==='dark'?$dark:${!dark};" +
            "return{media:s,matches:m,onchange:null," +
            // themes-mixin uses the DEPRECATED addListener, so both spellings must exist. They are
            // no-ops on purpose: a policy change rebuilds the WebView, so there is no change to emit.
            "addListener:function(){},removeListener:function(){}," +
            "addEventListener:function(){},removeEventListener:function(){}," +
            "dispatchEvent:function(){return false}}};}" +
            InjectionScript.readThemeStoreJs(key) +
            InjectionScript.RECOVER_THEME_STORE +
            // Snapshot once, on the first load under this policy. A later load must not re-snapshot, or
            // the recorded original would become the value this script itself wrote.
            "if(localStorage.getItem($marker)===null){" +
            "localStorage.setItem($marker,JSON.stringify({a:r===null,d:typeof o.dark==='boolean'?o.dark:null}))}" +
            InjectionScript.writeThemeDarkJs(key, dark)
        )

    /** Give the `dark` field back, and only that field; every other key in the object is the user's. */
    private fun handBackThemeJs(key: String, marker: String): String = (
        "var k=localStorage.getItem($marker);if(k===null)return;" +
            "var m={};try{m=JSON.parse(k)||{}}catch(e){}" +
            InjectionScript.readThemeStoreJs(key) +
            "if(o&&typeof o==='object'&&!(o instanceof Array)){" +
            // Absent originally means absent again: restoring `false` would invent a preference the
            // user never expressed, and `dark:false` is not the same as Auto to the frontend.
            "if(m.d===true||m.d===false)o.dark=m.d;else delete o.dark;" +
            "var c=0;for(var p in o)if(Object.prototype.hasOwnProperty.call(o,p))c++;" +
            // If ha-paneld created the whole entry and nothing but `dark` was ever in it, remove the
            // entry rather than leave an empty object where Home Assistant had stored nothing at all.
            "if(m.a===true&&c===0)localStorage.removeItem($key);" +
            "else localStorage.setItem($key,JSON.stringify(o))}" +
            "localStorage.removeItem($marker);"
        )

    /**
     * Reads HA's stored per-device theme (`s`) and the scheme the frontend is rendering now (`e`).
     * Both are booleans or null; nothing else from the page is returned, so the result is safe to log.
     * The effective value comes from `hass.themes.darkMode`, which is what the profile radio, the
     * server-side preference and `prefers-color-scheme` all resolve INTO — the one place the answer
     * to "what is on screen" is not a guess.
     */
    const val THEME_OBSERVATION_JS = """(function(){var s=null,e=null;try{var t=JSON.parse(localStorage.getItem('${InjectionScript.SELECTED_THEME_KEY}')||'null');s=t&&typeof t.dark==='boolean'?t.dark:null}catch(x){}try{var h=document.querySelector('home-assistant');var d=h&&h.hass&&h.hass.themes?h.hass.themes.darkMode:null;e=typeof d==='boolean'?d:null}catch(x){}return JSON.stringify({s:s,e:e})})()"""

    /** One theme observation; [valid] is false when the page returned something unparseable. */
    data class ThemeObservation(val storedDark: Boolean?, val effectiveDark: Boolean?, val valid: Boolean)

    /** Parse the `evaluateJavascript` result of [THEME_OBSERVATION_JS] (a JSON string, itself JSON-quoted). */
    fun parseThemeObservation(result: String?): ThemeObservation {
        val raw = result ?: return ThemeObservation(null, null, valid = false)
        return runCatching {
            // evaluateJavascript hands back the string VALUE as a JSON literal, so unquote it first.
            val inner = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
            val o = JSONObject(inner)
            fun field(k: String): Boolean? = if (o.isNull(k)) null else o.optBoolean(k).takeIf { o.get(k) is Boolean }
            ThemeObservation(field("s"), field("e"), valid = true)
        }.getOrElse { ThemeObservation(null, null, valid = false) }
    }

    /** Validate the fixed frontend callback before any token lookup or network refresh is attempted. */
    fun validAuthRequestForce(payload: String): Boolean? {
        if (payload.length > MAX_AUTH_PAYLOAD_CHARS) return null
        val message = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (message.opt("callback") != "externalAuthSetToken") return null
        return message.optBoolean("force", false)
    }


    /** `getExternalAuth` reply: `externalAuthSetToken(true, {access_token, expires_in})`, or a
     *  `(false)` failure when no token is available (unconfigured, or a refresh that failed closed).
     *  [expiresInSec] is the token's remaining life the frontend uses to know when to ask again. */
    fun authReply(payload: String, accessToken: String?, expiresInSec: Long): String? {
        if (callbackOf(payload) != "externalAuthSetToken") return null
        if (accessToken.isNullOrBlank()) return "externalAuthSetToken(false)"
        val auth = JSONObject().put("access_token", accessToken).put("expires_in", expiresInSec)
        return "externalAuthSetToken(true, $auth)"
    }

    /** `revokeExternalAuth` ack. The panel doesn't own the long-lived token (the admin minted it),
     *  so there's no server-side revoke to perform — acknowledge so the frontend can finish logout. */
    fun revokeReply(payload: String): String? =
        if (callbackOf(payload) == "externalAuthRevokeToken") "externalAuthRevokeToken(true)" else null

    /** Document-start script that forces panel-appropriate HA frontend prefs on this WebView's FIRST
     *  run, then never again: hide the sidebar, keep the websocket alive when idle, no haptics. Values
     *  are `JSON.stringify`'d to match HA's `ha-pref-storage` localStorage format. Self-gated by a
     *  sentinel key so it applies once, survives reloads/restarts, never clobbers a later user change,
     *  and re-applies after a renderer-storage wipe (a fresh first run). */
    fun panelDefaultsJs(): String =
        """(function(){try{
            ${InjectionScript.TOP_FRAME_GUARD}
            if(localStorage.getItem('__hapaneld_panel_defaults'))return;
            localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));
            localStorage.setItem('suspendWhenHidden',JSON.stringify(false));
            localStorage.setItem('vibrate',JSON.stringify(false));
            localStorage.setItem('__hapaneld_panel_defaults','1');
        }catch(e){}})();"""

    private fun callbackOf(payload: String): String =
        if (payload.length > MAX_AUTH_PAYLOAD_CHARS) ""
        else runCatching { JSONObject(payload).optString("callback") }.getOrDefault("")
}
