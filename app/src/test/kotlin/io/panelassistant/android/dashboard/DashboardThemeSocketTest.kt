package io.panelassistant.android.dashboard

import io.panelassistant.android.ExternalAuthProtocol
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panel's Dashboard theme against a theme saved on its Home Assistant user, run through the scripts
 * the built-in renderer installs, in the order it installs them, under a model of the frontend.
 *
 * The model is what Home Assistant's frontend does with that saved theme (themes mixin and
 * home-assistant-js-websocket, frontend 20260930.3): it subscribes with `frontend/subscribe_user_data`
 * key `theme`, the server answers on that id first and again on every later change, frames may arrive
 * coalesced into one array, a non-null value REPLACES the selected theme and is mirrored into
 * localStorage, null is ignored, and `dark` resolves as `selected.dark === undefined ?
 * prefers-color-scheme : selected.dark`, after which a named theme without both modes decides on its
 * own. The profile page saves the whole selected theme back with `frontend/set_user_data`.
 *
 * What the owner sees is the resolved `dark`, and what the account keeps is what was sent: those two are
 * what every test here asserts.
 */
class DashboardThemeSocketTest {

    private val haUrl = "https://ha.example"

    /** Scripts exactly as `createDashboardWebView` installs them for [policy] (null = Follow). */
    private fun installed(policy: Boolean?): List<String> = listOfNotNull(
        ExternalAuthProtocol.dashboardThemePolicyJs(policy),
        EntityFilterProtocol.documentStartScript(haUrl, listOf("light.kitchen")),
        policy?.let { ExternalAuthProtocol.dashboardThemeSocketJs(it, haUrl, setOf(haUrl)) },
    )

    /**
     * Runs [scenario] against a page with [policy] whose local store starts as [localTheme]. The scenario
     * drives `serve(frame)` (the server), `settheme(detail)` (the profile page) and reads `dark()`,
     * `sent()` and `delivered`, and prints its result.
     */
    private fun page(policy: Boolean?, scenario: String, localTheme: String? = null, scripts: List<String> = installed(policy)): String {
        Node.assumeAvailable()
        val seed = localTheme?.let { "store.set('selectedTheme', ${quote(it)});" } ?: ""
        val harness = """
            const store=new Map();$seed
            globalThis.localStorage={getItem:k=>store.has(k)?store.get(k):null,setItem:(k,v)=>store.set(k,String(v)),removeItem:k=>store.delete(k)};
            globalThis.window=globalThis;window.top=window;
            globalThis.location={href:'$haUrl/lovelace/0'};
            globalThis.matchMedia=q=>({media:String(q),matches:false});
            class Native extends EventTarget{
              constructor(url){super();this.url=String(url);this.wire=[];}
              send(data){this.wire.push(data);}
              serve(data){this.dispatchEvent(new MessageEvent('message',{data}));}
            }
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            globalThis.WebSocket=Native;
            ${scripts.joinToString("\n")}
            // --- the frontend -------------------------------------------------------------------------
            const THEMES={dual:{modes:{light:{},dark:{}}},plain:{}};
            const mql=matchMedia('(prefers-color-scheme: dark)');
            const socket=new WebSocket('wss://ha.example/api/websocket');
            const callbacks=new Map();const delivered=[];let nextId=1;
            socket.addEventListener('message',ev=>{
              delivered.push(ev.data);
              let frames=JSON.parse(ev.data);if(!Array.isArray(frames))frames=[frames];
              for(const f of frames){const cb=callbacks.get(f.id);if(cb&&f.type==='event')cb(f.event)}
            });
            let selected=JSON.parse(localStorage.getItem('selectedTheme')||'null')||{};
            const received=[];
            const themeId=nextId++;
            callbacks.set(themeId,({value})=>{received.push(value);if(value){selected=value;localStorage.setItem('selectedTheme',JSON.stringify(value))}});
            socket.send(JSON.stringify({type:'frontend/subscribe_user_data',key:'theme',id:themeId}));
            socket.send(JSON.stringify({type:'subscribe_entities',id:nextId++}));
            function dark(){
              let d=selected.dark===undefined?mql.matches:selected.dark;
              const t=THEMES[selected.theme||'default'];
              if(t){if(t.modes&&'dark' in t.modes){if(!('light' in t.modes))d=true}else d=false}
              return d;
            }
            function settheme(detail){
              selected={...selected,...detail};
              socket.send(JSON.stringify({type:'frontend/set_user_data',key:'theme',value:selected,id:nextId++}));
            }
            const serve=frame=>socket.serve(typeof frame==='string'?frame:JSON.stringify(frame));
            const sent=type=>socket.wire.map(w=>JSON.parse(w)).filter(m=>m.type===type);
            const saved=()=>sent('frontend/set_user_data').map(m=>m.value);
            $scenario
        """.trimIndent()
        val (code, out) = Node.run(stdin = harness)
        assertEquals("page run failed:\n$out", 0, code)
        return out.trim()
    }

    private fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val darkUser = """serve({id:themeId,type:'event',event:{value:{dark:true}}});"""
    private val lightUser = """serve({id:themeId,type:'event',event:{value:{dark:false}}});"""

    // --- the panel wins ---------------------------------------------------------------------------

    @Test fun aLightPanelShowsLightWhenItsUserSavedDark() {
        assertEquals("false", page(false, "$darkUser console.log(dark())"))
    }

    @Test fun aDarkPanelShowsDarkWhenItsUserSavedLight() {
        assertEquals("true", page(true, "$lightUser console.log(dark())"))
    }

    @Test fun followStillShowsWhatTheUserSaved() {
        assertEquals("true", page(null, "$darkUser console.log(dark())"))
        assertEquals("false", page(null, "$lightUser console.log(dark())"))
    }

    @Test fun aLaterChangeFromAnotherDeviceDoesNotTakeThePanelBack() {
        // The server pushes the user's theme again whenever any client saves it.
        val out = page(false, "$lightUser $darkUser console.log(dark())")
        assertEquals("false", out)
    }

    @Test fun aCoalescedFrameIsForcedAndEverythingElseInItArrivesUnchanged() {
        val entity = """{"id":2,"type":"event","event":{"a":{"light.kitchen":{"s":"on","a":{"value":1}}}}}"""
        val out = page(
            false,
            """
            serve('[$entity,'+JSON.stringify({id:themeId,type:'event',event:{value:{dark:true,theme:'dual'}}})+']');
            const frames=JSON.parse(delivered[0]);
            console.log(JSON.stringify([dark(),JSON.stringify(frames[0])===${quote(entity)},selected.theme,delivered.length]));
            """.trimIndent(),
        )
        assertEquals("""[false,true,"dual",1]""", out)
    }

    @Test fun aFrameThatIsNotTheUsersThemeIsDeliveredByteForByte() {
        val frame = """{"id":2,"type":"event","event":{"c":{"sensor.value":{"+":{"s":"21","a":{"value":"x"}}}}}}"""
        val out = page(false, "serve(${quote(frame)}); console.log(delivered[0]===${quote(frame)})")
        assertEquals("true", out)
    }

    @Test fun noSavedThemeStaysNoSavedTheme() {
        // Null tells the profile page there is nothing saved (it offers to save the local theme); a
        // value invented here would hide that and replace the local store.
        val out = page(false, "serve({id:themeId,type:'event',event:{value:null}}); console.log(JSON.stringify([received,dark()]))")
        assertEquals("[[null],false]", out)
    }

    // --- the account is never written -------------------------------------------------------------

    @Test fun aColourChangeOnThePanelSavesTheUsersOwnDarkNotThePanels() {
        val out = page(
            false,
            """serve({id:themeId,type:'event',event:{value:{dark:true,theme:'dual'}}}); settheme({primaryColor:'#ff0000'}); console.log(JSON.stringify(saved()))""",
        )
        assertEquals("""[{"dark":true,"theme":"dual","primaryColor":"#ff0000"}]""", out)
    }

    @Test fun aUserWithoutASavedDarkIsNeverGivenOne() {
        // Auto on the account ({}), and no saved theme at all (null with a forced local store).
        assertEquals(
            """[{"primaryColor":"#ff0000"}]""",
            page(true, """serve({id:themeId,type:'event',event:{value:{}}}); settheme({primaryColor:'#ff0000'}); console.log(JSON.stringify(saved()))"""),
        )
        assertEquals(
            """[{"primaryColor":"#ff0000"}]""",
            page(
                true,
                """serve({id:themeId,type:'event',event:{value:null}}); settheme({primaryColor:'#ff0000'}); console.log(JSON.stringify(saved()))""",
                localTheme = """{"dark":false}""",
            ),
        )
    }

    @Test fun followSavesWhatTheProfilePageChose() {
        val out = page(null, """$darkUser settheme({dark:false}); console.log(JSON.stringify(saved()))""")
        assertEquals("""[{"dark":false}]""", out)
    }

    // --- named themes and the rest of the page ----------------------------------------------------

    @Test fun aNamedThemeWithBothModesFollowsThePanel() {
        val out = page(true, """serve({id:themeId,type:'event',event:{value:{theme:'dual',dark:false}}}); console.log(JSON.stringify([dark(),selected.theme]))""")
        assertEquals("""[true,"dual"]""", out)
    }

    @Test fun theEntityFilterStillNarrowsTheSubscriptionThroughTheThemeWrapper() {
        val out = page(false, """console.log(JSON.stringify(sent('subscribe_entities')[0].entity_ids))""")
        assertEquals("""["light.kitchen"]""", out)
    }

    @Test fun aSecondSocketToAnotherPathIsLeftAlone() {
        val out = page(
            false,
            """
            const other=new WebSocket('wss://ha.example/other');
            other.send(JSON.stringify({type:'frontend/set_user_data',key:'theme',value:{dark:false},id:9}));
            console.log(other.wire[0]);
            """.trimIndent(),
        )
        assertEquals("""{"type":"frontend/set_user_data","key":"theme","value":{"dark":false},"id":9}""", out)
    }
}
