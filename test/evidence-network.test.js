'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {createSecurityStore}=require('../bot/security-store')
const G='111111111111111111',H='222222222222222222',S='0123456789abcdef',D='333333333333333333',A='java:'+'a'.repeat(32),B='java:'+'b'.repeat(32)
test('historical identity, durable alert receipts and network events',()=>{
 let now=1000000;const s=createSecurityStore({file:':memory:',secret:Buffer.alloc(32,1),now:()=>now})
 try{
  s.register(G,S);s.register(H,S)
  const code=s.issue(G,S,A,'session');s.claim(G,S,code,D);s.confirm(G,S,A,'session',D)
  const event={id:'12345678-1234-4234-8234-123456789abc',at:now,kind:'OP_STATUS_CHANGED',identity:A,detail:'Observed change',source:'Paper'}
  s.recordEvent(G,S,event);assert.equal(s.getEvent(G,S,event.id).discord,D);assert.equal(s.getEvent(H,S,event.id),null)
  s.recordEvent(G,S,{...event,id:'22345678-1234-4234-8234-123456789abc',at:now-1})
  assert.equal(s.getEvent(G,S,'22345678-1234-4234-8234-123456789abc').discord,null)
  assert.equal(s.unnotified(G,S).length,2);s.markAlert(G,S,event.id,'444444444444444444');assert.equal(s.unnotified(G,S).length,1)
  const n={...event,kind:'AUTHENTICATED_NETWORK',fingerprint:'b'.repeat(64)}
  s.recordEvent(G,S,{...n,id:'32345678-1234-4234-8234-123456789abc'})
  s.recordEvent(G,S,{...n,identity:B,id:'42345678-1234-4234-8234-123456789abc'})
  assert.equal(s.compare(G,S,D,A,B).matches,1);assert.ok(!s.getEvent(G,S,'32345678-1234-4234-8234-123456789abc').body.includes(n.fingerprint))
 }finally{s.close()}
})
