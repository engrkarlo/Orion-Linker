'use strict'
const http=require('node:http')
const {randomBytes,createHash,timingSafeEqual}=require('node:crypto')
const {normalizePlayers,cleanUuid,MAX_PLAYERS}=require('./players')
const {decodeIcon}=require('./icon')
const GUILD=/^\d{17,20}$/, SERVER=/^[a-f0-9]{16}$/, TOKEN=/^[a-f0-9]{64}$/, TEXTURE=/^[a-f0-9]{32,64}$/
const MAX_BODY=1024*1024, TTL=90000, MAX_SNAPSHOTS=1000
const digest=token=>createHash('sha256').update(token).digest('hex')
const pairKey=(guild,server)=>guild+':'+server
function getServer(store,guild,id){
  if(!GUILD.test(guild)||!SERVER.test(id))return null
  return store.get(guild).servers.find(s=>s.id===id)||null
}
function config(env=process.env){
  if(!env.ORION_BRIDGE_PUBLIC_URL)return null
  const url=new URL(env.ORION_BRIDGE_PUBLIC_URL)
  const loopback=['127.0.0.1','localhost','[::1]'].includes(url.hostname)
  if((url.protocol!=='https:'&&!(loopback&&url.protocol==='http:'))||url.username||url.password||url.search||url.hash)throw Error('Bridge public URL must be HTTPS (HTTP only on loopback).')
  const host=env.ORION_BRIDGE_BIND||'127.0.0.1',port=Number(env.ORION_BRIDGE_PORT||8787)
  if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid bridge port.')
  return {base:url.href.replace(/\/+$/,''),host,port}
}
function normalize(body,now){
  if(!body||body.protocol!==1||!Number.isSafeInteger(body.sentAt)||Math.abs(now-body.sentAt)>120000||!Array.isArray(body.players)||body.players.length>MAX_PLAYERS)throw Error('Invalid snapshot or clock skew')
  const count=(n,limit=1000000)=>Number.isSafeInteger(n)&&n>=0&&n<=limit?n:0
  const decimal=(n,limit)=>typeof n==='number'&&Number.isFinite(n)&&n>=0&&n<=limit?n:null
  const online=count(body.online),max=count(body.max)
  const {roster}=normalizePlayers({list:body.players})
  if(online<roster.length)throw Error('Player count smaller than roster')
  const byName=new Map(body.players.filter(p=>p&&typeof p.name==='string').map(p=>[p.name.toLowerCase(),p]))
  for(const p of roster){
    const raw=byName.get(p.name.toLowerCase())
    if(raw&&cleanUuid(raw.uuid)===p.uuid&&typeof raw.texture==='string'&&TEXTURE.test(raw.texture)){p.texture=raw.texture;p.skinSource=raw.skinSource==='stored'?'stored':'live'}
    if(raw&&Number.isSafeInteger(raw.ping)&&raw.ping>=0&&raw.ping<=60000)p.ping=raw.ping
  }
  return {online:true,onlineCount:online,max,roster,list:roster.map(p=>p.name),rosterLimited:online>roster.length,version:typeof body.version==='string'?body.version.slice(0,100):'',motd:typeof body.motd==='string'?body.motd.slice(0,800):'',icon:decodeIcon(body.icon),checkedAt:now,sentAt:body.sentAt,error:false,bridge:true,tps:decimal(body.tps,100),mspt:decimal(body.mspt,1000000),memoryUsed:count(body.memoryUsed,Number.MAX_SAFE_INTEGER),memoryMax:count(body.memoryMax,Number.MAX_SAFE_INTEGER)}
}
function createBridge({store,now=Date.now,logger=console}){
  const snapshots=new Map()
  let listener=null,settings=null
  function pair(guild,id){
    if(!settings||!listener?.listening)throw Error('OrionBridge receiver is not running. Ask the bot operator to configure ORION_BRIDGE_PUBLIC_URL.')
    const token=randomBytes(32).toString('hex')
    store().change(guild,state=>{
      const s=state.servers.find(s=>s.id===id)
      if(!s||s.type!=='java')throw Error('Select a registered Java/Paper server.')
      s.bridgeTokenHash=digest(token);delete s.bridgePull
    })
    snapshots.delete(pairKey(guild,id))
    return {endpoint:settings.base+'/v1/snapshot/'+guild+'/'+id,token}
  }
  function revoke(guild,id){
    store().change(guild,state=>{const s=state.servers.find(s=>s.id===id);if(!s)throw Error('Server not found.');delete s.bridgeTokenHash;delete s.bridgePull})
    snapshots.delete(pairKey(guild,id))
  }
  function get(guild,s){
    if(!s?.bridgeTokenHash)return null
    const item=snapshots.get(pairKey(guild,s.id))
    if(!item||item.hash!==s.bridgeTokenHash||now()-item.value.checkedAt>TTL)return null
    return item.value
  }
  function accept(guild,id,auth,body){
    const s=getServer(store(),guild,id)
    const token=typeof auth==='string'&&auth.startsWith('Bearer ')?auth.slice(7):''
    if(!s||s.type!=='java'||!TOKEN.test(token)||!TOKEN.test(s.bridgeTokenHash||'')||!timingSafeEqual(Buffer.from(digest(token),'hex'),Buffer.from(s.bridgeTokenHash,'hex')))return 401
    const key=pairKey(guild,id),previous=snapshots.get(key),time=now()
    if(previous&&previous.hash===s.bridgeTokenHash&&time-previous.value.checkedAt<3000)return 429
    let value
    try{value=normalize(body,time)}catch{return 400}
    if(previous&&previous.hash===s.bridgeTokenHash&&value.sentAt<=previous.value.sentAt)return 409
    if(previous&&previous.hash===s.bridgeTokenHash&&time-previous.value.checkedAt<=TTL){
      const old=new Set(previous.value.roster.map(p=>p.uuid||p.name)),fresh=new Set(value.roster.map(p=>p.uuid||p.name))
      value.joined=value.roster.filter(p=>!old.has(p.uuid||p.name)).map(p=>p.name).slice(0,10)
      value.left=previous.value.roster.filter(p=>!fresh.has(p.uuid||p.name)).map(p=>p.name).slice(0,10)
      value.changesAt=value.joined.length||value.left.length?time:previous.value.changesAt
      if(!value.joined.length&&!value.left.length&&time-(value.changesAt||0)<60000){value.joined=previous.value.joined;value.left=previous.value.left}
    }
    snapshots.delete(key);snapshots.set(key,{hash:s.bridgeTokenHash,value})
    while(snapshots.size>MAX_SNAPSHOTS)snapshots.delete(snapshots.keys().next().value)
    return 204
  }
  function handle(req,res){
    const finish=code=>{if(!res.headersSent&&!res.destroyed){res.writeHead(code,{'Cache-Control':'no-store','Content-Length':0});res.end()}}
    const route=/^\/v1\/snapshot\/(\d{17,20})\/([a-f0-9]{16})$/.exec(req.url||'')
    if(req.method!=='POST'||!route){req.resume();return finish(404)}
    if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']||'')){req.resume();return finish(415)}
    const [,guild,id]=route
    // Authenticate before buffering an attacker-controlled request body.
    const s=getServer(store(),guild,id),auth=req.headers.authorization
    const token=typeof auth==='string'&&auth.startsWith('Bearer ')?auth.slice(7):''
    if(!s||s.type!=='java'||!TOKEN.test(token)||!TOKEN.test(s.bridgeTokenHash||'')||!timingSafeEqual(Buffer.from(digest(token),'hex'),Buffer.from(s.bridgeTokenHash,'hex'))){req.resume();return finish(401)}
    if(Number(req.headers['content-length'])>MAX_BODY){req.resume();return finish(413)}
    let size=0,chunks=[],rejected=false
    req.on('data',chunk=>{if(rejected)return;size+=chunk.length;if(size>MAX_BODY){rejected=true;chunks=[];finish(413);return}chunks.push(chunk)})
    req.on('end',()=>{if(rejected)return;try{finish(accept(guild,id,auth,JSON.parse(Buffer.concat(chunks).toString('utf8'))))}catch{finish(400)}})
    req.on('error',()=>finish(400))
  }
  function start(env=process.env){
    if(listener)return
    try{settings=config(env)}catch(e){logger.warn('[OrionBridge] '+e.message);return}
    if(!settings)return
    listener=http.createServer({requestTimeout:10000,headersTimeout:5000,maxHeaderSize:8192},(req,res)=>{try{handle(req,res)}catch{if(!res.headersSent){res.writeHead(503);res.end()}}})
    listener.maxConnections=64
    listener.on('error',()=>{logger.warn('[OrionBridge] Receiver could not listen; check the configured bind and port.')})
    listener.listen(settings.port,settings.host,()=>logger.info('[OrionBridge] Telemetry receiver ready.'))
  }
  function stop(){if(listener){listener.close();listener.closeAllConnections?.();listener=null}settings=null;snapshots.clear()}
  return {start,stop,pair,revoke,get,accept}
}
module.exports={createBridge,normalize,config,digest,TTL}
