'use strict'
const test=require('node:test'),assert=require('node:assert/strict')
const {createSecurityStore,duration}=require('../bot/security-store')
const G='111111111111111111',H='222222222222222222',S='0123456789abcdef',D='333333333333333333',E='444444444444444444',A='java:'+'a'.repeat(32),B='java:'+'b'.repeat(32)
function fixture(){let now=1000000;const store=createSecurityStore({file:':memory:',secret:Buffer.alloc(32,1),now:()=>now});store.register(G,S);store.register(H,S);return {store,advance:n=>now+=n}}
test('duration parser bounds and composite times',()=>{
 assert.equal(duration('2h30m'),9000000);assert.equal(duration('permanent'),null)
 for(const v of ['0s','2h2h','-1d','500d','1e9s','',null])assert.throws(()=>duration(v))
})
test('links require two confirmations, same session, uniqueness and guild isolation',()=>{
 const {store:s}=fixture();try{
 const c=s.issue(G,S,A,'session');assert.throws(()=>s.claim(H,S,c,D));s.claim(G,S,c,D)
 assert.throws(()=>s.confirm(G,S,A,'wrong',D));assert.throws(()=>s.confirm(G,S,A,'session',E))
 assert.equal(s.confirm(G,S,A,'session',D).discord,D);assert.throws(()=>s.confirm(G,S,A,'session',D))
 const second=s.issue(G,S,B,'session');assert.throws(()=>s.claim(G,S,second,D))
 const other=s.issue(H,S,A,'session');s.claim(H,S,other,D);assert.equal(s.confirm(H,S,A,'session',D).discord,D)
 }finally{s.close()}
})
test('regenerated and expired codes cannot be reused',()=>{
 const {store:s,advance}=fixture();try{
 const old=s.issue(G,S,A,'a'),fresh=s.issue(G,S,A,'b');assert.throws(()=>s.claim(G,S,old,D));s.claim(G,S,fresh,D)
 advance(300001);assert.throws(()=>s.confirm(G,S,A,'b',D))
 }finally{s.close()}
})
test('case types and durations validated; no false enforcement or cross-guild reads',()=>{
 const {store:s}=fixture();try{
 const base={identity:A,actor:D,type:'TEMP_BAN',reason:'Test',time:'7d',scope:'MINECRAFT_SERVER'}
 const c=s.createCase(G,S,base);assert.equal(c.state,'PENDING_APPROVAL');assert.equal(c.expires-c.created,604800000);assert.equal(s.getCase(H,c.id),null)
 assert.throws(()=>s.createCase(G,S,{...base,time:'permanent'}))
 assert.throws(()=>s.createCase(G,S,{...base,type:'DISCORD_TIMEOUT',scope:'DISCORD',time:'30d'}))
 assert.throws(()=>s.createCase(G,S,{...base,type:'MUTE',scope:'DISCORD'}))
 }finally{s.close()}
})
test('network matching is guild-isolated, time bounded, and not proof of ownership',()=>{
 const {store:s,advance}=fixture();try{
 const fp='a'.repeat(64);s.observeNetwork(G,S,A,fp);s.observeNetwork(H,S,B,fp)
 assert.equal(s.compare(G,S,D,A,B).matches,0);s.observeNetwork(G,S,B,fp)
 assert.deepEqual(s.compare(G,S,D,A,B),{matches:1,commonOwnershipConfirmed:false})
 advance(91*86400000);s.prune();assert.equal(s.compare(G,S,D,A,B).matches,0)
 }finally{s.close()}
})
test('events deduplicate and remain isolated',()=>{
 const {store:s}=fixture();try{
 const e={id:'12345678-1234-4234-8234-123456789abc',at:1000000,kind:'JOIN',identity:A,detail:'Authenticated join',source:'Paper'}
 assert.equal(s.recordEvent(G,S,e),true);assert.equal(s.recordEvent(G,S,e),false)
 assert.equal(s.events(G,S).length,1);assert.equal(s.events(H,S).length,0)
 }finally{s.close()}
})
