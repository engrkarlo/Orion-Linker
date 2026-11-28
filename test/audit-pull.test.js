'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),{createHmac,createHash}=require('node:crypto')
const {signedHeaders,drainAudit}=require('../bot/audit-pull')
const cfg={origin:'https://bridge.example.com',token:'a'.repeat(64),guild:'111111111111111111',server:'0123456789abcdef',key:'b'.repeat(64)}
test('audit signature binds method, route, guild, server, nonce and body',()=>{
 const body=Buffer.from('abc'),now=1000000,nonce='1'.repeat(32),method='POST',path='/v2/acknowledge'
 const h=signedHeaders({...cfg,method,path,body,now,nonce})
 const expected=createHmac('sha256',Buffer.from(cfg.key,'hex')).update([method,path,cfg.guild,cfg.server,now,nonce,createHash('sha256').update(body).digest('hex')].join('\n')).digest('hex')
 assert.equal(h['X-Orion-Signature'],expected)
 assert.notEqual(signedHeaders({...cfg,method,path,body:Buffer.from('def'),now,nonce})['X-Orion-Signature'],expected)
})
test('ack only follows central persistence and is scoped',async()=>{
 const id='12345678-1234-4234-8234-123456789abc';let written=false,ack=false
 const fetcher=async (origin,token,opts)=>{
  assert.equal(opts.headers['X-Orion-Guild'],cfg.guild)
  if(opts.route==='/v2/events')return {protocol:2,droppedSinceStartup:0,events:[{id}]}
  assert.ok(written);assert.equal(opts.body.toString(),id);ack=true;return {acknowledged:1}
 }
 await assert.rejects(drainAudit({...cfg,fetcher,store:{recordEvent(){throw Error('disk full')}}}),/disk full/);assert.equal(ack,false)
 assert.deepEqual(await drainAudit({...cfg,fetcher,store:{recordEvent(g,s,e){assert.equal(g,cfg.guild);assert.equal(s,cfg.server);assert.equal(e.id,id);written=true}}}),{received:1,droppedSinceStartup:0});assert.equal(ack,true)
})
