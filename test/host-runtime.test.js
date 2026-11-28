'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path')
const {createHostRuntime}=require('../bot/host-runtime')
test('host polls only enabled paired guilds and persists alert before deduplicating delivery',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ob-host-')),g='123456789012345678',other='223456789012345678',s='0123456789abcdef',ch='323456789012345678',event='11111111-1111-4111-8111-111111111111'
 let clock=Date.now(),sent=0,calls=0
 const channel={guildId:g,id:ch,type:0,guild:{roles:{everyone:{}}},permissionsFor:()=>({has:()=>false}),send:async()=>({id:String(423456789012345670n+BigInt(++sent))})}
 const guild={channels:{fetch:async()=>channel}}
 const rt=createHostRuntime({dir,client:{guilds:{cache:new Map([[g,guild]])}},manager:{isGuildEnabled:x=>x===g},registry:{get:()=>({servers:[{id:s,type:'java',name:'Test',bridgePull:{origin:'https://example.com',secret:'encrypted'}}]})},telemetryVault:{open:()=> 'a'.repeat(64)},now:()=>clock,fetcher:async(origin,token,opt)=>{
  calls++;assert.equal(opt.headers['X-Orion-Guild'],g)
  if(opt.route==='/v2/events')return {protocol:2,droppedSinceStartup:0,events:[{id:event,at:clock,kind:'OP_STATUS_CHANGED',identity:null,detail:'Observed change',source:'Paper'}]}
  assert.equal(opt.route,'/v2/acknowledge');return {acknowledged:1}
 }})
 try{
  rt.settings.setup(g,s);rt.settings.setup(other,s)
  rt.settings.update(g,s,{audit:true,securityChannel:ch});rt.settings.update(other,s,{audit:true,securityChannel:ch})
  await rt.tick();clock+=3000;await rt.tick()
  assert.equal(sent,1);assert.equal(calls,4);assert.equal(rt.store.events(g,s).length,1);assert.equal(rt.store.events(other,s).length,0)
 }finally{await rt.stop();fs.rmSync(dir,{recursive:true,force:true})}
})
test('host rejects a guild without permission before returning setup credentials',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ob-auth-')),g='123456789012345678',s='0123456789abcdef',d='223456789012345678'
 const member={id:d,user:{bot:false},permissions:{has:()=>false},roles:{cache:new Map()}}
 let reply
 const rt=createHostRuntime({dir,client:{guilds:{cache:new Map([[g,{members:{fetch:async()=>member}}]])}},manager:{isGuildEnabled:()=>true},registry:{get:()=>({servers:[{id:s,type:'java',name:'Test',bridgePull:{origin:'https://example.com',secret:'encrypted'}}]})}})
 try{
  await rt.interaction({customId:'ob2:setup:'+s,guildId:g,user:{id:d},deferReply:async function(){this.deferred=true},editReply:async p=>{reply=p}})
  assert.equal(rt.settings.get(g,s),null);assert.match(JSON.stringify(reply),/Unable to complete action/)
 }finally{await rt.stop();fs.rmSync(dir,{recursive:true,force:true})}
})
