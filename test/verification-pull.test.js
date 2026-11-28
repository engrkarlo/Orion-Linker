'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {createSecurityStore}=require('../bot/security-store'),{createVerificationService}=require('../bot/verification-pull')
const G='111111111111111111',S='0123456789abcdef',D='222222222222222222',I='java:'+'a'.repeat(32),P='12345678-1234-4234-8234-123456789abc',SESSION='22345678-1234-4234-8234-123456789abc',REQUEST='32345678-1234-4234-8234-123456789abc'
test('two-party Pull verification does not lease until central confirmation',async()=>{
 let now=1000000;const store=createSecurityStore({file:':memory:',secret:Buffer.alloc(32,1),now:()=>now})
 store.register(G,S)
 let code,session={player:P,session:SESSION,identity:I,request:REQUEST,hasCode:false,claimant:null,confirmed:false,leaseUntil:0},allowed=true
 const service=createVerificationService({store,now:()=>now,member:async(g,d)=>allowed?{id:d,displayName:'Example'}:null,fetcher:async(origin,token,o)=>{
  if(o.method==='GET')return {protocol:2,sessions:[session]}
  const [op,p,s,i,...fields]=o.body.toString().split('\n')
  assert.equal(p,P);assert.equal(s,SESSION);assert.equal(i,I)
  if(op==='OFFER'){code=fields[1];session.hasCode=true}
  if(op==='CLAIM')session.claimant=fields[1]
  if(op==='LEASE')session.leaseUntil=Number(fields[2])
  if(op==='RESTRICT')session.leaseUntil=0
  return {result:'APPLIED'}
 }})
 const cfg={guild:G,server:S,key:'b'.repeat(64),token:'a'.repeat(64),origin:'https://bridge.example.com'}
 try{
  await service.poll(cfg);assert.ok(code);await service.claimCode(cfg,D,code);await service.poll(cfg);assert.equal(session.claimant,D)
  assert.equal(store.linked(G,I),null);assert.equal(session.leaseUntil,0)
  session.confirmed=true;await service.poll(cfg);assert.equal(store.linked(G,I).discord,D);assert.equal(session.leaseUntil,now+60000)
  allowed=false;now+=40000;await service.poll(cfg);assert.equal(session.leaseUntil,0)
 }finally{store.close()}
})
