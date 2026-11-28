'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {createSecurityStore}=require('../bot/security-store'),{deliverModeration}=require('../bot/moderation-pull')
const G='111111111111111111',H='222222222222222222',S='0123456789abcdef',T='fedcba9876543210',D='333333333333333333',E='444444444444444444',I='java:'+'a'.repeat(32)
test('approval, per-target acknowledgements, revocation revisions and expiry',async()=>{
 let now=1000000;const store=createSecurityStore({file:':memory:',secret:Buffer.alloc(32,1),now:()=>now})
 try{
  store.register(G,S);store.register(G,T);store.register(H,S)
  const c=store.createCase(G,S,{identity:I,actor:D,type:'TEMP_BAN',reason:'Test',time:'7d',scope:'GUILD_MINECRAFT'})
  assert.throws(()=>store.approve(G,c.id,D));assert.throws(()=>store.approve(H,c.id,E));store.approve(G,c.id,E)
  assert.equal(store.pending(H,S).length,0)
  const cfg={origin:'https://bridge.example.com',token:'a'.repeat(64),guild:G,server:S,key:'b'.repeat(64),store}
  const fetcher=async (origin,token,options)=>{const [id,rev]=options.body.toString().split('\n');return {case:id,revision:Number(rev),result:'PERSISTED'}}
  await deliverModeration({...cfg,fetcher});assert.equal(store.getCase(G,c.id).state,'PARTIAL')
  await deliverModeration({...cfg,server:T,fetcher});assert.equal(store.getCase(G,c.id).state,'ACTIVE')
  store.revoke(G,c.id,E,'Appeal accepted');assert.equal(store.pending(G,S)[0].revision,2);assert.equal(store.acknowledge(G,S,c.id,1),false)
  await deliverModeration({...cfg,fetcher});await deliverModeration({...cfg,server:T,fetcher});assert.equal(store.getCase(G,c.id).state,'REVOKED')
  const expired=store.createCase(G,S,{identity:I,actor:D,type:'MUTE',reason:'Test',time:'1m',scope:'MINECRAFT_SERVER'})
  store.approve(G,expired.id,E);now+=61000;store.expire();assert.equal(store.pending(G,S).length,0);assert.equal(store.getCase(G,expired.id).state,'EXPIRED')
 }finally{store.close()}
})
