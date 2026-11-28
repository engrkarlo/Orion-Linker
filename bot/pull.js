'use strict'
const https=require('node:https')
const dns=require('node:dns').promises
const {isIP}=require('node:net')
const fs=require('node:fs')
const path=require('node:path')
const {randomBytes,createCipheriv,createDecipheriv,createHash}=require('node:crypto')
const TOKEN=/^[a-f0-9]{64}$/
const MAX_BODY=1024*1024
function validateOrigin(value){
  let url
  try{url=new URL(value)}catch{throw Error('Enter an HTTPS domain.')}
  if(url.protocol!=='https:'||url.username||url.password||url.port||url.search||url.hash||!['','/','/v1/snapshot'].includes(url.pathname)||isIP(url.hostname)||!/^([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*$/i.test(url.hostname)||/\.(local|internal|localhost|lan|test|invalid|onion|home|example)$/i.test(url.hostname))throw Error('Use a public HTTPS domain on port 443, without credentials or a custom path.')
  return url.origin
}
function publicIPv4(ip){
  if(isIP(ip)!==4)return false
  const [a,b,c]=ip.split('.').map(Number)
  return !(a===0||a===10||a===127||a>=224||(a===100&&b>=64&&b<=127)||(a===169&&b===254)||(a===172&&b>=16&&b<=31)||(a===192&&(b===168||b===0||(b===88&&c===99)))||(a===198&&(b===18||b===19||(b===51&&c===100)))||(a===203&&b===0&&c===113))
}
// Keep the recoverable credential encrypted at rest; never echo it in a Discord modal.
function createVault(file=path.join(process.cwd(),'data','orionbridge.key')){
  let key
  function getKey(){
    if(key)return key
    fs.mkdirSync(path.dirname(file),{recursive:true})
    try{fs.writeFileSync(file,randomBytes(32),{mode:0o600,flag:'wx'})}catch(e){if(e.code!=='EEXIST')throw e}
    key=fs.readFileSync(file)
    if(key.length!==32){key=null;throw Error('Invalid OrionBridge encryption key.')}
    return key
  }
  return {
    seal(token,scope){if(!TOKEN.test(token))throw Error('Token must be 64 lowercase hexadecimal characters.');const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',getKey(),iv);c.setAAD(Buffer.from(scope));const value=Buffer.concat([c.update(token,'utf8'),c.final()]);return Buffer.concat([iv,c.getAuthTag(),value]).toString('base64')},
    open(value,scope){const b=Buffer.from(value,'base64'),c=createDecipheriv('aes-256-gcm',getKey(),b.subarray(0,12));c.setAAD(Buffer.from(scope));c.setAuthTag(b.subarray(12,28));return Buffer.concat([c.update(b.subarray(28)),c.final()]).toString('utf8')}
  }
}
async function fetchBridge(origin,token,{resolve=dns.resolve4,request=https.request,route='/v1/snapshot',method='GET',body,headers={}}={}){
  if(![['GET','/v1/snapshot'],['GET','/v2/events'],['POST','/v2/acknowledge'],['POST','/v2/actions'],['GET','/v2/verification'],['POST','/v2/verification']].some(([m,p])=>m===method&&p===route))throw Error('Unsupported bridge route')
  if(body!=null&&(!Buffer.isBuffer(body)||body.length>4096))throw Error('Invalid bridge body')
  const url=new URL(validateOrigin(origin))
  // Resolve and pin the destination address for this connection; do not
  // permit DNS rebinding, redirects, IP literals or private address ranges.
  let timer
  let ips
  try{ips=await Promise.race([resolve(url.hostname),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('DNS timeout')),3000);timer.unref?.()})])}finally{clearTimeout(timer)}
  if(!ips.length||ips.some(ip=>!publicIPv4(ip)))throw Error('Bridge domain does not resolve to a public IPv4 address.')
  return new Promise((resolve,reject)=>{
    let deadline
    const req=request({hostname:ips[0],port:443,servername:url.hostname,method,path:route,agent:false,headers:{...headers,Host:url.hostname,Authorization:'Bearer '+token,Accept:'application/json',...(body?{'Content-Type':'text/plain','Content-Length':body.length}:{})},rejectUnauthorized:true},res=>{
      if(res.statusCode!==200){res.destroy();reject(Error('Bridge HTTP '+res.statusCode));return}
      if(!/^application\/json(?:;|$)/i.test(res.headers['content-type']||'')||Number(res.headers['content-length'])>MAX_BODY){res.destroy();reject(Error('Invalid bridge response'));return}
      let bytes=0,parts=[]
      res.on('data',chunk=>{bytes+=chunk.length;if(bytes>MAX_BODY){res.destroy();reject(Error('Bridge response too large'));return}parts.push(chunk)})
      res.on('end',()=>{try{resolve(JSON.parse(Buffer.concat(parts).toString('utf8')))}catch{reject(Error('Invalid JSON'))}})
      res.on('error',reject);res.on('aborted',()=>reject(Error('Bridge response interrupted')))
    })
    deadline=setTimeout(()=>req.destroy(Error('Bridge timeout')),7000);deadline.unref?.()
    req.on('error',reject);req.on('close',()=>clearTimeout(deadline));req.end(body)
  })
}
function fetchSnapshot(origin,token,options={}){return fetchBridge(origin,token,{...options,route:'/v1/snapshot',method:'GET',body:undefined,headers:{}})}
function createPull({store,normalize,now=Date.now,fetcher=fetchSnapshot,vault=createVault()}){
  const cache=new Map(),pending=new Map()
  let active=0,epoch=0
  const scope=(guild,id)=>guild+':'+id
  const fingerprint=s=>createHash('sha256').update(s.bridgePull.origin+'\0'+s.bridgePull.secret).digest('hex')
  function configure(guild,id,endpoint,token,interval){
    const origin=validateOrigin(endpoint),seconds=Number(interval)
    if(!Number.isInteger(seconds)||seconds<5||seconds>60)throw Error('Polling interval must be 5–60 seconds.')
    const key=scope(guild,id)
    store().change(guild,state=>{
      const s=state.servers.find(s=>s.id===id&&s.type==='java')
      if(!s)throw Error('Select a registered Java server.')
      const secret=token?vault.seal(token,key):s.bridgePull?.secret
      if(!secret)throw Error('Enter the plugin token.')
      if(!token&&s.bridgePull.origin!==origin)throw Error('Enter the token again when changing the domain.')
      s.bridgePull={origin,secret,interval:seconds};delete s.bridgeTokenHash
    })
    cache.delete(key)
  }
  function forget(guild,id){cache.delete(scope(guild,id))}
  function peek(guild,s){
    if(!s?.bridgePull)return null
    const item=cache.get(scope(guild,s.id))
    return item?.fingerprint===fingerprint(s)&&item.value&&now()-item.value.sentAt<=90000&&now()-item.value.checkedAt<=90000?item.value:null
  }
  async function poll(guild,s,force=false){
    if(!s?.bridgePull)return null
    const key=scope(guild,s.id),fp=fingerprint(s),old=cache.get(key)
    const existing=peek(guild,s)
    if(old?.fingerprint===fp&&now()-old.attemptedAt<(force?3000:s.bridgePull.interval*1000))return existing
    if(pending.has(key))return pending.get(key).fingerprint===fp?pending.get(key).promise:null
    if(active>=8)return existing
    const generation=epoch
    active++
    const job=(async()=>{
      let value=null
      try{
        const token=vault.open(s.bridgePull.secret,key)
        value=normalize(await fetcher(s.bridgePull.origin,token),now())
        if(now()-value.sentAt>90000)throw Error('Stale snapshot')
        if(existing&&value.sentAt<existing.sentAt)throw Error('Out-of-order snapshot')
        if(existing){
          const a=new Set(existing.roster.map(p=>p.uuid||p.name)),b=new Set(value.roster.map(p=>p.uuid||p.name))
          value.joined=value.roster.filter(p=>!a.has(p.uuid||p.name)).map(p=>p.name).slice(0,10)
          value.left=existing.roster.filter(p=>!b.has(p.uuid||p.name)).map(p=>p.name).slice(0,10)
          value.changesAt=value.joined.length||value.left.length?now():existing.changesAt
          if(!value.joined.length&&!value.left.length&&now()-(value.changesAt||0)<60000){value.joined=existing.joined;value.left=existing.left}
        }
        value.bridgeMode='pull'
      }catch{value=null}
      const current=store().get(guild).servers.find(x=>x.id===s.id)
      if(epoch!==generation||!current?.bridgePull||fingerprint(current)!==fp)return null
      cache.delete(key);cache.set(key,{fingerprint:fp,attemptedAt:now(),value})
      while(cache.size>1000)cache.delete(cache.keys().next().value)
      return value
    })().finally(()=>{active--;if(pending.get(key)?.promise===job)pending.delete(key)})
    pending.set(key,{fingerprint:fp,promise:job})
    return job
  }
  function stop(){epoch++;cache.clear();pending.clear()}
  return {configure,forget,peek,poll,stop}
}
module.exports={createPull,createVault,validateOrigin,publicIPv4,fetchSnapshot,fetchBridge}
