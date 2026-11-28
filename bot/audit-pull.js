'use strict'
const {randomBytes,createHmac,createHash}=require('node:crypto')
const {fetchBridge}=require('./pull')
function signedHeaders({guild,server,key,method,path,body=Buffer.alloc(0),now=Date.now(),nonce=randomBytes(16).toString('hex')}){
 if(typeof guild!=='string'||!/^\d{17,20}$/.test(guild)||typeof server!=='string'||! /^[a-f0-9]{16}$/.test(server)||typeof key!=='string'||! /^[a-f0-9]{64}$/.test(key)||! /^[a-f0-9]{32}$/.test(nonce)||!Number.isSafeInteger(now))throw Error('Invalid audit scope')
 const ts=String(now),digest=createHash('sha256').update(body).digest('hex')
 const signature=createHmac('sha256',Buffer.from(key,'hex')).update([method,path,guild,server,ts,nonce,digest].join('\n')).digest('hex')
 return {'X-Orion-Guild':guild,'X-Orion-Server':server,'X-Orion-Time':ts,'X-Orion-Nonce':nonce,'X-Orion-Signature':signature}
}
// The host must scope credentials to a registered guild/server and run this
// on a bounded background worker. It does not start a timer or Discord bot.
async function drainAudit({origin,token,guild,server,key,store,fetcher=fetchBridge}){
 const call=(method,route,body)=>fetcher(origin,token,{route,method,body,headers:signedHeaders({guild,server,key,method,path:route,body})})
 const batch=await call('GET','/v2/events')
 if(batch?.protocol!==2||!Array.isArray(batch.events)||batch.events.length>100||!Number.isSafeInteger(batch.droppedSinceStartup)||batch.droppedSinceStartup<0)throw Error('Invalid audit batch')
 const ids=[]
 // Record synchronously in the central database before acknowledging. A retry
 // is harmless because evidence has a unique guild/server/event constraint.
 for(const event of batch.events){store.recordEvent(guild,server,event);ids.push(event.id)}
 if(ids.length){
  const receipt=await call('POST','/v2/acknowledge',Buffer.from(ids.join('\n')))
  if(!Number.isSafeInteger(receipt?.acknowledged)||receipt.acknowledged<0||receipt.acknowledged>ids.length)throw Error('Invalid acknowledgement')
 }
 return {received:ids.length,droppedSinceStartup:batch.droppedSinceStartup}
}
module.exports={signedHeaders,drainAudit}
