'use strict'
const test=require('node:test')
const assert=require('node:assert/strict')
const {createBridge,normalize,config,digest}=require('../bot/receiver')
const {skinIdentity,createSkinResolver}=require('../bot/players')
const A='111111111111111111',B='222222222222222222',S='0123456789abcdef',T='a'.repeat(64),texture='b'.repeat(64)
const body=now=>({protocol:1,sentAt:now,online:1,max:50,version:'26.3',tps:19.8,mspt:12.3,memoryUsed:100,memoryMax:200,players:[{name:'Alex',uuid:'12345678-1234-4234-8234-123456789abc',texture,ping:37}]})
test('snapshot preserves applied texture and metrics and rejects stale/malformed data',()=>{
  const now=1000000,r=normalize(body(now),now)
  assert.equal(r.roster[0].texture,texture)
  assert.equal(r.roster[0].ping,37)
  assert.equal(r.tps,19.8)
  assert.equal(skinIdentity(r.roster[0],'java').identifier,texture)
  assert.throws(()=>normalize(body(now-121000),now))
  assert.throws(()=>normalize({...body(now),online:0},now))
  assert.equal(normalize({...body(now),players:[{...body(now).players[0],texture:'https://evil.invalid'}]},now).roster[0].texture,undefined)
})
test('guild pairing hash scopes snapshots, enforces freshness, rate limit, and revoke',()=>{
  let now=1000000
  const states={[A]:{servers:[{id:S,type:'java',bridgeTokenHash:digest(T)}]},[B]:{servers:[{id:S,type:'java',bridgeTokenHash:digest('c'.repeat(64))}]}}
  const db={get:g=>states[g]||{servers:[]},change:(g,fn)=>fn(states[g])}
  const bridge=createBridge({store:()=>db,now:()=>now})
  assert.equal(bridge.accept(B,S,'Bearer '+T,body(now)),401)
  assert.equal(bridge.accept(A,S,'Bearer '+T,body(now)),204)
  assert.equal(bridge.get(A,states[A].servers[0]).onlineCount,1)
  assert.equal(bridge.get(B,states[B].servers[0]),null)
  assert.equal(bridge.accept(A,S,'Bearer '+T,body(now)),429)
  now+=4000
  assert.equal(bridge.accept(A,S,'Bearer '+T,body(now-4000)),409)
  assert.equal(bridge.accept(A,S,'Bearer '+T,{...body(now),online:0,players:[]}),204)
  assert.deepEqual(bridge.get(A,states[A].servers[0]).left,['Alex'])
  now+=91000
  assert.equal(bridge.get(A,states[A].servers[0]),null)
  bridge.revoke(A,S)
  assert.equal(bridge.accept(A,S,'Bearer '+T,body(now)),401)
})
test('HTTPS is required except loopback and live texture avoids account lookup',async()=>{
  assert.throws(()=>config({ORION_BRIDGE_PUBLIC_URL:'http://example.com'}))
  assert.equal(config({ORION_BRIDGE_PUBLIC_URL:'http://127.0.0.1:8787'}).port,8787)
  const skins=createSkinResolver({fetcher:()=>{throw Error('unexpected network lookup')}})
  assert.equal(await skins.resolve({name:'Alex',texture},'java'),'https://mc-heads.net/avatar/'+texture+'/64.png')
})
