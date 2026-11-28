'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const {createHostStore}=require('../bot/host-store'),ui=require('../bot/discord-ui')
const G='111111111111111111',H='222222222222222222',S='0123456789abcdef',T='fedcba9876543210'
test('host configuration separates credentials and channels; guild network key shared only within guild',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ob-host-'));try{
  let store=createHostStore(dir)
  store.setup(G,S);store.setup(G,T);store.setup(H,S)
  const keys=store.keys(G,S);assert.notEqual(keys.audit,keys.control);assert.equal(keys.network,store.keys(G,T).network);assert.notEqual(keys.network,store.keys(H,S).network)
  store.update(G,S,{verifyChannel:'333333333333333333',audit:true})
  assert.equal(store.get(H,S).verifyChannel,null);assert.throws(()=>store.update(G,S,{staffRoles:[G]}));store.close()
  store=createHostStore(dir);assert.deepEqual(store.keys(G,S),keys);assert.equal(store.get(G,S).audit,true);store.close()
 }finally{fs.rmSync(dir,{recursive:true,force:true})}
})
test('Components V2 payloads are scoped, private by default, bounded custom IDs and sanitized evidence',()=>{
 const server={id:S,name:'Test @everyone'},event={id:'12345678-1234-4234-8234-123456789abc',kind:'OP_STATUS_CHANGED',at:1000,identity:null,discord:null,body:JSON.stringify({detail:'@everyone suspicious',source:'Paper'})}
 const payload=ui.evidence(server,event,true)
 assert.equal(payload.flags,32832);assert.deepEqual(payload.allowedMentions.parse,[])
 const buttons=payload.components[0].components.at(-1).components
 assert.ok(buttons.every(b=>b.custom_id.length<=100));assert.equal(buttons.at(-1).disabled,true)
 assert.equal(ui.verifyPanel(server).flags,32768)
})
