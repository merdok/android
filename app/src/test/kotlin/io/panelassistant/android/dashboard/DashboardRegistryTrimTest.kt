package io.panelassistant.android.dashboard

import io.panelassistant.android.ExternalAuthProtocol
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The registry results a filtered dashboard hands Home Assistant's frontend, run through the scripts the
 * built-in renderer installs, in the order it installs them, under a model of the frontend.
 *
 * The model is what frontend 20260930.3 does with them (connection-mixin, ws-device_registry and
 * home-assistant-js-websocket): it sends `config/entity_registry/list_for_display`,
 * `config/device_registry/list` and `config/area_registry/list` on connect, Home Assistant answers them in
 * one batched array frame among other messages, the device list is fetched again on every
 * `device_registry_updated`, and a child device takes its manufacturer from its parent in the same list
 * and its area when it has none. What the owner sees is what the frontend holds: names, devices and areas.
 */
class DashboardRegistryTrimTest {

    private val haUrl = "https://ha.example"
    private val allowed = listOf("light.kitchen", "sensor.hall_temperature")

    /** Scripts as `createDashboardWebView` installs them with the filter on and a forcing theme. */
    private fun installed(filter: Boolean = true): List<String> = listOfNotNull(
        ExternalAuthProtocol.dashboardThemePolicyJs(false),
        if (filter) EntityFilterProtocol.documentStartScript(haUrl, allowed) else null,
        EntityLearningProtocol.documentStartScript(haUrl, setOf(haUrl), featureCostsEnabled = true),
        ExternalAuthProtocol.dashboardThemeSocketJs(false, haUrl, setOf(haUrl)),
    )

    private fun page(scenario: String, scripts: List<String> = installed()): String {
        Node.assumeAvailable()
        val harness = """
            const store=new Map();
            globalThis.localStorage={getItem:k=>store.has(k)?store.get(k):null,setItem:(k,v)=>store.set(k,String(v)),removeItem:k=>store.delete(k)};
            globalThis.window=globalThis;window.top=window;
            globalThis.location={href:'$haUrl/lovelace/0'};
            globalThis.matchMedia=q=>({media:String(q),matches:false});
            globalThis.document={querySelector(){return null}};
            globalThis.setInterval=()=>0;
            class Native extends EventTarget{
              constructor(url){super();this.url=String(url);this.wire=[];}
              send(data){this.wire.push(data);}
              serve(data){this.dispatchEvent(new MessageEvent('message',{data}));}
            }
            Native.CONNECTING=0;Native.OPEN=1;Native.CLOSING=2;Native.CLOSED=3;
            globalThis.WebSocket=Native;
            ${scripts.joinToString("\n")}
            // --- the frontend -------------------------------------------------------------------------
            const socket=new WebSocket('wss://ha.example/api/websocket');
            const delivered=[],pending=new Map(),events=new Map();let nextId=1;
            const hass={entities:{},devices:{},areas:{}};
            socket.addEventListener('message',ev=>{
              delivered.push(ev.data);
              let frames;try{frames=JSON.parse(ev.data)}catch(e){return}
              if(!Array.isArray(frames))frames=[frames];
              for(const f of frames){const cb=pending.get(f.id);if(cb&&f.type==='result'){pending.delete(f.id);if(f.success)cb(f.result)}
                const listener=events.get(f.id);if(listener&&f.type==='event')listener(f.event)}
            });
            function request(type,cb){const id=nextId++;pending.set(id,cb);socket.send(JSON.stringify({type,id}));return id}
            const entityRequest=()=>request('config/entity_registry/list_for_display',r=>{
              hass.entities={};for(const e of r.entities||[])hass.entities[e.ei]={entity_id:e.ei,name:e.en,device_id:e.di,area_id:e.ai}});
            const deviceRequest=()=>request('config/device_registry/list',rows=>{
              const parents=new Map(rows.filter(d=>'connections' in d).map(d=>[d.id,d]));hass.devices={};
              for(const d of rows){const p=parents.get(d.parent_device_id);
                hass.devices[d.id]='connections' in d?d:{manufacturer:p?p.manufacturer:null,...d}}});
            const areaRequest=()=>request('config/area_registry/list',rows=>{hass.areas={};for(const a of rows)hass.areas[a.area_id]=a});
            const subId=nextId++;socket.send(JSON.stringify({type:'subscribe_entities',id:subId}));
            // ws-device_registry: every device_registry_updated refetches the whole device list.
            const subscribeDeviceEvents=()=>{const id=nextId++;events.set(id,()=>deviceRequest());socket.send(JSON.stringify({type:'subscribe_events',event_type:'device_registry_updated',id}))};
            const lastRequest=type=>socket.wire.map(w=>JSON.parse(w)).filter(m=>m.type===type).pop().id;
            const serve=frame=>socket.serve(typeof frame==='string'?frame:JSON.stringify(frame));
            const result=(id,value)=>({id,type:'result',success:true,result:value});
            // What the user sees for an entity: its name, its device's maker and its area's name.
            function shown(entityId){
              const e=hass.entities[entityId];if(!e)return entityId;
              const d=e.device_id&&hass.devices[e.device_id];
              const p=d&&d.parent_device_id&&hass.devices[d.parent_device_id];
              const areaId=e.area_id||(d&&(d.area_id||(p&&p.area_id)));
              const a=areaId&&hass.areas[areaId];
              return [e.name,d?d.manufacturer:'-',a?a.name:'-'].join('/');
            }
            const ENTITIES={entity_categories:{'0':'config','1':'diagnostic'},entities:[
              {ei:'light.kitchen',en:'Kitchen light',di:'dev_kitchen',ec:0},
              {ei:'sensor.hall_temperature',en:'Hall temperature',di:'dev_hall_child'},
              {ei:'switch.garage',en:'Garage',di:'dev_garage'},
              {ei:'sensor.loft',en:'Loft',ai:'loft'}]};
            const DEVICES=[
              {id:'dev_kitchen',name:'Kitchen bulb',manufacturer:'Hue',area_id:'kitchen',connections:[]},
              {id:'dev_hall_child',name:'Hall sensor',parent_device_id:'dev_hub',area_id:null},
              {id:'dev_hub',name:'Hub',manufacturer:'Aqara',area_id:'hall',connections:[]},
              {id:'dev_garage',name:'Garage relay',manufacturer:'Shelly',area_id:'garage',connections:[]}];
            const AREAS=[{area_id:'kitchen',name:'Kitchen'},{area_id:'hall',name:'Hall'},{area_id:'garage',name:'Garage'},{area_id:'loft',name:'Loft'}];
            const STATE_EVENT=JSON.stringify({id:subId,type:'event',event:{a:{'light.kitchen':{s:'on',a:{}}}}});
            $scenario
        """.trimIndent()
        val (code, out) = Node.run(stdin = harness)
        assertEquals("page run failed:\n$out", 0, code)
        return out.trim()
    }

    /** The connect batch exactly as Home Assistant coalesces it: a state event among the three results. */
    private val connectBatch = """
        subscribeDeviceEvents();const e=entityRequest(),d=deviceRequest(),a=areaRequest();
        serve('['+STATE_EVENT+','+[result(e,ENTITIES),result(d,DEVICES),result(a,AREAS)].map(m=>JSON.stringify(m)).join(',')+']');
    """.trimIndent()

    @Test fun aBatchedConnectFrameHandsTheFrontendOnlyTheAllowedEntitiesAndTheirDevices() {
        val out = page(
            """
            $connectBatch
            console.log(JSON.stringify([Object.keys(hass.entities),Object.keys(hass.devices).sort(),Object.keys(hass.areas).length]));
            """.trimIndent(),
        )
        assertEquals(
            """[["light.kitchen","sensor.hall_temperature"],["dev_hall_child","dev_hub","dev_kitchen"],4]""",
            out,
        )
    }

    @Test fun everyAllowedEntityShowsTheSameNameMakerAndAreaAsUntrimmed() {
        val show = "$connectBatch console.log(JSON.stringify(['light.kitchen','sensor.hall_temperature'].map(shown)));"
        val trimmed = page(show)
        assertEquals("""["Kitchen light/Hue/Kitchen","Hall temperature/Aqara/Hall"]""", trimmed)
        assertEquals(page(show, installed(filter = false)), trimmed)
    }

    @Test fun otherMessagesInTheBatchArriveUnchanged() {
        val out = page(
            """
            $connectBatch
            const frames=JSON.parse(delivered[0]);
            console.log(JSON.stringify([delivered.length,JSON.stringify(frames[0])===STATE_EVENT,JSON.stringify(frames[3].result)===JSON.stringify(AREAS),JSON.stringify(frames[1].result.entity_categories)]));
            """.trimIndent(),
        )
        assertEquals("""[1,true,true,"{\"0\":\"config\",\"1\":\"diagnostic\"}"]""", out)
    }

    @Test fun aFrameWithoutARegistryResultArrivesByteForByteWhileOneIsPending() {
        // Home Assistant writes 21.0 where JSON.stringify would write 21: a frame it has nothing to trim
        // in must not be re-encoded.
        val out = page(
            """
            entityRequest();
            const frame='[{"id":'+subId+',"type":"event","event":{"c":{"sensor.hall_temperature":{"+":{"s":"21.0","a":{"t":21.0}}}}}}]';
            serve(frame);
            console.log(JSON.stringify([delivered[0]===frame]));
            """.trimIndent(),
        )
        assertEquals("[true]", out)
    }

    @Test fun anEntityMovedToADeviceTheLastDeviceListWithheldGetsThatDevice() {
        // Home Assistant answers the frontend's two refetches independently: here the device list (with the
        // kitchen light's new device) lands first, then the entity registry that moves the light onto it.
        val out = page(
            """
            $connectBatch
            const moved=[...DEVICES,{id:'dev_kitchen_new',name:'Kitchen bulb v2',manufacturer:'IKEA',area_id:'kitchen',connections:[]}];
            const d2=deviceRequest();serve(result(d2,moved));
            const e2=entityRequest();
            serve(result(e2,{...ENTITIES,entities:ENTITIES.entities.map(r=>r.ei==='light.kitchen'?{...r,di:'dev_kitchen_new'}:r)}));
            setTimeout(()=>{
              serve(result(lastRequest('config/device_registry/list'),moved));
              console.log(JSON.stringify([shown('light.kitchen'),Object.keys(hass.devices).sort()]));
            },20);
            """.trimIndent(),
        )
        assertEquals(
            """["Kitchen light/IKEA/Kitchen",["dev_hall_child","dev_hub","dev_kitchen_new"]]""",
            out,
        )
    }

    @Test fun anEntityRefreshThatReachesNoWithheldDeviceCausesNoDeviceRefetch() {
        val out = page(
            """
            $connectBatch
            const before=socket.wire.length;const e2=entityRequest();serve(result(e2,ENTITIES));
            setTimeout(()=>console.log(JSON.stringify(socket.wire.slice(before).map(w=>JSON.parse(w).type))),20);
            """.trimIndent(),
        )
        assertEquals("""["config/entity_registry/list_for_display"]""", out)
    }

    @Test fun withoutTheFrontendsDeviceSubscriptionDevicesArriveWhole() {
        // Nothing could tell the frontend to refetch a withheld device, so none is withheld.
        val out = page(
            """
            const e=entityRequest(),d=deviceRequest();
            serve('['+[result(e,ENTITIES),result(d,DEVICES)].map(m=>JSON.stringify(m)).join(',')+']');
            console.log(JSON.stringify([Object.keys(hass.entities).length,Object.keys(hass.devices).length]));
            """.trimIndent(),
        )
        assertEquals("[2,4]", out)
    }

    @Test fun aDeviceRefetchInItsOwnFrameIsTrimmedToo() {
        val out = page(
            """
            $connectBatch
            const again=deviceRequest();serve(result(again,DEVICES));
            console.log(JSON.stringify(JSON.parse(delivered[1]).result.map(d=>d.id).sort()));
            """.trimIndent(),
        )
        assertEquals("""["dev_hall_child","dev_hub","dev_kitchen"]""", out)
    }

    @Test fun withoutTheFilterNothingIsTrimmed() {
        val out = page(
            "$connectBatch console.log(JSON.stringify([Object.keys(hass.entities).length,Object.keys(hass.devices).length]));",
            installed(filter = false),
        )
        assertEquals("[4,4]", out)
    }

    @Test fun devicesBeforeAnyEntityRegistryArriveWhole() {
        // Nothing yet says which devices the allowed entities reach, so keeping all is the safe answer.
        val out = page(
            "subscribeDeviceEvents();const d=deviceRequest();serve(result(d,DEVICES));console.log(Object.keys(hass.devices).length);",
        )
        assertEquals("4", out)
    }

    @Test fun anUnknownEntityRegistryShapeArrivesByteForByteAndLeavesDevicesWhole() {
        // A renamed row list, or rows keyed other than `ei`, must not read as "no allowed rows".
        val out = page(
            """
            const shapes=[{rows:ENTITIES.entities},{entities:ENTITIES.entities.map(({ei,...r})=>({entity_id:ei,...r}))}];
            subscribeDeviceEvents();
            const served=shapes.map(shape=>{
              const e=entityRequest(),d=deviceRequest();
              const frame=JSON.stringify([{id:e,type:'result',success:true,result:shape},result(d,DEVICES)]);
              serve(frame);return frame;
            });
            console.log(JSON.stringify(delivered.map((f,i)=>f===served[i])));
            """.trimIndent(),
        )
        assertEquals("[true,true]", out)
    }

    @Test fun deviceRowsWithoutAnIdArriveWhole() {
        val out = page(
            """
            $connectBatch
            const again=deviceRequest();const odd=JSON.stringify(result(again,DEVICES.map(({id,...r})=>({device_id:id,...r}))));
            serve(odd);
            console.log(JSON.stringify([delivered[1]===odd]));
            """.trimIndent(),
        )
        assertEquals("[true]", out)
    }

    @Test fun aMalformedFrameWhileARegistryRequestIsPendingArrivesByteForByte() {
        val out = page(
            """
            entityRequest();
            const broken='[{"id":2,"type":"result","success":true,"result":{"entities":[';
            serve(broken);
            console.log(JSON.stringify([delivered[0]===broken]));
            """.trimIndent(),
        )
        assertEquals("[true]", out)
    }

    @Test fun aBinaryFrameWhileARegistryRequestIsPendingArrivesUntouched() {
        val out = page(
            """
            entityRequest();
            const bytes=new Uint8Array([0x5b,0x7b,0x7d,0x5d]).buffer;
            socket.serve(bytes);
            console.log(JSON.stringify([delivered[0]===bytes]));
            """.trimIndent(),
        )
        assertEquals("[true]", out)
    }

    @Test fun aFailedRegistryResultArrivesByteForByte() {
        val out = page(
            """
            const e=entityRequest();
            const failed=JSON.stringify({id:e,type:'result',success:false,error:{code:'unknown_command',message:'x'}});
            serve(failed);
            console.log(JSON.stringify([delivered[0]===failed]));
            """.trimIndent(),
        )
        assertEquals("[true]", out)
    }
}
