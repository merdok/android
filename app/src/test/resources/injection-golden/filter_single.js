(()=>{
  if(window.top&&window.top!==window)return;
  const Native=window.WebSocket;
  if(!Native||Native.__haPaneldEntityFilter)return;
  const targetWsOrigins=["wss://ha.example"],targetWsPath="/api/websocket",entityIds=["light.alpha","sensor.temperature"],emptySubscriptionEntityId="ha_paneld_internal.empty_subscription_5f39d48b7a6c4e2a",filterKeys=["entity_ids","exclude","exclude_domains","exclude_entities","exclude_entity_globs","include","include_domains","include_entities","include_entity_globs"];
  const allowed=new Set(entityIds),registryCommands={'config/entity_registry/list_for_display':'entities','config/device_registry/list':'devices'};
  function FilteredWebSocket(url,protocols){
    const socket=protocols===undefined?new Native(url):new Native(url,protocols);
    try{
      const u=new URL(String(url),location.href);
      if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
        const pending=new Map();let reachedDevices=null;
        const trim={
          entities(result){
            const rows=result&&typeof result==='object'?result.entities:null;
            if(!Array.isArray(rows)||!rows.every(r=>r&&typeof r.ei==='string'))return false;
            const kept=rows.filter(r=>allowed.has(r.ei));
            reachedDevices=new Set(kept.map(r=>r.di).filter(d=>typeof d==='string'));
            result.entities=kept;return true;
          },
          devices(rows,message){
            if(!reachedDevices||!Array.isArray(rows)||!rows.every(r=>r&&typeof r.id==='string'))return false;
            const keep=new Set(reachedDevices);
            for(const r of rows)if(reachedDevices.has(r.id)&&typeof r.parent_device_id==='string')keep.add(r.parent_device_id);
            const kept=rows.filter(r=>keep.has(r.id));
            message.result=kept;return true;
          },
        };
        Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
          let outgoing=data;
          if(typeof data==='string')try{
            const message=JSON.parse(data);
            if(message&&registryCommands[message.type])pending.set(message.id,registryCommands[message.type]);
            if(message&&message.type==='subscribe_entities'){
              window.__haPaneldEntitySubscriptionId=message.id;
              if(!filterKeys.some(key=>Object.prototype.hasOwnProperty.call(message,key))){
                message.entity_ids=entityIds.length?entityIds:[emptySubscriptionEntityId];
                outgoing=JSON.stringify(message);
                try{if(window.haPaneldV2&&typeof window.haPaneldV2.postMessage==='function')window.haPaneldV2.postMessage(JSON.stringify({type:'entityFilterSubscriptionModified'}));}catch(e){}
              }
            }
          }catch(e){}
          return Native.prototype.send.call(this,outgoing);
        }});
        socket.addEventListener('message',event=>{
          if(!pending.size)return;
          let decoded;try{decoded=JSON.parse(event.data)}catch(e){return}
          let changed=false;
          for(const m of Array.isArray(decoded)?decoded:[decoded]){
            if(!m||m.type!=='result'||!pending.has(m.id))continue;
            const kind=pending.get(m.id);pending.delete(m.id);
            if(trim[kind](m.result,m))changed=true;
          }
          if(changed)Object.defineProperty(event,'data',{configurable:true,value:JSON.stringify(decoded)});
        });
      }
    }catch(e){}
    return socket;
  }
  Object.setPrototypeOf(FilteredWebSocket,Native);
  FilteredWebSocket.prototype=Native.prototype;
  for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(FilteredWebSocket,k,{value:Native[k]});
  Object.defineProperty(FilteredWebSocket,'__haPaneldEntityFilter',{value:true});
  window.WebSocket=FilteredWebSocket;
})();
