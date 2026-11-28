'use strict'
const {fetchBridge}=require('./pull')
const {signedHeaders}=require('./audit-pull')
const {identity}=require('./security-store')
const UUID=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/,DISCORD=/^\d{17,20}$/
function createVerificationService({store,member,fetcher=fetchBridge,now=Date.now,leaseSeconds=60}){
 if(typeof member!=='function'||!Number.isInteger(leaseSeconds)||leaseSeconds<15||leaseSeconds>60)throw Error('A guild membership verifier and bounded lease are required')
 const pending=new Set(),cursor=new Map(),attempts=new Map()
 async function currentMember(g,d){
  if(typeof d!=='string'||!DISCORD.test(d))return null
  const m=await member(g,d)
  if(!m||m.id!==d)return null
  const name=String(m.displayName||m.username||d).replace(/[\u0000-\u001f\u007f]/g,' ').slice(0,80)
  return {id:d,name:name.trim()||d}
 }
 function signedCall(cfg,method,route,body){
  return fetcher(cfg.origin,cfg.token,{route,method,body,headers:signedHeaders({guild:cfg.guild,server:cfg.server,key:cfg.key,method,path:route,body,now:now()})})
 }
 async function action(cfg,session,op,...fields){
  const body=Buffer.from([op,session.player,session.session,session.identity,...fields].join('\n'))
  const r=await signedCall(cfg,'POST','/v2/verification',body)
  if(!r||typeof r.result!=='string')throw Error('Invalid verification receipt')
  return r.result
 }
 function validate(s){
  if(!s||!UUID.test(s.player)||!UUID.test(s.session)||typeof s.hasCode!=='boolean'||typeof s.confirmed!=='boolean'||!Number.isSafeInteger(s.leaseUntil)||s.leaseUntil<0||s.request!==null&&!UUID.test(s.request)||s.claimant!==null&&!DISCORD.test(s.claimant))throw Error('Invalid verification session')
  identity(s.identity);return s
 }
 async function poll(cfg){
  const scope=cfg.guild+':'+cfg.server
  if(pending.has(scope))return {busy:true}
  if(pending.size>=16)throw Error('Verification polling capacity reached')
  pending.add(scope)
  try{
   const batch=await signedCall(cfg,'GET','/v2/verification')
   if(batch?.protocol!==2||!Array.isArray(batch.sessions)||batch.sessions.length>2000)throw Error('Invalid verification snapshot')
   const sessions=batch.sessions.map(validate).sort((a,b)=>a.player.localeCompare(b.player)),offset=(cursor.get(scope)||0)%Math.max(1,sessions.length)
   // Round robin bounds membership queries and control requests even for large servers.
   let processed=0,actions=0
   for(let i=0;i<sessions.length&&actions<25&&processed<100;i++){
    const s=sessions[(offset+i)%sessions.length];processed++
    let linked=store.linked(cfg.guild,s.identity)
    if(!linked&&s.confirmed&&s.request&&s.claimant){
     actions++
     const m=await currentMember(cfg.guild,s.claimant)
     if(m){const claim=store.pendingClaim(cfg.guild,cfg.server,s.identity,s.request);if(claim?.discord===m.id){try{linked=store.confirm(cfg.guild,cfg.server,s.identity,s.request,m.id)}catch{linked=store.linked(cfg.guild,s.identity)}}}
    }
    if(linked){
     if(s.leaseUntil>now()+leaseSeconds*500)continue
     actions++
     const m=await currentMember(cfg.guild,linked.discord),issued=now()
     if(m)await action(cfg,s,'LEASE',m.id,issued,issued+leaseSeconds*1000)
     else if(s.leaseUntil>0)await action(cfg,s,'RESTRICT',issued)
     continue
    }
    if(s.leaseUntil>0){actions++;await action(cfg,s,'RESTRICT',now());continue}
    if(!s.request)continue
    if(!s.hasCode){
     actions++
     const code=store.issue(cfg.guild,cfg.server,s.identity,s.request)
     await action(cfg,s,'OFFER',s.request,code,now()+300000)
    }else if(!s.claimant){
     const claim=store.pendingClaim(cfg.guild,cfg.server,s.identity,s.request)
     if(claim){
      actions++;const m=await currentMember(cfg.guild,claim.discord)
      if(m)await action(cfg,s,'CLAIM',s.request,m.id,Buffer.from(m.name).toString('base64url'))
     }
    }
   }
   cursor.set(scope,offset+processed);if(cursor.size>1000)cursor.delete(cursor.keys().next().value)
   return {sessions:sessions.length,processed,actions}
  }finally{pending.delete(scope)}
 }
 async function claimCode(cfg,discord,code){
  if(typeof discord!=='string'||!DISCORD.test(discord))throw Error('Invalid Discord account')
  const key=cfg.guild+':'+discord,t=now()
  if(attempts.size>=10000){for(const [k,v] of attempts)if(v.until<=t)attempts.delete(k);if(attempts.size>=10000&&!attempts.has(key))throw Error('Verification busy')}
  let rate=attempts.get(key)
  if(!rate||rate.until<=t){rate={count:0,until:t+60000};attempts.set(key,rate)}
  if(++rate.count>5)throw Error('Too many verification attempts')
  if(!await currentMember(cfg.guild,discord))throw Error('Discord membership required')
  return store.claim(cfg.guild,cfg.server,String(code).trim().toLowerCase(),discord)
 }
 return {poll,claimCode}
}
module.exports={createVerificationService}
