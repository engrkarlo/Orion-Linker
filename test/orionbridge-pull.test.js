'use strict'
const test=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const {createPull,createVault,validateOrigin,publicIPv4,fetchSnapshot}=require('../bot/pull')
const {normalize}=require('../bot/receiver')
const A='111111111111111111',B='222222222222222222',S='0123456789abcdef',T='a'.repeat(64)
test('pull origin and DNS address restrictions',async()=>{
  assert.equal(validateOrigin('https://orionbridge.srein.xyz/v1/snapshot'),'https://orionbridge.srein.xyz')
  for(const url of ['http://example.com','https://127.0.0.1','https://example.com:25762','https://example.com/private','https://user:pass@example.com','https://localhost','https://example.com?token=abc'])assert.throws(()=>validateOrigin(url))
  for(const ip of ['127.0.0.1','10.0.0.1','169.254.169.254','172.16.0.1','192.168.1.1','100.64.0.1','::1','0.0.0.0','192.0.2.1'])assert.equal(publicIPv4(ip),false)
  assert.equal(publicIPv4('8.8.8.8'),true)
  await assert.rejects(fetchSnapshot('https://orionbridge.srein.xyz',T,{resolve:async()=>['127.0.0.1'],request:()=>{throw Error('must not connect')}}),/public IPv4/)
})
test('encrypted credential scope, isolated cache, freshness, fallback and rotation',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'orion-pull-'))
  try{
    const vault=createVault(path.join(dir,'key'))
    const states={[A]:{servers:[{id:S,type:'java'}]},[B]:{servers:[{id:S,type:'java'}]}}
    const db={get:g=>states[g],change:(g,fn)=>fn(states[g])}
    let now=1000000,calls=0,fail=false
    const pull=createPull({store:()=>db,normalize,vault,now:()=>now,fetcher:async (origin,token)=>{calls++;if(fail)throw Error('offline');assert.equal(origin,'https://orionbridge.srein.xyz');assert.equal(token,T);return {protocol:1,sentAt:now,online:1,max:50,players:[{name:'Alex',uuid:'12345678-1234-4234-8234-123456789abc',texture:'b'.repeat(64)}]}}})
    pull.configure(A,S,'https://orionbridge.srein.xyz',T,10)
    const s=states[A].servers[0]
    assert.equal(s.bridgePull.secret.includes(T),false)
    assert.equal(vault.open(s.bridgePull.secret,A+':'+S),T)
    assert.throws(()=>vault.open(s.bridgePull.secret,B+':'+S))
    assert.throws(()=>pull.configure(A,S,'https://another.example.com','',10))
    const [a,b]=await Promise.all([pull.poll(A,s),pull.poll(A,s)])
    assert.equal(a,b);assert.equal(a.bridgeMode,'pull');assert.equal(calls,1)
    assert.equal(pull.peek(B,states[B].servers[0]),null)
    assert.equal((await pull.poll(A,s)).onlineCount,1)
    now+=11000;fail=true
    assert.equal(await pull.poll(A,s),null)
    assert.equal(pull.peek(A,s),null)
    pull.stop()
  }finally{fs.rmSync(dir,{recursive:true,force:true})}
})
