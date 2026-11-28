'use strict'
const {fetchBridge}=require('./pull')
const {signedHeaders}=require('./audit-pull')
// The host must authorize moderators and approval scopes before calling the store.
// A distinct control key is required; no arbitrary command strings are accepted.
async function deliverModeration({origin,token,guild,server,key,store,fetcher=fetchBridge}){
 const results=[]
 store.expire()
 for(const action of store.pending(guild,server,25)){
  const body=Buffer.from([action.case_id,action.revision,action.identity,action.type,action.expires??0,action.operation,Buffer.from(action.reason).toString('base64url')].join('\n'))
  const route='/v2/actions',method='POST'
  const receipt=await fetcher(origin,token,{route,method,body,headers:signedHeaders({guild,server,key,method,path:route,body})})
  if(receipt?.case!==action.case_id||receipt.revision!==action.revision||typeof receipt.result!=='string')throw Error('Invalid moderation receipt')
  if(receipt.result==='PERSISTED')store.acknowledge(guild,server,action.case_id,action.revision)
  results.push({id:action.case_id,revision:action.revision,result:receipt.result})
 }
 return results
}
module.exports={deliverModeration}
